package lab.hyperaudio

import android.content.Context

enum class AudioRole { SEND, RECEIVE }
/** Role changes invalidate any outstanding MediaProjection result before the old service exits. */
class RoleLease {
    var selected=AudioRole.SEND;private set
    var epoch=1L;private set
    private var active:AudioRole?=null
    @Synchronized fun select(role:AudioRole):Long { selected=role;return ++epoch }
    @Synchronized fun claim(role:AudioRole,expectedEpoch:Long):Boolean {
        if(selected!=role || epoch!=expectedEpoch || active!=null)return false
        active=role;return true
    }
    @Synchronized fun release(role:AudioRole) { if(active==role)active=null }
}
object AppRole {
    private val lease=RoleLease();private var loaded=false
    val epoch:Long get()=lease.epoch
    @Synchronized fun current(context:Context):AudioRole {
        if(!loaded) { loaded=true;lease.select(runCatching{AudioRole.valueOf(context.getSharedPreferences("lab",0).getString("audioRole","SEND")!!)}.getOrDefault(AudioRole.SEND)) };return lease.selected
    }
    @Synchronized fun select(context:Context,role:AudioRole):Long {
        current(context);context.getSharedPreferences("lab",0).edit().putString("audioRole",role.name).apply();return lease.select(role)
    }
    fun claim(role:AudioRole,expectedEpoch:Long)=lease.claim(role,expectedEpoch)
    fun release(role:AudioRole)=lease.release(role)
}
