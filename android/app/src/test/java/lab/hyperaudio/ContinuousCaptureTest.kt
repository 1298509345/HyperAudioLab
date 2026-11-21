package lab.hyperaudio

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import org.junit.Assert.*
import org.junit.Test

class ContinuousCaptureTest {
    private class Sink : ByteArrayOutputStream() {
        var closes = 0
        override fun close() { closes++; super.close() }
    }

    @Test fun archiveClosesAtExactLimitAndRejectsLaterWrites() {
        val sink = Sink()
        val archive = PcmArchive(sink, 4)
        assertTrue(archive.write(byteArrayOf(9, 1, 2, 9), 1, 2))
        assertFalse(archive.write(byteArrayOf(3, 4), 0, 2))
        assertFalse(archive.write(byteArrayOf(5, 6), 0, 2))
        archive.close()
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), sink.toByteArray())
        assertEquals(1, sink.closes)
    }

    @Test fun archiveTruncatesPacketCrossingLimit() {
        val sink = Sink()
        val archive = PcmArchive(sink, 3)
        assertFalse(archive.write(byteArrayOf(9, 1, 2, 3, 4), 1, 4))
        assertArrayEquals(byteArrayOf(1, 2, 3), sink.toByteArray())
        assertEquals(1, sink.closes)
    }

    @Test fun manualCloseBeforeLimitIsIdempotent() {
        val sink = Sink()
        val archive = PcmArchive(sink, 10)
        assertTrue(archive.write(byteArrayOf(1, 2), 0, 2))
        archive.close(); archive.close()
        assertFalse(archive.write(byteArrayOf(3), 0, 1))
        assertArrayEquals(byteArrayOf(1, 2), sink.toByteArray())
        assertEquals(1, sink.closes)
    }

    @Test fun defaultArchiveKeepsOnlyFiveMinutesOfSixMinuteInput() {
        var bytes = 0L
        var closes = 0
        val sink = object : OutputStream() {
            override fun write(value: Int) { bytes++ }
            override fun write(data: ByteArray, offset: Int, length: Int) { bytes += length }
            override fun close() { closes++ }
        }
        val archive = PcmArchive(sink)
        val frame = ByteArray(960)
        repeat(36_000) { frameIndex ->
            assertEquals(frameIndex < 29_999, archive.write(frame, 0, frame.size))
        }
        assertEquals(28_800_000L, bytes)
        assertEquals(1, closes)
    }

    @Test fun wakeLeaseRenewsBeforeExpiryThroughoutLongSession() {
        val acquiredAt = mutableListOf<Long>()
        var now = 0L
        var releases = 0
        val lease = CaptureWakeLease({ timeout ->
            assertEquals(300_000L, timeout)
            acquiredAt.add(now)
        }, { releases++ })
        while (now <= 1_800_000L) { lease.refresh(now); now += 250L }
        assertEquals((0L..7L).map { it * 240_000L }, acquiredAt)
        lease.close(); lease.close(); lease.refresh(now + 300_000)
        assertEquals(8, acquiredAt.size)
        assertEquals(1, releases)
    }

    @Test fun closingUnusedLeaseDoesNotReleaseOrAcquire() {
        var acquisitions = 0
        var releases = 0
        val lease = CaptureWakeLease({ acquisitions++ }, { releases++ })
        lease.close(); lease.refresh(0)
        assertEquals(0, acquisitions)
        assertEquals(0, releases)
    }
}
