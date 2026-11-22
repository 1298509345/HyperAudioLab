package lab.hyperaudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureMediaVolumeTest {
    private class Media(var volume: Int, var muted: Boolean = false) {
        val writes = mutableListOf<Int>()

        fun snapshot() = CaptureMediaVolume({ volume }, { muted }) {
            writes.add(it)
            volume = it
            muted = false
        }
    }

    @Test fun restoresVolumeAfterEitherCaptureMuteMode() {
        val zeroed = Media(7)
        val zeroedSnapshot = zeroed.snapshot()
        zeroed.volume = 0
        zeroedSnapshot.restore(CaptureEndReason.USER_STOPPED)
        assertEquals(listOf(7), zeroed.writes)

        val muted = Media(4)
        val mutedSnapshot = muted.snapshot()
        muted.muted = true
        mutedSnapshot.restore(CaptureEndReason.AUTHORIZATION_REVOKED)
        assertEquals(listOf(4), muted.writes)
        assertFalse(muted.muted)
    }

    @Test fun preservesOriginalSilenceAndVolumeAlreadyChangedByTheUser() {
        val originallyMuted = Media(6, muted = true)
        originallyMuted.snapshot().restore(CaptureEndReason.USER_STOPPED)
        assertTrue(originallyMuted.writes.isEmpty())
        val originallyZero = Media(0)
        originallyZero.snapshot().restore(CaptureEndReason.USER_STOPPED)
        assertTrue(originallyZero.writes.isEmpty())

        val adjusted = Media(5)
        val snapshot = adjusted.snapshot()
        adjusted.volume = 2
        snapshot.restore(CaptureEndReason.USER_STOPPED)
        assertEquals(2, adjusted.volume)
        assertTrue(adjusted.writes.isEmpty())
    }

    @Test fun sleepStaysSilentAndRepeatedTeardownDoesNotRestoreTwice() {
        val sleeping = Media(8)
        val sleepingSnapshot = sleeping.snapshot()
        sleeping.volume = 0
        sleepingSnapshot.restore(CaptureEndReason.SLEEP_EXPIRED)
        sleepingSnapshot.restore(CaptureEndReason.USER_STOPPED)
        assertTrue(sleeping.writes.isEmpty())

        val failedStart = Media(3)
        val failedSnapshot = failedStart.snapshot()
        failedStart.volume = 0
        failedSnapshot.restore(CaptureEndReason.CAPTURE_FAILED)
        failedStart.volume = 0
        failedSnapshot.restore(CaptureEndReason.USER_STOPPED)
        assertEquals(listOf(3), failedStart.writes)
    }
}
