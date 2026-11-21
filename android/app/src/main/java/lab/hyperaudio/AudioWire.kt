package lab.hyperaudio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject

/** HAP1: 480 mono frames / 10 ms. HAP2: 240 interleaved stereo frames / 5 ms.
 * Both formats contain exactly 480 PCM16 samples in a 984-byte datagram. */
object AudioWire {
    const val PACKET_BYTES = 984
    const val HEADER_BYTES = 24
    const val SAMPLE_COUNT = 480
    fun frames(channels: Int): Int { require(channels in 1..2); return SAMPLE_COUNT / channels }
    fun frameDurationMS(channels: Int): Int = frames(channels) * 1000 / 48000
    fun archiveBytes(channels: Int): Long { require(channels in 1..2); return 48000L * channels * 2 * 300 }
    fun channels(preferStereo: Boolean, supported: Boolean, initialized: Boolean): Int =
        if (preferStereo && supported && initialized) 2 else 1
    fun canResume(channels: Int, supported: Boolean) = channels == 1 || supported
    fun supportsStereo(ready: JSONObject): Boolean {
        val formats = ready.optJSONArray("audioFormats") ?: return false
        return (0 until formats.length()).any { formats.optString(it) == "HAP2" }
    }
}

/** Worker-owned reusable storage; send/write synchronously before the next encode. */
class PacketEncoder(private val channels: Int) {
    private val buffer = ByteBuffer.allocate(AudioWire.PACKET_BYTES)
    init { require(channels in 1..2) }
    fun encode(samples: ShortArray, paused: Boolean, sequence: Long, timestampNS: Long, token: String): ByteArray {
        require(samples.size == AudioWire.SAMPLE_COUNT)
        require(token.length == 8 && token.all { it in '0'..'9' || it in 'a'..'f' })
        buffer.clear(); buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(if (channels == 2) 0x48415032 else 0x48415031)
        buffer.putInt(sequence.toInt()); buffer.putLong(timestampNS)
        for (c in token) buffer.put(c.code.toByte())
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) buffer.putShort(if (paused) 0 else sample)
        return buffer.array()
    }
}
