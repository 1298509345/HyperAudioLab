package lab.hyperaudio

/** Service-main-thread owned, based only on elapsedRealtime. No delayed callback can outlive a session. */
class SessionSleepTimer {
    private var sessionID: Long? = null
    private var deadlineMS: Long? = null
    @Synchronized fun update(id: Long, minutes: Int, nowMS: Long) {
        require(minutes in listOf(0, 15, 30, 60, 120))
        sessionID = id
        deadlineMS = if (minutes == 0) null else nowMS + minutes * 60_000L
    }
    @Synchronized fun remainingMS(id: Long, nowMS: Long): Long? =
        deadlineMS?.takeIf { sessionID == id }?.let { maxOf(0, it - nowMS) }
    @Synchronized fun expired(id: Long, nowMS: Long) = remainingMS(id, nowMS) == 0L
    @Synchronized fun cancel(id: Long) { if (sessionID == id) { sessionID=null; deadlineMS=null } }
}
