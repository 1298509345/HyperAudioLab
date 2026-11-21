package lab.hyperaudio

/** A transport knows no Activity. All transitions are delivered on its existing main dispatcher. */
interface ConnectionTransport {
    fun remembered(): ReceiverEndpoint?
    fun hasKey(id: String): Boolean
    fun find(done: (List<ReceiverEndpoint>) -> Unit)
    fun connect(endpoint: ReceiverEndpoint, allowPair: Boolean, progress: (String) -> Unit, done: (Result<ConnectionInfo>) -> Unit)
    fun cancel()
}
enum class ConnectionPhase { IDLE, FINDING, CONNECTING, CHOOSING, READY, FAILED, CANCELLED }

/** Retained across configuration changes. Detached UI never cancels discovery, pairing, or results. */
class ConnectionCoordinator(private val transport: ConnectionTransport) {
    var phase=ConnectionPhase.IDLE; private set
    var message=""; private set
    var candidates: List<ReceiverEndpoint> = emptyList(); private set
    private var ready: ConnectionInfo?=null
    private var observer: (() -> Unit)?=null
    private var generation=0
    val busy get()=phase in setOf(ConnectionPhase.FINDING,ConnectionPhase.CONNECTING,ConnectionPhase.CHOOSING,ConnectionPhase.READY)
    fun attach(listener: () -> Unit) { observer=listener; listener() }
    fun detach() { observer=null }
    private fun publish(next: ConnectionPhase, text: String) { phase=next;message=text;observer?.invoke() }
    private fun begin(): Int { generation++;ready=null;candidates=emptyList();return generation }
    fun startAutomatic() {
        val run=begin(); val saved=transport.remembered()
        if(saved==null) discover(run,null) else attempt(saved,false,true,run)
    }
    fun connect(endpoint: ReceiverEndpoint) { attempt(endpoint,true,false,begin()) }
    fun discoverID(id: String) { discover(begin(),id) }
    private fun discover(run: Int, expectedID: String?) {
        publish(ConnectionPhase.FINDING,"正在发现接收端…")
        transport.find { found ->
            if(run!=generation) return@find
            val devices=if(expectedID==null) found else found.filter { it.expectedID==expectedID }
            val known=devices.firstOrNull { it.expectedID?.let(transport::hasKey)==true }
            when {
                known!=null -> attempt(known,true,false,run)
                devices.size==1 -> attempt(devices.first(),true,false,run)
                devices.isEmpty() -> publish(ConnectionPhase.FAILED,"没有找到接收端。确认 iPhone 已开始接收；也可使用手动连接。")
                else -> { candidates=devices;publish(ConnectionPhase.CHOOSING,"请选择接收端") }
            }
        }
    }
    fun choose(index: Int) {
        if(phase!=ConnectionPhase.CHOOSING) return
        val endpoint=candidates.getOrNull(index) ?: return
        candidates=emptyList();attempt(endpoint,true,false,generation)
    }
    private fun attempt(endpoint: ReceiverEndpoint, allowPair: Boolean, fallback: Boolean, run: Int) {
        publish(ConnectionPhase.CONNECTING,"正在连接 ${endpoint.name}…")
        transport.connect(endpoint,allowPair,{ text -> if(run==generation) publish(ConnectionPhase.CONNECTING,text) },{ result ->
            if(run!=generation) return@connect
            result.fold({ info -> ready=info;publish(ConnectionPhase.READY,"接收端已就绪，等待系统采集授权") },{
                if(fallback) discover(run,null) else publish(ConnectionPhase.FAILED,"连接失败：${it.message ?: "接收端不可达"}。可重试或使用手动连接。")
            })
        })
    }
    fun takeReady(): ConnectionInfo? {
        val info=ready ?: return null
        ready=null;phase=ConnectionPhase.IDLE
        return info
    }
    fun cancel() { generation++;ready=null;candidates=emptyList();transport.cancel();publish(ConnectionPhase.CANCELLED,"连接已取消") }
}
