package lab.hyperaudio

import org.junit.Assert.*
import org.junit.Test

class ReceiverBoundary07Test {
    private val token="0123abcd"
    @Test fun prebufferAcceptsEarlierOutOfOrderArrival() {
        val q=ReceiverJitterBuffer(8,3);val out=ShortArray(480)
        assertTrue(q.offer(12,1,ShortArray(480){12}));assertTrue(q.offer(10,1,ShortArray(480){10}));assertTrue(q.offer(11,1,ShortArray(480){11}))
        assertEquals(1,q.poll(out));assertEquals(10.toShort(),out[0]);assertEquals(1,q.poll(out));assertEquals(11.toShort(),out[0]);assertEquals(1,q.poll(out));assertEquals(12.toShort(),out[0])
    }
    @Test fun prebufferReordersAcrossUnsignedWrap() {
        val q=ReceiverJitterBuffer(8,3);val out=ShortArray(480)
        assertTrue(q.offer(0,1,ShortArray(480){3}));assertTrue(q.offer(0xfffffffe,1,ShortArray(480){1}));assertTrue(q.offer(0xffffffff,1,ShortArray(480){2}))
        repeat(3){assertEquals(1,q.poll(out));assertEquals((it+1).toShort(),out[0])}
    }
    @Test fun lateAndDuplicatePacketsNeverReplay() {
        val q=ReceiverJitterBuffer(8,2);val pcm=ShortArray(480);val out=ShortArray(480)
        q.offer(1,1,pcm);q.offer(2,1,pcm);q.poll(out);assertFalse(q.offer(1,1,pcm));assertFalse(q.offer(2,1,pcm));assertEquals(1,q.poll(out));assertEquals(0,q.poll(out))
    }
    @Test fun restartDiscardsOldFormatAndQueuedSamples() {
        val q=ReceiverJitterBuffer(8,2);val out=ShortArray(480)
        q.offer(1,1,ShortArray(480){7});q.offer(2,1,ShortArray(480){7});q.clear()
        q.offer(100,2,ShortArray(480){3});q.offer(101,2,ShortArray(480){3});assertEquals(2,q.poll(out));assertEquals(3.toShort(),out[0]);assertEquals(5,q.queuedMs())
    }
    @Test fun emptyBufferWaitIsBoundedAndDoesNotGenerateSilenceForever() {
        val q=ReceiverJitterBuffer(8,2);val out=ShortArray(480){7};repeat(1000){assertEquals(0,q.poll(out))};assertEquals(0L,q.concealed)
        val start=System.nanoTime();q.awaitData(20);assertTrue((System.nanoTime()-start)>10_000_000)
    }
    @Test fun malformedPacketsNeverTouchPcmOrDecoderMetadata() {
        val encoder=PacketEncoder(2);val valid=encoder.encode(ShortArray(480){123},false,99,Long.MIN_VALUE,token).clone()
        val d=PacketDecoder();val out=ShortArray(480){-77};assertTrue(d.decode(valid,984,token,out));assertEquals(Long.MIN_VALUE,d.timestampNS)
        val tooLong=valid.copyOf(985);assertFalse(d.decode(tooLong,985,token,out));val bad=valid.clone();bad[3]=51;assertFalse(d.decode(bad,984,token,out))
        assertFalse(d.decode(valid,984,"0123abce",out));assertEquals(99L,d.sequence);assertEquals(123.toShort(),out[0])
    }
    @Test fun pcmSignedAndEndianExtremesSurviveWire() {
        val samples=ShortArray(480);samples[0]=Short.MIN_VALUE;samples[1]=Short.MAX_VALUE;samples[2]=-1;samples[3]=256
        for(c in 1..2){val bytes=PacketEncoder(c).encode(samples,false,0xffffffff,0,token);val out=ShortArray(480);val d=PacketDecoder();assertTrue(d.decode(bytes,bytes.size,token,out));assertArrayEquals(samples,out);assertEquals(c,d.channels)}
    }
    @Test fun staleApprovalCannotRemoveTheNewPendingRequest() {
        var now=0L;val m=ReceiverControlModel("receiver",{now});m.start();val old=m.requestPair("a","A","ip")!!;now=40000
        val fresh=m.requestPair("b","B","other")!!;assertNull(m.approve(old.id));assertEquals(fresh.id,m.pending!!.id);assertFalse(m.deny(old.id));assertEquals(fresh.id,m.pending!!.id)
        assertNotNull(m.approve(fresh.id));assertNull(m.pending)
    }
    @Test fun invalidProofAndUnknownInspectDoNotRotateTokenOrRequestConsent() {
        val m=ReceiverControlModel("receiver",{0});m.start();val before=m.token;assertFalse(m.authenticated("unknown","nonce","0".repeat(64)));assertEquals(before,m.token);assertNull(m.pending);assertTrue(m.clients().isEmpty())
    }
    @Test fun busySessionUsesAudioLeaseAfterControlSocketHasGone() {
        var now=0L;val m=ReceiverControlModel("receiver",{now});m.start();val a=m.requestPair("a","A","ipA")!!;m.approve(a.id)
        now=44000;m.audioActivity("ipA");now=45001;assertTrue(m.busy("b"));assertNull(m.requestPair("b","B","ipB"))
        now=56001;assertFalse(m.busy("b"));assertNotNull(m.requestPair("b","B","ipB"))
    }
    @Test fun trustedKeySurvivesReconnectAndRejectsPairOverwrite() {
        val m=ReceiverControlModel("receiver",{0});m.start();val p=m.requestPair("a","A","ip")!!;val key=m.approve(p.id)!!.secret!!
        val before=m.token;assertNotNull(m.resume("a","ip"));assertNotEquals(before,m.token);assertNull(m.requestPair("a","A","ip"));assertTrue(m.authenticated("a","nonce",PairingProof.sign(key,"a","receiver","nonce")))
        assertFalse(m.authenticated("a","wrong",PairingProof.sign(key,"a","receiver","nonce")))
    }
    @Test fun routeLossRequiresFreshAudioAndDoesNotAuthorizeSpeaker() {
        val p=ReceiverPlaybackPolicy();p.focusGained();assertTrue(p.canPlay(true));assertFalse(p.canPlay(false));assertFalse(p.speaker)
        p.speaker=true;assertTrue(p.canPlay(false));p.newSession();assertFalse(p.canPlay(false))
    }
    @Test fun transientFocusDoesNotOverrideUserPauseOrPermanentLoss() {
        val p=ReceiverPlaybackPolicy();p.focusGained();p.pause();p.focusLost(true);p.focusGained();assertFalse(p.canPlay(true));p.resume();assertTrue(p.canPlay(true))
        p.focusLost(false);p.focusGained();assertFalse(p.canPlay(true));p.resume();assertTrue(p.canPlay(true))
    }
    @Test fun duplicateServiceStartCannotOwnAnExistingLease() {
        val p=RoleLease();val e=p.select(AudioRole.RECEIVE);assertTrue(p.claim(AudioRole.RECEIVE,e));assertFalse(p.claim(AudioRole.RECEIVE,e));p.release(AudioRole.SEND);assertFalse(p.claim(AudioRole.SEND,e));p.release(AudioRole.RECEIVE);assertTrue(p.claim(AudioRole.RECEIVE,e))
    }
    @Test fun authenticatedReconnectPreservesManualPauseAndPermanentFocusLoss() {
        val p=ReceiverPlaybackPolicy();p.focusGained();p.pause();p.speaker=true;p.senderSession()
        assertFalse(p.speaker);assertFalse(p.canPlay(true));assertFalse(p.wantsPlayback())
        p.resume();p.focusLost(false);p.senderSession();p.focusGained();assertFalse(p.canPlay(true));assertFalse(p.wantsPlayback())
    }
    @Test fun focusIsRequestedOnlyWithFreshAudioAndAnAllowedOutput() {
        val p=ReceiverPlaybackPolicy();assertFalse(p.shouldRequestFocus(false,true));assertFalse(p.shouldRequestFocus(true,false));assertTrue(p.shouldRequestFocus(true,true))
        p.pause();assertFalse(p.shouldRequestFocus(true,true));p.resume();p.focusLost(false);assertFalse(p.shouldRequestFocus(true,true))
    }

    @Test fun transientFocusLossWaitsForSystemGainOrExplicitResume() {
        val p=ReceiverPlaybackPolicy();p.focusGained();p.focusLost(true)
        assertFalse(p.shouldRequestFocus(true,true))
        repeat(10){p.senderSession();assertFalse(p.shouldRequestFocus(true,true))}
        p.focusGained();assertTrue(p.canPlay(true));assertFalse(p.shouldRequestFocus(true,true))
        p.focusLost(true);p.resume();assertTrue(p.shouldRequestFocus(true,true))
    }

}
