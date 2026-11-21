package lab.hyperaudio

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class Audio05Test {
    @Test fun stereoKeepsInterleavedLeftRightAndFixedPacketSize() {
        val samples = ShortArray(480) { if (it % 2 == 0) 1234 else -2345 }
        val bytes = PacketEncoder(2).encode(samples, false, 0x1_ffff_ffffL, 123456L, "a1b2c3d4")
        assertEquals(984, bytes.size)
        assertEquals("HAP2", String(bytes, 0, 4, Charsets.US_ASCII))
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        assertEquals(-1, b.getInt(4)); assertEquals(123456L, b.getLong(8))
        assertEquals("a1b2c3d4", String(bytes, 16, 8, Charsets.US_ASCII))
        b.order(ByteOrder.LITTLE_ENDIAN)
        repeat(240) { assertEquals(1234.toShort(), b.getShort(24 + it*4)); assertEquals((-2345).toShort(), b.getShort(26 + it*4)) }
        assertEquals(5, AudioWire.frameDurationMS(2)); assertEquals(240, AudioWire.frames(2))
    }
    @Test fun monoRemainsHap1AndPauseZerosPayloadOnlyWithReusedStorage() {
        val encoder = PacketEncoder(1)
        val first = encoder.encode(ShortArray(480) { 11 }, false, 0, 1, "12345678")
        val paused = encoder.encode(ShortArray(480) { 99 }, true, 1, 2, "abcdef01")
        assertSame(first, paused); assertEquals("HAP1", String(paused, 0, 4, Charsets.US_ASCII))
        assertTrue(paused.drop(24).all { it == 0.toByte() })
        assertEquals(1, ByteBuffer.wrap(paused).getInt(4))
        assertEquals("abcdef01", String(paused, 16, 8, Charsets.US_ASCII))
        assertEquals(10, AudioWire.frameDurationMS(1))
    }
    @Test fun invalidTokenAndSampleCountNeverWritePacket() {
        val e = PacketEncoder(2)
        for (token in listOf("1234567", "123456789", "abcdefgh", "é1234567"))
            assertTrue(runCatching { e.encode(ShortArray(480), false, 0, 0, token) }.isFailure)
        assertTrue(runCatching { e.encode(ShortArray(960), false, 0, 0, "12345678") }.isFailure)
    }
    @Test fun stereoNeedsExplicitReadyCapabilityAndCaptureSuccess() {
        assertFalse(AudioWire.supportsStereo(JSONObject()))
        assertFalse(AudioWire.supportsStereo(JSONObject("{\"audioFormats\":[\"HAP1\"]}")))
        assertTrue(AudioWire.supportsStereo(JSONObject("{\"audioFormats\":[\"HAP1\",\"HAP2\"]}")))
        assertEquals(1, AudioWire.channels(true, false, true))
        assertEquals(1, AudioWire.channels(true, true, false))
        assertEquals(1, AudioWire.channels(false, true, true))
        assertEquals(2, AudioWire.channels(true, true, true))
        assertFalse(AudioWire.canResume(2, false)); assertTrue(AudioWire.canResume(1, false))
    }
    @Test fun stereoArchiveFiveMinutesQuotaAndDefaultMonoAreDistinct() {
        assertEquals(28_800_000L, AudioWire.archiveBytes(1)); assertEquals(57_600_000L, AudioWire.archiveBytes(2))
        var written = 0L
        val sink = object : java.io.OutputStream() {
            override fun write(b: Int) { written++ }
            override fun write(b: ByteArray, off: Int, len: Int) { written += len }
        }
        val archive = PcmArchive(sink, AudioWire.archiveBytes(2))
        repeat(60_001) { archive.write(ByteArray(960), 0, 960) }
        assertEquals(57_600_000L, written)
    }
    @Test fun sleepTimerExpiresByMonotonicDeadlineAndCancelPreventsExpiry() {
        val timer = SessionSleepTimer()
        timer.update(10, 15, 1000)
        assertEquals(900_000L, timer.remainingMS(10, 1000))
        assertFalse(timer.expired(10, 900_999)); assertTrue(timer.expired(10, 901_000))
        timer.update(10, 0, 901_000)
        assertNull(timer.remainingMS(10, 1_000_000)); assertFalse(timer.expired(10, 1_000_000))
    }
    @Test fun oldSessionTimerCannotStopNewSessionOrCancelItsTimer() {
        val timer = SessionSleepTimer()
        timer.update(1, 15, 0); timer.update(2, 30, 100)
        timer.cancel(1)
        assertFalse(timer.expired(1, Long.MAX_VALUE))
        assertEquals(1_800_000L, timer.remainingMS(2, 100))
        timer.cancel(2); assertFalse(timer.expired(2, Long.MAX_VALUE))
    }
    @Test fun metricsRotateAtLimitKeepOnlyThreeSegmentsAndNeverTouchPcm() {
        val root = Files.createTempDirectory("hyperaudio-metrics").toFile()
        try {
            val folder = root.resolve("session-100-1").apply { mkdir() }
            val pcm = folder.resolve("capture.pcm").apply { writeText("pcm") }
            val log = MetricsLog(folder, "header", segmentBytes=45)
            log.use { repeat(20) { log.line("$it," + "x".repeat(30)) } }
            assertTrue(log.awaitStopped(3000))
            val segments = folder.listFiles()!!.filter { it.name.startsWith("metrics-") }
            assertEquals(3, segments.size); assertTrue(segments.all { it.length() < 90 })
            assertTrue(segments.any { it.readText().contains("19,") }); assertEquals("pcm", pcm.readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun cleanupOnlyDeletesStrictMetricsProtectsCurrentAndEnforcesExpiryAndQuota() {
        val root = Files.createTempDirectory("hyperaudio-retain").toFile()
        try {
            fun session(name: String) = root.resolve(name).apply { mkdir() }
            val old = session("session-100-1")
            val expired = old.resolve("metrics.csv").apply { writeText("expired"); setLastModified(1) }
            val pcm = old.resolve("capture-48k-mono-s16le.pcm").apply { writeText("pcm"); setLastModified(1) }
            val unrelated = old.resolve("metrics-not-ours.csv").apply { writeText("keep") }
            val stranger = session("other").resolve("metrics.csv").apply { writeText("keep") }
            val current = session("session-200-2")
            val active = current.resolve("metrics-0.csv").apply { writeText("current".repeat(20)); setLastModified(1) }
            MetricsRetention.cleanup(root, current, nowMS=1_000_000, maxAgeMS=10_000, maxBytes=20)
            assertFalse(expired.exists()); assertTrue(active.exists()); assertTrue(pcm.exists()); assertTrue(unrelated.exists()); assertTrue(stranger.exists())
            val a = old.resolve("metrics-1.csv").apply { writeText("a".repeat(20)); setLastModified(990_000) }
            val b = old.resolve("metrics-2.csv").apply { writeText("b".repeat(20)); setLastModified(995_000) }
            MetricsRetention.cleanup(root, current, nowMS=1_000_000, maxAgeMS=100_000, maxBytes=active.length()+20)
            assertFalse(a.exists()); assertTrue(b.exists()); assertTrue(active.exists())
        } finally { root.deleteRecursively() }
    }
}
