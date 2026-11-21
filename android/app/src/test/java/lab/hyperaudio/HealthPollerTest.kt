package lab.hyperaudio

import org.junit.Assert.*
import org.junit.Test

class HealthPollerTest {
    private class Clock {
        val pending = mutableListOf<Runnable>()
        fun schedule(task: Runnable, delay: Long) { assertEquals(10_000L, delay); pending.add(task) }
        fun cancel(task: Runnable) { pending.removeAll { it === task } }
        fun tick() { val task = pending.removeAt(0); task.run() }
    }
    @Test fun lostInspectionCallbackDuringRecoveryDoesNotStopFutureHealthChecks() {
        val clock = Clock()
        var recovering = false
        var inspected = 0
        var completed = 0
        var pendingResponse: (() -> Unit)? = null
        val poller = HealthPoller(clock::schedule, clock::cancel) {
            if (!recovering) {
                inspected++
                pendingResponse = { completed++ }
            }
        }
        poller.start()
        assertEquals(1, clock.pending.size)
        clock.tick() // An inspect is waiting on the socket.
        assertEquals(1, inspected)
        recovering = true
        pendingResponse = null // Recovery cancels that generation; its completion never arrives.
        assertEquals(1, clock.pending.size)
        clock.tick()
        assertEquals(1, inspected)
        assertEquals(1, clock.pending.size)
        recovering = false
        clock.tick()
        assertEquals(2, inspected)
        pendingResponse?.invoke()
        assertEquals(1, completed)
        assertEquals(1, clock.pending.size)
        poller.close()
    }
    @Test fun repeatedStartMaintainsOneTimerAndStopCancelsEvenAnAlreadyDequeuedTick() {
        val clock = Clock()
        var polls = 0
        val poller = HealthPoller(clock::schedule, clock::cancel) { polls++ }
        poller.start(); poller.start()
        assertEquals(1, clock.pending.size)
        val stale = clock.pending.single()
        poller.close(); poller.close()
        stale.run()
        assertEquals(0, polls)
        assertTrue(clock.pending.isEmpty())
        poller.start() // A stopped session cannot restart its health loop.
        assertTrue(clock.pending.isEmpty())
    }
}
