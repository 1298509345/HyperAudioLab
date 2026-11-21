package lab.hyperaudio
import org.junit.Assert.*
import org.junit.Test
class Receiver07Test {
 private val token="1234abcd"
 private fun packet(seq:Long,c:Int=1)=PacketEncoder(c).encode(ShortArray(480){(it-200).toShort()},false,seq,123456,token).clone()
 @Test fun strictDecoder() { val d=PacketDecoder();val out=ShortArray(480)
  assertTrue(d.decode(packet(0xffffffffL),984,token,out));assertEquals(0xffffffffL,d.sequence);assertEquals(123456L,d.timestampNS);assertEquals((-200).toShort(),out[0])
  assertTrue(d.decode(packet(0,2),984,token,out));assertEquals(2,d.channels)
  assertFalse(d.decode(packet(0),983,token,out));assertFalse(d.decode(packet(0),984,"eeeeeeee",out));val bad=packet(1);bad[0]=0;assertFalse(d.decode(bad,984,token,out)) }
 @Test fun jitterOrderWrapConceal() { val q=ReceiverJitterBuffer(8,2);val s=ShortArray(480){7};val out=ShortArray(480)
  assertTrue(q.offer(0xffffffffL,1,s));assertTrue(q.offer(1,1,s));assertTrue(q.offer(0,1,s));assertFalse(q.offer(0,1,s))
  repeat(3){assertEquals(1,q.poll(out))};repeat(5){q.poll(out)};assertEquals(0,q.poll(out))
  q.clear();q.offer(4,2,s);q.offer(6,2,s);assertEquals(2,q.poll(out));assertEquals(2,q.poll(out));assertEquals(0.toShort(),out[0]);assertEquals(1L,q.concealed) }
 @Test fun backlogBoundAndRebuffer() {val q=ReceiverJitterBuffer(6,2);val s=ShortArray(480);val out=ShortArray(480)
  repeat(100){q.offer(it.toLong(),1,s)};assertTrue(q.size<=6);repeat(20){q.poll(out)};assertEquals(0,q.poll(out));assertTrue(q.dropped>0)
  q.offer(110,1,s);assertEquals(0,q.poll(out));q.offer(111,1,s);assertEquals(1,q.poll(out)) }
 @Test fun liveConsentProofBusy() {var now=0L;val r="22222222-2222-2222-2222-222222222222";val c="11111111-1111-1111-1111-111111111111";val m=ReceiverControlModel(r,{now});m.start()
  val p=m.requestPair(c,"Phone","ip")!!;assertNull(m.requestPair("b","Other","other"));now=40001;assertNull(m.approve(p.id))
  val live=m.requestPair(c,"Phone","ip")!!;val ready=m.approve(live.id)!!;assertEquals(64,ready.secret!!.length);assertNull(m.requestPair(c,"Phone","ip"))
  val n="n".repeat(64);assertFalse(m.authenticated(c,n,"0".repeat(64)));assertTrue(m.authenticated(c,n,PairingProof.sign(ready.secret!!,c,r,n)))
  val t=m.token;assertNull(m.resume("other","other"));assertEquals(t,m.token);m.audioActivity("ip");now+=1000;assertNull(m.resume("other","other"));assertFalse(m.forget(c));m.stop();assertTrue(m.forget(c));assertNull(m.approve(live.id)) }
 @Test fun denyStopAndSource() {val m=ReceiverControlModel("r",{0});m.start();val p=m.requestPair("a","A","ip")!!;assertTrue(m.deny(p.id));assertNull(m.approve(p.id));assertTrue(m.acceptAudio("ip"));assertFalse(m.acceptAudio("other"));m.stop();assertFalse(m.acceptAudio("ip")) }
 @Test fun playbackPolicy() {val p=ReceiverPlaybackPolicy();assertFalse(p.canPlay(false));p.focusGained();assertTrue(p.canPlay(true));p.focusLost(true);assertFalse(p.canPlay(true));p.pause();p.focusGained();assertFalse(p.canPlay(true));p.resume();assertTrue(p.canPlay(true));p.focusLost(false);p.focusGained();assertFalse(p.canPlay(true));p.resume();assertFalse(p.canPlay(false));p.speaker=true;assertTrue(p.canPlay(false));p.newSession();assertFalse(p.speaker) }
 @Test fun mutualExclusionAndEpoch() {val p=RoleLease();val a=p.select(AudioRole.SEND);assertTrue(p.claim(AudioRole.SEND,a));val b=p.select(AudioRole.RECEIVE);assertFalse(p.claim(AudioRole.RECEIVE,b));p.release(AudioRole.SEND);assertTrue(p.claim(AudioRole.RECEIVE,b));assertFalse(p.claim(AudioRole.SEND,a)) }
}
