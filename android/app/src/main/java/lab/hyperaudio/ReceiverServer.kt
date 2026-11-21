package lab.hyperaudio

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/** Shared admission path for UDP input and callback-order regression tests. */
internal class ReceiverAudioAdmission(private val model:ReceiverControlModel,private val senderChanged:()->Unit) {
    fun accept(address:String,token:String,deliver:()->Unit):Boolean = synchronized(model) {
        val priorSource=model.sourceAddress()
        if(!model.acceptAudio(address,token))return@synchronized false
        // Initial manual admission preserves an output the user just explicitly selected.
        // Replacing an existing source starts a new sender session before any PCM is delivered.
        if(priorSource!=null && priorSource!=address)senderChanged()
        model.audioActivity(address);deliver();true
    }
}

/** Bounded control peers and a single reusable UDP decoder. No packet dispatches to the main thread. */
internal class ReceiverServer(
    val model:ReceiverControlModel,private val store:ReceiverStore,
    private val status:()->JSONObject,private val sessionChanged:()->Unit,
    private val audio:(PacketDecoder,ShortArray)->Unit,private val failed:(Exception)->Unit
) {
    private val admission=ReceiverAudioAdmission(model,sessionChanged)
    private val live=AtomicBoolean(false)
    private val peers=Collections.synchronizedSet(mutableSetOf<Socket>())
    private val slots=Semaphore(4)
    private val workers=Executors.newFixedThreadPool(4)
    private var tcp:ServerSocket?=null;private var udp:DatagramSocket?=null
    private var acceptThread:Thread?=null;private var udpThread:Thread?=null
    private val approval=Object();private val replies=mutableMapOf<String,ReceiverReady?>()
    fun start(){
        try {
            tcp=ServerSocket().apply{reuseAddress=true;bind(InetSocketAddress(50006))}
            udp=DatagramSocket(null).apply{reuseAddress=true;bind(InetSocketAddress(50005));soTimeout=1000}
            model.start();live.set(true)
            acceptThread=Thread({acceptLoop()},"HyperAudio-control").also{it.start()}
            udpThread=Thread({audioLoop()},"HyperAudio-UDP").also{it.start()}
        } catch(e:Exception){close();throw e}
    }
    fun approve(id:String){synchronized(approval){synchronized(model){val result=model.approve(id);if(result!=null){store.save(model);sessionChanged();replies[id]=result}};approval.notifyAll()}}
    fun deny(id:String){synchronized(approval){if(model.deny(id))replies[id]=null;approval.notifyAll()}}
    private fun acceptLoop(){try{while(live.get()){val s=tcp?.accept() ?: break;if(!slots.tryAcquire()){s.close();continue};peers.add(s);workers.execute{try{s.use{handle(it)}}catch(_:Exception){}finally{peers.remove(s);slots.release()}}}}catch(e:Exception){if(live.get())failed(e)}}
    private fun handle(s:Socket){
        s.soTimeout=5000
        val input=s.getInputStream();val output=s.getOutputStream()
        val hello=ControlProtocol.read(input)
        if(hello.optString("type")!="hello" || hello.optInt("v")!=1)return
        val client=hello.getString("clientId").lowercase();if(runCatching{UUID.fromString(client)}.isFailure)return
        val name=hello.optString("name","发端").filter{it.code>=32 && it.code!=127}.take(48)
        val nonce=UUID.randomUUID().toString()+UUID.randomUUID().toString()
        ControlProtocol.send(output,JSONObject().put("type","challenge").put("v",1).put("receiverId",model.receiverId).put("name","HyperAudio-${model.receiverId.take(8)}").put("nonce",nonce))
        val request=ControlProtocol.read(input);val type=request.optString("type")
        fun error(reason:String){ControlProtocol.send(output,JSONObject().put("type","error").put("reason",reason))}
        if(type=="inspect") {
            if(!model.authenticated(client,nonce,request.optString("proof"))){error("pair_required");return}
            val result=status();result.put("type","status").put("v",1).put("receiverId",model.receiverId).put("name","HyperAudio-${model.receiverId.take(8)}")
            ControlProtocol.send(output,result);return
        }
        val address=s.inetAddress.hostAddress ?: return
        var ready:ReceiverReady?=null
        if(type=="resume") {
            if(!model.authenticated(client,nonce,request.optString("proof"))){error("pair_required");return}
            synchronized(model){ready=model.resume(client,address);if(ready!=null)sessionChanged()};if(ready==null){error("busy");return}
        } else if(type=="pair") {
            val pending=model.requestPair(client,name,address)
            if(pending==null){error(if(model.busy(client)||model.pending!=null)"busy" else "pair_required");return}
            s.soTimeout=200
            try {
                while(live.get()) {
                    val done=synchronized(approval){
                        if(replies.containsKey(pending.id)){ready=replies.remove(pending.id);true}
                        else {model.expire();model.pending?.id!=pending.id}
                    }
                    if(done)break
                    // Detect a cancelled or malicious peer while consent is pending, without blocking approval.
                    try { if(input.read()!=-1){model.cancel(pending.id)};break }catch(_:SocketTimeoutException){}
                }
            } finally {model.cancel(pending.id);synchronized(approval){replies.remove(pending.id)}}
            if(ready==null){error("denied");return}
        } else {error("pair_required");return}
        val r=ready ?: return
        val json=JSONObject().put("type","ready").put("v",1).put("receiverId",model.receiverId).put("name","HyperAudio-${model.receiverId.take(8)}")
            .put("port",50005).put("token",r.token).put("audioFormats",org.json.JSONArray(listOf("HAP1","HAP2")))
        r.secret?.let{json.put("secret",it)};ControlProtocol.send(output,json)
    }
    private fun audioLoop(){
        val bytes=ByteArray(985);val packet=DatagramPacket(bytes,bytes.size);val decoder=PacketDecoder();val samples=ShortArray(480)
        try{while(live.get()){
            try{packet.length=bytes.size;udp?.receive(packet) ?: break}catch(_:SocketTimeoutException){continue}
            val expectedToken=model.token
            if(!decoder.decode(bytes,packet.length,expectedToken,samples))continue
            val source=packet.address.hostAddress ?: continue
            admission.accept(source,expectedToken){audio(decoder,samples)}
        }}catch(e:Exception){if(live.get())failed(e)}
    }
    fun close(){live.set(false);model.stop();runCatching{tcp?.close()};runCatching{udp?.close()};synchronized(peers){peers.toList().forEach{runCatching{it.close()}}};workers.shutdownNow();synchronized(approval){replies.clear();approval.notifyAll()}}
}
