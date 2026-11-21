package lab.hyperaudio

data class CaptureSnapshot(
    val running: Boolean = false, val paused: Boolean = false, val sessionID: Long = 0,
    val sleepRemainingMS: Long? = null, val channels: Int = 1, val mode: Int = 0,
    val errorCode: CaptureError = CaptureError.NONE, val endReason: CaptureEndReason = CaptureEndReason.NONE,
    val text: String = "未开始", val connection: String = "未连接",
    val endpoint: ReceiverEndpoint? = null, val packets: Long = 0, val sent: Long = 0,
    val db: Double = -120.0, val elapsedMS: Long = 0, val metricAt: Long = 0,
    val receiver: ReceiverStatus? = null, val inspectMessage: String = "未测试",
    val volume: Int = 0, val muted: Boolean = false, val outputTypes: String = "",
    val archiveStatus: String = "", val logDirectory: String = "",
    val loggingDropped: Long = 0, val loggingFailed: Boolean = false, val loggingComplete: Boolean = false
)
object LabState {
    private val ownership = SessionOwnership()
    @Volatile var snapshot = CaptureSnapshot(); private set
    val running get() = snapshot.running
    val text get() = snapshot.text
    @Synchronized fun begin(info: ConnectionInfo): Long {
        val id = ownership.begin()
        snapshot = CaptureSnapshot(running=true,sessionID=id,connection="正在连接",text="准备采集…",
            endpoint=ReceiverEndpoint(info.name,info.host,info.controlPort,info.receiverID))
        return id
    }
    @Synchronized fun owns(id: Long) = ownership.owns(id)
    @Synchronized fun update(id: Long, change: (CaptureSnapshot) -> CaptureSnapshot) {
        if (ownership.owns(id)) snapshot = change(snapshot)
    }
    /** Final writer diagnostics may arrive after end(), but must never touch another session. */
    @Synchronized fun completeLog(id: Long, final: MetricsLogFinalState) {
        if (snapshot.sessionID == id) snapshot = snapshot.copy(loggingDropped=final.droppedLines,
            loggingFailed=final.failed,loggingComplete=true)
    }
    @Synchronized fun end(id: Long, message: String, errorCode: CaptureError = CaptureError.NONE,
        reason: CaptureEndReason = when(errorCode) {
            CaptureError.AUTHORIZATION_REVOKED -> CaptureEndReason.AUTHORIZATION_REVOKED
            CaptureError.CAPTURE_FAILED -> CaptureEndReason.CAPTURE_FAILED
            else -> CaptureEndReason.USER_STOPPED
        }) {
        if (ownership.end(id)) snapshot = snapshot.copy(running=false,paused=false,sleepRemainingMS=null,text=message,connection=message,errorCode=errorCode,endReason=reason)
    }
}
