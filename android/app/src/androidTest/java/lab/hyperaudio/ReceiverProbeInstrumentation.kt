package lab.hyperaudio

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.content.pm.PackageManager
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/** Emulator-only instrumentation: invokes production services and sends the real wire protocol. */
class ReceiverProbeInstrumentation : Instrumentation() {
    private val checks = mutableListOf<String>()
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun expect(value: Boolean, label: String) {
        check(value) { "$label; receiver=${ReceiverState.snapshot}" }
        checks += label
    }
    private fun until(label: String, timeout: Long = 5_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
        expect(condition(), label)
    }
    private fun action(value: String, request: String? = null) {
        targetContext.startService(Intent(targetContext, ReceiverService::class.java).setAction(value).apply {
            if (request != null) putExtra(ReceiverService.EXTRA_REQUEST_ID, request)
        })
    }
    private class Peer(val id: String = UUID.randomUUID().toString()) : AutoCloseable {
        val socket = Socket("127.0.0.1", 50006).apply { soTimeout = 5_000 }
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val challenge = ControlProtocol.challenge(input, output, id, null, "Android instrumentation sender")
        fun send(value: JSONObject) = ControlProtocol.send(output, value)
        fun read(): JSONObject = ControlProtocol.read(input)
        override fun close() = socket.close()
    }
    private fun sendAudio(token: String, count: Int, channels: Int = 2, start: Int = 0, source: String? = null) {
        (if (source == null) DatagramSocket() else DatagramSocket(InetSocketAddress(source, 0))).use { socket ->
            val encoder = PacketEncoder(channels)
            val samples = ShortArray(480) { (kotlin.math.sin((it / channels) * 2.0 * Math.PI * 440 / 48_000) * 1000).toInt().toShort() }
            for (index in start until start + count) {
                val bytes = encoder.encode(samples, false, index.toLong(), System.nanoTime(), token)
                socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("127.0.0.1"), 50005))
                SystemClock.sleep(if (channels == 2) 5 else 10)
            }
        }
    }
    private fun referencePlayback() {
        val mask = android.media.AudioFormat.CHANNEL_OUT_STEREO
        val minimum = android.media.AudioTrack.getMinBufferSize(48000, mask, android.media.AudioFormat.ENCODING_PCM_16BIT)
        val reference = android.media.AudioTrack.Builder()
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(48000).setChannelMask(mask)
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(android.media.AudioTrack.MODE_STREAM).setBufferSizeInBytes(minimum * 2).build()
        try {
            reference.setVolume(0f)
            reference.play()
            val silence = ShortArray(480)
            val deadline = SystemClock.elapsedRealtime() + 1_500
            while (SystemClock.elapsedRealtime() < deadline) {
                reference.write(silence, 0, silence.size, android.media.AudioTrack.WRITE_NON_BLOCKING)
                SystemClock.sleep(5)
            }
            expect(reference.playbackHeadPosition > 0, "reference AudioTrack advances in this emulator")
        } finally { reference.pause(); reference.flush(); reference.release() }
    }
    override fun onStart() {
        val result = Bundle()
        val previous = AppRole.current(targetContext)
        var resultCode = Activity.RESULT_OK
        try {
            // A real foreground Activity meets Android15 playback/focus start requirements.
            AppRole.select(targetContext, AudioRole.RECEIVE)
            startActivitySync(Intent(targetContext, MainActivity::class.java).setAction("lab.hyperaudio.OPEN_RECEIVER")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync()
            referencePlayback()
            action(ReceiverService.ACTION_START)
            until("receiver starts without capture authorization") { ReceiverState.snapshot.running }
            until("idle playback thread waits without polling") {
                Thread.getAllStackTraces().keys.any { it.name == "HyperAudio-playback" && it.state == Thread.State.WAITING }
            }
            expect(targetContext.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED,
                "playback works with RECORD_AUDIO denied")

            until("control listener binds") {
                try { Socket("127.0.0.1", 50006).use { }; true } catch (_: Exception) { false }
            }
            Peer().use { peer ->
                peer.send(JSONObject().put("type", "pair"))
                until("first pairing needs explicit confirmation") { ReceiverState.snapshot.pending?.clientId == peer.id }
                action(ReceiverService.ACTION_DENY, ReceiverState.snapshot.pending!!.id)
                expect(peer.read().optString("reason") == "denied", "denied pairing fails")
            }
            var client = ""
            var secret = ""
            var receiver = ""
            var token = ""
            Peer().use { peer ->
                client = peer.id
                receiver = peer.challenge.getString("receiverId")
                peer.send(JSONObject().put("type", "pair"))
                until("approved pairing is pending") { ReceiverState.snapshot.pending?.clientId == peer.id }
                action(ReceiverService.ACTION_ALLOW, ReceiverState.snapshot.pending!!.id)
                val ready = peer.read()
                expect(ready.optString("type") == "ready", "pairing returns ready")
                expect(ready.optInt("port") == 50005, "production UDP port")
                secret = ready.getString("secret")
                token = ready.getString("token")
                expect(ready.getJSONArray("audioFormats").toString().contains("HAP2"), "stereo advertised")
            }
            val initial = ReceiverState.snapshot.received
            sendAudio(token, 40)
            until("valid PCM packets received") { ReceiverState.snapshot.received >= initial + 40 }
            expect(ReceiverState.snapshot.playedFrames == 0L, "headphones default never plays on speaker")
            val count = ReceiverState.snapshot.received
            sendAudio(if (token == "00000000") "ffffffff" else "00000000", 12, start = 40)
            SystemClock.sleep(300)
            expect(ReceiverState.snapshot.received == count, "wrong token rejected")
            DatagramSocket().use { socket ->
                val oversized = PacketEncoder(2).encode(ShortArray(480), false, 40, System.nanoTime(), token).copyOf(985)
                repeat(3) {
                    socket.send(DatagramPacket(oversized, oversized.size, InetAddress.getByName("127.0.0.1"), 50005))
                }
            }
            SystemClock.sleep(300)
            expect(ReceiverState.snapshot.received == count, "oversized UDP cannot bypass decoder through truncation")

            action(ReceiverService.ACTION_SPEAKER)
            sendAudio(token, 500, start = 40)
            until("actual AudioTrack playback head advances") { ReceiverState.snapshot.playedFrames > 0 }
            expect(ReceiverState.snapshot.channels == 2, "stereo decoded by receiver")

            Peer().use { peer ->
                peer.send(JSONObject().put("type", "pair"))
                expect(peer.read().optString("reason") == "busy", "second sender cannot displace active sender")
            }
            action(ReceiverService.ACTION_PAUSE)
            until("pause preserves receiver session") { ReceiverState.snapshot.running && ReceiverState.snapshot.paused }
            until("paused playback thread waits without polling") {
                Thread.getAllStackTraces().keys.any { it.name == "HyperAudio-playback" && it.state == Thread.State.WAITING }
            }
            val beforeResume = ReceiverState.snapshot.playedFrames
            Peer(client).use { peer ->
                peer.send(JSONObject().put("type", "resume").put("proof",
                    PairingProof.sign(secret, client, receiver, peer.challenge.getString("nonce"))))
                val resumed = peer.read()
                expect(resumed.optString("type") == "ready" && !resumed.has("secret"), "authenticated resume keeps stored key")
                token = resumed.getString("token")
            }
            until("reconnected token published") { ReceiverState.snapshot.token == token }
            expect(ReceiverState.snapshot.paused, "authenticated reconnect preserves manual pause")
            action(ReceiverService.ACTION_SPEAKER)
            action(ReceiverService.ACTION_RESUME)
            sendAudio(token, 120, start = 540)
            until("resume accepts fresh audio") { ReceiverState.snapshot.running && !ReceiverState.snapshot.paused }
            until("AudioTrack advances after resume") { ReceiverState.snapshot.playedFrames > beforeResume }

            Peer(client).use { peer ->
                peer.send(JSONObject().put("type", "inspect").put("proof",
                    PairingProof.sign(secret, client, receiver, peer.challenge.getString("nonce"))))
                val status = peer.read()
                expect(status.optString("type") == "status" && !status.has("secret") && !status.has("token"), "authenticated inspect hides secrets")
            }
            result.putLong("playedFrames", ReceiverState.snapshot.playedFrames)
            result.putLong("received", ReceiverState.snapshot.received)
            result.putString("actualOutput", ReceiverState.snapshot.actualOutput)
            until("network stall shows waiting instead of playback", 4_000) {
                ReceiverState.snapshot.waitingForAudio && ReceiverState.snapshot.audioPhase == "waiting"
            }
            action(ReceiverService.ACTION_STOP)
            until("stop releases receiver state") { !ReceiverState.snapshot.running }
            until("stop releases TCP listener") {
                try { Socket("127.0.0.1", 50006).use { }; false } catch (_: Exception) { true }
            }
            until("stop releases role lease") {
                if (AppRole.claim(AudioRole.RECEIVE, AppRole.epoch)) { AppRole.release(AudioRole.RECEIVE); true } else false
            }
            action(ReceiverService.ACTION_START)
            until("receiver restarts with fresh manual token") {
                ReceiverState.snapshot.running && ReceiverState.snapshot.token.isNotEmpty() && ReceiverState.snapshot.token != token
            }
            val manualToken = ReceiverState.snapshot.token
            action(ReceiverService.ACTION_SPEAKER)
            sendAudio(manualToken, 220, channels = 1, source = "127.0.0.1")
            until("manual source plays mono on explicitly selected speaker") { ReceiverState.snapshot.playedFrames > 0 }
            expect(ReceiverState.snapshot.speaker && ReceiverState.snapshot.channels == 1, "first manual source preserves explicit speaker choice")
            SystemClock.sleep(13_000)
            val beforeSourceChange = ReceiverState.snapshot.received
            val framesBeforeSourceChange = ReceiverState.snapshot.playedFrames
            sendAudio(manualToken, 40, channels = 1, start = 220, source = "127.0.0.2")
            until("new manual source returns to headphones default") {
                ReceiverState.snapshot.received > beforeSourceChange && !ReceiverState.snapshot.speaker
            }
            expect(ReceiverState.snapshot.playedFrames == framesBeforeSourceChange, "new manual source does not inherit speaker playback")
            action(ReceiverService.ACTION_STOP)
            until("final stop releases receiver") { !ReceiverState.snapshot.running }
            checks += "ALL_RECEIVER_NATIVE_CHECKS_PASSED"
            result.putString("checks", checks.joinToString("\n"))
        } catch (failure: Throwable) {
            resultCode = Activity.RESULT_CANCELED
            result.putString("checks", checks.joinToString("\n"))
            result.putString("failure", android.util.Log.getStackTraceString(failure))
        } finally {
            try { action(ReceiverService.ACTION_STOP) } catch (_: Exception) { }
            val deadline = SystemClock.elapsedRealtime() + 3_000
            while (ReceiverState.snapshot.running && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
            AppRole.select(targetContext, previous)
        }
        finish(resultCode, result)
    }
}
