package lab.hyperaudio

enum class CaptureEndReason { NONE, USER_STOPPED, SLEEP_EXPIRED, AUTHORIZATION_REVOKED, CAPTURE_FAILED }

/** A structured state projection shared by the primary card; never parses diagnostic strings. */
object CapturePresentation {
    fun connection(s: CaptureSnapshot): String = when {
        s.running -> (s.endpoint?.name ?: "接收端")+" · "+s.connection
        s.endReason==CaptureEndReason.SLEEP_EXPIRED -> "睡眠定时已到；采集已停止"
        s.endReason==CaptureEndReason.AUTHORIZATION_REVOKED -> "采集授权已撤销；请重新授权"
        s.endReason==CaptureEndReason.CAPTURE_FAILED -> "采集失败；请检查设置后重试"
        s.endReason==CaptureEndReason.USER_STOPPED -> "采集已停止"
        else -> "尚未采集"
    }
}
