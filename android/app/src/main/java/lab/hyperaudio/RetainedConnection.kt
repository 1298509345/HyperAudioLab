package lab.hyperaudio

import android.content.Context

/** No View or Activity is retained: callbacks capture only this application-context holder. */
class RetainedConnection(context: Context) {
    val auto=AutoConnect(context.applicationContext)
    val flow=ConnectionCoordinator(object:ConnectionTransport {
        override fun remembered()=auto.rememberedEndpoint()
        override fun hasKey(id:String)=auto.hasKey(id)
        override fun find(done:(List<ReceiverEndpoint>)->Unit)=auto.find(done)
        override fun connect(endpoint:ReceiverEndpoint,allowPair:Boolean,progress:(String)->Unit,done:(Result<ConnectionInfo>)->Unit)=auto.connect(endpoint,progress,done,allowPair)
        override fun cancel()=auto.cancel()
    })
    fun close() { flow.detach();auto.close() }
}
