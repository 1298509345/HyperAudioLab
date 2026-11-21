package lab.hyperaudio

import java.io.Closeable

/** Main-thread controller for a non-reference-counted, timed Android wake lock. */
class CaptureWakeLease(private val acquire: (Long) -> Unit, private val release: () -> Unit) : Closeable {
    private var renewAt: Long? = null
    private var closed = false

    fun refresh(now: Long) {
        if (closed) return
        val deadline = renewAt
        if (deadline == null || now >= deadline) {
            acquire(300_000L)
            renewAt = now + 240_000L
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        if (renewAt != null) release()
    }
}
