package lab.hyperaudio

import org.junit.Assert.*
import org.junit.Test

class ReceiverFeed07Test {
    @Test fun packetIntervalAndPrebufferGapsMustNotFlushHardware() {
        val policy=ReceiverFeedPolicy()
        // HAP2 first prebuffer is 3*240 frames, below AudioTrack's >=1920-frame threshold.
        // Empty between 5ms packets must preserve those frames across subsequent bursts.
        for(age in listOf(0L,5L,15L,50L,200L,1499L)) assertFalse(policy.shouldFlushEmpty(age))
    }
    @Test fun realStreamStallFlushesAtTheSameBoundaryAsWaitingPresentation() {
        val policy=ReceiverFeedPolicy()
        assertFalse(policy.shouldFlushEmpty(1500));assertTrue(policy.shouldFlushEmpty(1501))
        assertTrue(policy.shouldFlushEmpty(20000));assertTrue(policy.shouldFlushEmpty(-1))
    }
    @Test fun multipleShortBurstsCanFillNativeStartThresholdWithoutAFlush() {
        val policy=ReceiverFeedPolicy();var queuedFrames=0
        repeat(4){queuedFrames+=3*240;if(policy.shouldFlushEmpty(15))queuedFrames=0}
        assertTrue(queuedFrames>=1920)
    }
}
