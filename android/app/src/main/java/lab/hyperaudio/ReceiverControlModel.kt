package lab.hyperaudio

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

data class ReceiverPairRequest(val id:String,val clientId:String,val name:String,val expiresAtMs:Long)
data class ReceiverClient(val id:String,val name:String)
internal data class ReceiverTrust(val id:String,val name:String,val secret:String)
data class ReceiverReady(val token:String,val secret:String?=null)

/** All admission decisions share this lock. Socket closure is deliberately unrelated to audio ownership. */
class ReceiverControlModel(val receiverId:String,private val clock:()->Long) {
    private val random=SecureRandom()
    private val trust=linkedMapOf<String,ReceiverTrust>()
    private var running=false
    private var pendingSource=""
    @Volatile var pending:ReceiverPairRequest?=null;private set
    @Volatile var token="";private set
    @Volatile var activeClient:String?=null;private set
    private var source:String?=null
    private var leaseUntil=0L
    var generation=0L;private set
    private fun hex(count:Int)=ByteArray(count).also{random.nextBytes(it)}.joinToString(""){"%02x".format(it.toInt() and 255)}
    @Synchronized fun start(){ stop();running=true;token=hex(4);generation++ }
    @Synchronized fun stop(){ running=false;pending=null;activeClient=null;source=null;token="";leaseUntil=0;generation++ }
    @Synchronized fun expire(){ if(pending?.expiresAtMs?.let{clock()>=it}==true)pending=null }
    @Synchronized fun requestPair(client:String,name:String,address:String):ReceiverPairRequest? {
        expire();if(!running || pending!=null || trust.containsKey(client) || busy(client))return null
        return ReceiverPairRequest(UUID.randomUUID().toString(),client,name.filter{it.code>=32 && it.code!=127}.take(48).ifBlank{"发端"},clock()+40000).also{pending=it;pendingSource=address}
    }
    @Synchronized fun approve(id:String):ReceiverReady? {
        expire();val p=pending ?: return null
        if(!running || p.id!=id || busy(p.clientId) || trust.containsKey(p.clientId))return null
        pending=null;val key=hex(32);trust[p.clientId]=ReceiverTrust(p.clientId,p.name,key)
        return activate(p.clientId,pendingSource,key)
    }
    @Synchronized fun deny(id:String):Boolean {expire();if(pending?.id!=id)return false;pending=null;return true}
    @Synchronized fun cancel(id:String){ if(pending?.id==id)pending=null }
    @Synchronized fun busy(client:String)=source!=null && clock()<leaseUntil && activeClient!=client
    @Synchronized fun authenticated(client:String,nonce:String,proof:String):Boolean {
        val key=trust[client]?.secret ?: return false
        if(!proof.matches(Regex("[0-9a-f]{64}")))return false
        return MessageDigest.isEqual(proof.toByteArray(Charsets.US_ASCII),PairingProof.sign(key,client,receiverId,nonce).toByteArray(Charsets.US_ASCII))
    }
    @Synchronized fun resume(client:String,address:String):ReceiverReady? {
        if(!running || !trust.containsKey(client) || busy(client))return null
        return activate(client,address,null)
    }
    private fun activate(client:String,address:String,key:String?):ReceiverReady {
        activeClient=client;source=address;leaseUntil=clock()+45000;token=hex(4);generation++
        return ReceiverReady(token,key)
    }
    @Synchronized fun acceptAudio(address:String,expectedToken:String=token):Boolean {
        if(!running || token!=expectedToken)return false
        if(source!=null && source!=address && clock()<leaseUntil)return false
        if(source!=address) {source=address;activeClient=null;leaseUntil=clock()+12000;generation++}
        return true
    }
    @Synchronized internal fun sourceAddress()=source
    @Synchronized fun audioActivity(address:String){ if(source==address)leaseUntil=clock()+12000 }
    @Synchronized fun forget(client:String):Boolean {
        if(running && activeClient==client)return false
        return trust.remove(client)!=null
    }
    @Synchronized fun clients()=trust.values.map{ReceiverClient(it.id,it.name)}
    @Synchronized internal fun records()=trust.values.toList()
    @Synchronized internal fun restore(records:List<ReceiverTrust>){for(r in records)if(r.secret.matches(Regex("[0-9a-f]{64}")))trust[r.id]=r}
}
