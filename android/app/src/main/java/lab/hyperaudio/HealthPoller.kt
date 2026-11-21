package lab.hyperaudio

/** Main-thread fixed cadence: an IO completion is never responsible for scheduling the next poll. */
class HealthPoller(private val schedule: (Runnable, Long) -> Unit, private val cancel: (Runnable) -> Unit,
    private val poll: () -> Unit) : AutoCloseable {
    private var started = false
    private var closed = false
    private val tick = object : Runnable {
        override fun run() {
            if (closed) return
            poll()
            if (!closed) schedule(this,10_000)
        }
    }
    fun start() { if (!started && !closed) { started=true; schedule(tick,10_000) } }
    override fun close() { closed=true; cancel(tick) }
}
