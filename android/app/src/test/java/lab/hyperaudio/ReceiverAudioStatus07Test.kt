package lab.hyperaudio

import org.junit.Assert.*
import org.junit.Test

class ReceiverAudioStatus07Test {
    @Test fun enabledWithoutPacketsWaitsInsteadOfClaimingPlayback() {
        assertEquals("waiting",ReceiverAudioStatus.phase(true,false,-1,0,false))
    }
    @Test fun recentPacketsDistinguishBufferingFromActualPlayback() {
        assertEquals("buffering",ReceiverAudioStatus.phase(true,false,20,20,false))
        assertEquals("playing",ReceiverAudioStatus.phase(true,false,20,20,true))
        assertEquals("buffering",ReceiverAudioStatus.phase(true,false,20,0,false))
    }
    @Test fun stoppedUdpExpiresPlaybackEvenIfAStaleTrackFlagRemains() {
        assertEquals("playing",ReceiverAudioStatus.phase(true,false,1000,10,true))
        assertEquals("waiting",ReceiverAudioStatus.phase(true,false,1501,10,true))
        assertEquals("waiting",ReceiverAudioStatus.phase(true,false,20000,0,false))
    }
    @Test fun userPauseSurvivesPacketLossAndServiceStopIsSeparate() {
        assertEquals("paused",ReceiverAudioStatus.phase(true,true,20000,0,false))
        assertEquals("stopped",ReceiverAudioStatus.phase(false,true,20000,0,false))
    }
    @Test fun waitingFlagAndMediaPlaybackUseTheSameDerivedPhase() {
        for(p in listOf("waiting","buffering")) {assertTrue(ReceiverAudioStatus.waitingForAudio(p));assertFalse(ReceiverAudioStatus.isPlaying(p))}
        assertFalse(ReceiverAudioStatus.waitingForAudio("paused"));assertTrue(ReceiverAudioStatus.isPlaying("playing"))
    }
}
