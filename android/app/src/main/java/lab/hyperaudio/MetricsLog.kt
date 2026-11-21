package lab.hyperaudio

import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.nio.file.Files

data class MetricsLogFinalState(val droppedLines: Long, val failed: Boolean)

/** Single background writer; audio producer never performs file I/O or waits for drainage. */
class MetricsLog(private val folder: File, private val header: String,
    private val segmentBytes: Long = 1_048_576, private val segmentCount: Int = 3,
    private val maxQueueLines: Int = 256, private val maxQueueBytes: Int = 64 * 1024,
    private val drainTimeoutMS: Long = 1500,
    private val onClosed: (MetricsLogFinalState) -> Unit = {},
    private val sinkFactory: (File) -> OutputStream = { it.outputStream().buffered() }) : Closeable {
    private val lock = Object()
    private val queue = ArrayDeque<ByteArray>()
    private var queueBytes = 0
    private var accepting = true
    private var deadline = Long.MAX_VALUE
    private val stopped = java.util.concurrent.CountDownLatch(1)
    private val dropped = java.util.concurrent.atomic.AtomicLong()
    val droppedLines get() = dropped.get()
    val queuedLines get() = synchronized(lock) { queue.size }
    val queuedBytes get() = synchronized(lock) { queueBytes }
    @Volatile var failed = false; private set
    init {
        require(segmentBytes > 0 && segmentCount in 1..3 && maxQueueLines > 0 && maxQueueBytes > 0 && drainTimeoutMS >= 0)
        Thread({ writeLoop() }, "HyperAudio-metrics").apply { isDaemon = true; start() }
    }
    fun line(value: String) {
        if (value.length >= maxQueueBytes) { dropped.incrementAndGet(); return }
        val data = (value + "\n").toByteArray(Charsets.UTF_8)
        synchronized(lock) {
            if (!accepting || failed || queue.size >= maxQueueLines || data.size > maxQueueBytes - queueBytes) {
                dropped.incrementAndGet(); return
            }
            queue.addLast(data); queueBytes += data.size; lock.notifyAll()
        }
    }
    private fun writeLoop() {
        var output: OutputStream? = null
        var index = 0
        var bytes = 0L
        var pendingRows = 0
        val intro = (header + "\n").toByteArray(Charsets.UTF_8)
        fun open() {
            output = sinkFactory(folder.resolve("metrics-$index.csv"))
            output!!.write(intro); bytes = intro.size.toLong()
        }
        try {
            folder.mkdirs()
            folder.parentFile?.let { MetricsRetention.cleanup(it,folder) }
            open()
            while (true) {
                val batch = synchronized(lock) {
                    while (queue.isEmpty() && accepting) lock.wait(500)
                    if (!accepting && (queue.isEmpty() || System.nanoTime() >= deadline)) {
                        dropped.addAndGet(queue.size.toLong()); queue.clear(); queueBytes = 0
                        return@synchronized null
                    }
                    val rows = ArrayList<ByteArray>(minOf(32,queue.size))
                    repeat(minOf(32,queue.size)) { val row=queue.removeFirst(); queueBytes-=row.size; rows.add(row) }
                    rows
                } ?: break
                pendingRows=batch.size
                for ((position,row) in batch.withIndex()) {
                    if (System.nanoTime() >= deadline) {
                        dropped.addAndGet((batch.size-position).toLong()); pendingRows-=batch.size-position; break
                    }
                    // Oversized rows are diagnostics too; preserve the hard segment boundary.
                    if (row.size + intro.size > segmentBytes) { dropped.incrementAndGet(); pendingRows--; continue }
                    if (bytes + row.size > segmentBytes) {
                        output!!.close(); output=null; index=(index+1)%segmentCount; open()
                    }
                    output!!.write(row); bytes+=row.size
                }
                if (batch.isNotEmpty()) output!!.flush()
                pendingRows=0
            }
        } catch (_: Exception) {
            failed=true
            dropped.addAndGet(pendingRows.toLong())
            synchronized(lock) {
                accepting=false; dropped.addAndGet(queue.size.toLong()); queue.clear(); queueBytes=0
            }
        } finally {
            runCatching { output?.close() }.onFailure { failed=true }
            runCatching { onClosed(MetricsLogFinalState(droppedLines,failed)) }
            stopped.countDown()
        }
    }
    override fun close() {
        synchronized(lock) {
            if (accepting) { accepting=false; deadline=System.nanoTime()+drainTimeoutMS*1_000_000; lock.notifyAll() }
        }
    }
    /** For background validation only. Never call from an Activity or capture callback. */
    fun awaitStopped(timeoutMS: Long): Boolean = stopped.await(timeoutMS,java.util.concurrent.TimeUnit.MILLISECONDS)
}

object MetricsRetention {
    private val sessionName = Regex("session-[0-9]+-[0-9]+")
    private val metricName = Regex("metrics(?:-[0-2])?\\.csv")
    /** Call only on a background worker. PCM, unrelated files, directories and symlinks are never removed. */
    fun cleanup(root: File, current: File, nowMS: Long = System.currentTimeMillis(),
                maxAgeMS: Long = 7 * 24 * 60 * 60_000L, maxBytes: Long = 20 * 1_048_576L) {
        val files = root.listFiles().orEmpty().filter {
            it.isDirectory && sessionName.matches(it.name) && !Files.isSymbolicLink(it.toPath())
        }.flatMap { dir -> dir.listFiles().orEmpty().filter {
            it.isFile && metricName.matches(it.name) && !Files.isSymbolicLink(it.toPath())
        } }
        val candidates = files.filter { it.parentFile?.canonicalFile != current.canonicalFile }.sortedBy { it.lastModified() }
        candidates.filter { nowMS - it.lastModified() > maxAgeMS }.forEach { it.delete() }
        var total = files.filter { it.exists() }.sumOf { it.length() }
        for (file in candidates) {
            if (total <= maxBytes) break
            val bytes = file.length()
            if (file.exists() && file.delete()) total-=bytes
        }
    }
}
