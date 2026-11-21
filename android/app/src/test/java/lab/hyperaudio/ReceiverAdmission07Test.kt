package lab.hyperaudio

import org.junit.Assert.*
import org.junit.Test

class ReceiverAdmission07Test {
    @Test fun expiredManualSourceReplacementResetsSpeakerAndBufferBeforeDelivery() {
        var now=0L;val model=ReceiverControlModel("receiver",{now});model.start()
        val policy=ReceiverPlaybackPolicy();policy.focusGained();policy.speaker=true
        val queue=ReceiverJitterBuffer(8,2);var resets=0
        val admission=ReceiverAudioAdmission(model,{resets++;policy.senderSession();queue.clear()})
        assertTrue(admission.accept("ipA",model.token){queue.offer(1,1,ShortArray(480){7});queue.offer(2,1,ShortArray(480){7})})
        assertTrue(policy.speaker);assertEquals(2,queue.size);assertEquals(0,resets)
        now=12001
        assertTrue(admission.accept("ipB",model.token){assertFalse(policy.speaker);assertEquals(0,queue.size)})
        assertEquals(1,resets)
    }
    @Test fun replacementKeepsUserPauseAndPermanentFocusLoss() {
        var now=0L;val model=ReceiverControlModel("r",{now});model.start();val policy=ReceiverPlaybackPolicy();policy.focusGained()
        val admission=ReceiverAudioAdmission(model,{policy.senderSession()})
        admission.accept("a",model.token){};policy.pause();now=13000
        admission.accept("b",model.token){assertFalse(policy.wantsPlayback())}
        policy.resume();policy.focusLost(false);now=26000
        admission.accept("c",model.token){policy.focusGained();assertFalse(policy.wantsPlayback())}
    }
    @Test fun invalidTokenAndUnexpiredOtherSourceNeitherResetNorDeliver() {
        val model=ReceiverControlModel("r",{0});model.start();var resets=0;var delivered=0
        val admission=ReceiverAudioAdmission(model,{resets++})
        assertTrue(admission.accept("a",model.token){delivered++})
        assertFalse(admission.accept("b",model.token){delivered++})
        assertFalse(admission.accept("a","badtoken"){delivered++})
        assertEquals(0,resets);assertEquals(1,delivered)
    }
}
