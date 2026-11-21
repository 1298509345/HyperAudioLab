package lab.hyperaudio
import org.junit.Assert.*
import org.junit.Test
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
class MetricsBenchmark06Test {
    @Test fun repeatableProducerMicrobenchmarkWithTwoMillisecondFlushLatency() {
        val root=Files.createTempDirectory("ha-bench").toFile()
        try {
            val rows=200; val flushes=AtomicInteger()
            fun disk(file:java.io.File):OutputStream {
                val output=file.outputStream().buffered()
                return object:OutputStream() {
                    override fun write(b:Int) = output.write(b)
                    override fun write(b:ByteArray,off:Int,len:Int) = output.write(b,off,len)
                    override fun flush() { Thread.sleep(2); flushes.incrementAndGet(); output.flush() }
                    override fun close() = output.close()
                }
            }
            val row="1000,200,200,false,-32.4,1200,0,true,2"
            val start=System.nanoTime()
            disk(root.resolve("baseline.csv")).use { out -> repeat(rows) { out.write((row+"\n").toByteArray()); out.flush() } }
            val beforeNS=System.nanoTime()-start; flushes.set(0)
            val log=MetricsLog(root.resolve("session-123-1"),"header") { file -> disk(file) }
            val enqueueStart=System.nanoTime(); repeat(rows) { log.line(row) }; val afterNS=System.nanoTime()-enqueueStart
            log.close(); assertTrue(log.awaitStopped(3000)); assertEquals(0L,log.droppedLines)
            assertTrue(root.resolve("session-123-1/metrics-0.csv").readLines().size==rows+1)
            println("ANDROID06_MICROBENCH rows=$rows injected_flush_ms=2 synchronous_producer_ns=$beforeNS async_producer_ns=$afterNS writer_flushes=${flushes.get()} drops=${log.droppedLines}")
        } finally { root.deleteRecursively() }
    }
}
