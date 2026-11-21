package lab.hyperaudio

/** Decodes into caller-owned PCM storage; malformed datagrams never modify the output. */
class PacketDecoder {
    var sequence=0L; private set
    var timestampNS=0L; private set
    var channels=1; private set
    fun decode(bytes:ByteArray,length:Int,token:String,out:ShortArray):Boolean {
        if(length!=AudioWire.PACKET_BYTES || bytes.size<length || out.size<480 || token.length!=8) return false
        if(bytes[0]!=72.toByte() || bytes[1]!=65.toByte() || bytes[2]!=80.toByte() || (bytes[3]!=49.toByte() && bytes[3]!=50.toByte())) return false
        for(i in 0..7) if(bytes[16+i].toInt()!=token[i].code) return false
        var seq=0L; for(i in 4..7) seq=(seq shl 8) or (bytes[i].toLong() and 255)
        var time=0L; for(i in 8..15) time=(time shl 8) or (bytes[i].toLong() and 255)
        for(i in 0 until 480) out[i]=((bytes[24+i*2].toInt() and 255) or (bytes[25+i*2].toInt() shl 8)).toShort()
        sequence=seq; timestampNS=time; channels=if(bytes[3]==50.toByte()) 2 else 1; return true
    }
}

/** Preallocated slots, unsigned sequence arithmetic and bounded loss concealment.
 * poll returns channels, or zero while waiting for a fresh prebuffer. Never spin on an empty queue. */
class ReceiverJitterBuffer(private val capacity:Int=24,private val prebuffer:Int=3) {
    private val available=Object()
    private val pcm=Array(capacity){ShortArray(480)}
    private val seq=LongArray(capacity); private val used=BooleanArray(capacity)
    private var expected:Long?=null; private var channels=1; private var playing=false; private var gaps=0
    @Volatile var size=0; private set
    var dropped=0L; private set
    var concealed=0L; private set
    init { require(capacity>=prebuffer && prebuffer>0) }
    private fun delta(a:Long,b:Long)=((a-b+0x80000000L) and 0xffffffffL)-0x80000000L
    @Synchronized fun offer(sequence:Long,c:Int,samples:ShortArray):Boolean {
        require(c in 1..2 && samples.size>=480)
        if(expected==null || c!=channels) { clear(); expected=sequence;channels=c }
        val start=expected!!; val distance=delta(sequence,start)
        if(distance<0) {
            if(!playing && -distance<capacity)expected=sequence
            else {dropped++;return false}
        }
        if(distance>=capacity) { dropped+=size; clear();expected=sequence;channels=c }
        for(i in 0 until capacity) if(used[i] && seq[i]==sequence) return false
        var slot=used.indexOfFirst{!it}
        if(slot<0) { dropped+=size;clear();expected=sequence;channels=c;slot=0 }
        samples.copyInto(pcm[slot],0,0,480);seq[slot]=sequence;used[slot]=true;size++;synchronized(available){available.notifyAll()};return true
    }
    @Synchronized fun poll(out:ShortArray):Int {
        if(!playing) { if(size<prebuffer)return 0;playing=true }
        val next=expected ?: return 0
        val slot=(0 until capacity).firstOrNull{used[it] && seq[it]==next}
        if(slot==null) {
            // No later packet means the sender went quiet: rebuffer instead of inventing endless silence.
            if(size==0 || ++gaps>3) { clear();return 0 }
            out.fill(0);concealed++
        } else { pcm[slot].copyInto(out);used[slot]=false;size--;gaps=0 }
        expected=(next+1) and 0xffffffffL;return channels
    }
    @Synchronized fun clear() { used.fill(false);size=0;expected=null;playing=false;gaps=0 }
    fun awaitData(timeoutMs:Long=100){synchronized(available){if(size<prebuffer)available.wait(timeoutMs)}}
    @Synchronized fun queuedMs()=size*AudioWire.frameDurationMS(channels)
}

class ReceiverPlaybackPolicy {
    @Volatile var speaker=false
    @Volatile private var userPaused=false
    @Volatile private var focus=false
    @Volatile private var permanentLoss=false
    @Volatile private var transientLoss=false
    fun pause(){ userPaused=true }
    fun resume(){ userPaused=false;permanentLoss=false;transientLoss=false }
    fun focusLost(transient:Boolean){ focus=false;transientLoss=transient;if(!transient)permanentLoss=true }
    fun focusGained(){ focus=true;transientLoss=false }
    fun focusRequestFailed(){focus=false}
    fun wantsPlayback()=!userPaused && !permanentLoss
    fun shouldRequestFocus(freshAudio:Boolean,allowedOutput:Boolean)=wantsPlayback() && !focus && !transientLoss && freshAudio && allowedOutput
    fun senderSession(){speaker=false}
    fun canPlay(headphones:Boolean)=wantsPlayback() && focus && (headphones || speaker)
    fun isPaused()=userPaused || permanentLoss || transientLoss
    fun newSession(){ speaker=false;userPaused=false;permanentLoss=false;transientLoss=false;focus=false }
}

/** One phase drives the UI, remote inspect and lock-screen playback state. */
object ReceiverAudioStatus {
    fun phase(running:Boolean,paused:Boolean,lastPacketAgeMs:Long,queuedMs:Int,playing:Boolean):String = when {
        !running -> "stopped"
        paused -> "paused"
        lastPacketAgeMs<0 || lastPacketAgeMs>1500 -> "waiting"
        playing -> "playing"
        queuedMs>=0 -> "buffering"
        else -> "waiting"
    }
    fun waitingForAudio(phase:String)=phase=="waiting" || phase=="buffering"
    fun isPlaying(phase:String)=phase=="playing"
}

/** A UDP scheduling gap must not discard PCM already queued below the native startup threshold. */
class ReceiverFeedPolicy {
    fun shouldFlushEmpty(lastPacketAgeMs:Long)=lastPacketAgeMs<0 || lastPacketAgeMs>1500
}
