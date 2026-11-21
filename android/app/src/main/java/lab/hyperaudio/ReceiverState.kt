package lab.hyperaudio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ReceiverSnapshot(
    val running:Boolean=false,val paused:Boolean=false,val status:String="尚未开始接收",
    val token:String="",val addresses:String="",val pending:ReceiverPairRequest?=null,
    val clients:List<ReceiverClient> = emptyList(),val activeClient:String?=null,
    val received:Long=0,val playedFrames:Long=0,val writtenFrames:Long=0,val channels:Int=1,
    val queuedMs:Int=0,val actualOutput:String="静音 · 等待耳机",val speaker:Boolean=false,
    val bluetooth:Boolean=false,val lastPacketAgeMs:Long=-1,val dropped:Long=0,val concealed:Long=0,
    val waitingForAudio:Boolean=false,val audioPhase:String="stopped"
)
object ReceiverState {
    @Volatile var snapshot=ReceiverSnapshot();private set
    @Synchronized fun publish(value:ReceiverSnapshot){snapshot=value}
}
/** Receiver credentials use their own preference file, independent of Android's sender pairing catalog. */
internal class ReceiverStore(context:Context) {
    private val prefs=context.getSharedPreferences("receiverV1",0)
    val receiverId:String=prefs.getString("receiverId",null) ?: UUID.randomUUID().toString().also{prefs.edit().putString("receiverId",it).apply()}
    fun load():List<ReceiverTrust> = runCatching {
        val items=JSONArray(prefs.getString("clients","[]"));(0 until items.length()).map{val r=items.getJSONObject(it);ReceiverTrust(r.getString("id"),r.getString("name"),r.getString("secret"))}
    }.getOrDefault(emptyList())
    fun save(model:ReceiverControlModel){val a=JSONArray();model.records().forEach{a.put(JSONObject().put("id",it.id).put("name",it.name).put("secret",it.secret))};prefs.edit().putString("clients",a.toString()).apply()}
    fun clients()=load().map{ReceiverClient(it.id,it.name)}
    fun forget(id:String):Boolean {if(ReceiverState.snapshot.running)return false;val records=load().filter{it.id!=id};val a=JSONArray();records.forEach{a.put(JSONObject().put("id",it.id).put("name",it.name).put("secret",it.secret))};prefs.edit().putString("clients",a.toString()).apply();return true}
}
