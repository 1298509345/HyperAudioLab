package lab.hyperaudio

/** One recovery burst is bounded. A later explicit reconnect starts a new burst. */
class RecoveryBackoff {
    private var attempt = 0
    fun nextDelay(): Long? = longArrayOf(0, 1_000, 2_000, 4_000, 8_000, 15_000).getOrNull(attempt++)
    fun reset() { attempt = 0 }
}

/** Authenticated receive counters are meaningful only while we actually forward audio. */
class ReceiverHealth {
    private var failures = 0
    private var lastReceived: Long? = null
    private var lastGrowth = 0L
    private var supported = true
    fun observe(received: Long, now: Long, paused: Boolean, sending: Boolean): Boolean {
        failures = 0
        if (!supported) return false
        if (paused || !sending || lastReceived == null || received != lastReceived) {
            lastReceived = received; lastGrowth = now; return false
        }
        return now - lastGrowth >= 30_000
    }
    fun resetObservation() { lastReceived=null; failures=0 }
    fun failure(paused: Boolean = false): Boolean {
        if (paused) { failures=0; return false }
        return supported && ++failures >= 3
    }
    fun unsupported() { supported = false; failures = 0 }
}

/** Global monotonically increasing ownership prevents a stopped worker clearing a newer session. */
class SessionOwnership {
    private var serial = 0L
    private var active = 0L
    @Synchronized fun begin(): Long { active = ++serial; return active }
    @Synchronized fun owns(id: Long): Boolean = id != 0L && id == active
    @Synchronized fun end(id: Long): Boolean {
        if (!owns(id)) return false
        active = 0; return true
    }
}
object ForwardingPolicy {
    fun payload(samples: ShortArray, paused: Boolean): ShortArray = if (paused) ShortArray(samples.size) else samples
}
object DiagnosticPolicy {
    fun capture(running: Boolean, paused: Boolean, packets: Long, db: Double, ageMS: Long = 0): String = when {
        !running -> "未测试：尚未采集"
        paused -> "已暂停发送；采集授权保留"
        packets == 0L -> "采集中：等待首包"
        ageMS > 5000 -> "采集指标已过期；当前采集状态需检查"
        db <= -90 -> "采集中：当前静音；请检查音源是否播放且允许捕获"
        else -> "采集中：有电平（不代表耳机实际有声）"
    }
}
object PairingPolicy {
    fun discoveryIdentity(name: String, identities: List<String>): String? =
        identities.firstOrNull { name.startsWith("HyperAudio-${it.take(8)}") }
    fun canResume(actual: String, expected: String?, hasSecret: Boolean): Boolean = hasSecret && actual == expected
}
