package lab.hyperaudio
import org.junit.Assert.*
import org.junit.Test
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
class AsyncMetrics06Test {
    @Test fun slowDiskNeverBlocksProducerAndQueueHasBothBoundsAndDrops() {
        val root = Files.createTempDirectory("ha-slow").toFile()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val log = MetricsLog(root,"header",maxQueueLines=3,maxQueueBytes=20, sinkFactory={ file ->
            val out = file.outputStream()
            object : OutputStream() {
                override fun write(b: Int) { entered.countDown(); release.await(3,TimeUnit.SECONDS); out.write(b) }
                override fun write(b: ByteArray, off: Int, len: Int) { entered.countDown(); release.await(3,TimeUnit.SECONDS); out.write(b,off,len) }
                override fun close() = out.close()
            }
        })
        try {
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            repeat(100) { log.line("123456789") }
            assertTrue(log.queuedLines <= 3); assertTrue(log.queuedBytes <= 20)
            assertTrue(log.droppedLines >= 98)
            val start = System.nanoTime(); log.close(); assertTrue(System.nanoTime()-start < 100_000_000)
        } finally { release.countDown(); assertTrue(log.awaitStopped(3000)); root.deleteRecursively() }
    }
    @Test fun lineLimitAndUtf8ByteLimitAreIndependentlyEnforced() {
        for((lineLimit,byteLimit,row,accepted) in listOf(arrayOf(2,4096,"a",2),arrayOf(50,10,"汉",2))) {
            val root=Files.createTempDirectory("ha-bound").toFile()
            val entered=CountDownLatch(1); val release=CountDownLatch(1)
            val log=MetricsLog(root,"h",maxQueueLines=lineLimit as Int,maxQueueBytes=byteLimit as Int,sinkFactory={ file ->
                val out=file.outputStream()
                object:OutputStream() {
                    override fun write(b:Int) { out.write(b) }
                    override fun write(b:ByteArray,off:Int,len:Int) { entered.countDown(); release.await(2,TimeUnit.SECONDS); out.write(b,off,len) }
                    override fun close() = out.close()
                }
            })
            try {
                assertTrue(entered.await(2,TimeUnit.SECONDS)); repeat(5) { log.line(row as String) }
                assertEquals(accepted,log.queuedLines); assertEquals((5-(accepted as Int)).toLong(),log.droppedLines)
            } finally { log.close(); release.countDown(); assertTrue(log.awaitStopped(3000)); root.deleteRecursively() }
        }
    }
    @Test fun failedDiskOnlyDisablesLogAndClosesWriter() {
        val root=Files.createTempDirectory("ha-fail").toFile()
        var closed=false
        val log=MetricsLog(root,"header",sinkFactory={ object:OutputStream() {
            override fun write(b:Int) { throw java.io.IOException("full") }
            override fun close() { closed=true }
        } })
        log.line("hello"); assertTrue(log.awaitStopped(3000))
        assertTrue(log.failed); assertTrue(closed)
        log.line("after failure"); assertTrue(log.droppedLines>0); log.close(); root.deleteRecursively()
    }
    @Test fun writeFailureCountsEveryDiscardedDiagnosticRow() {
        val root=Files.createTempDirectory("ha-midfail").toFile()
        val gate=CountDownLatch(1); val header=CountDownLatch(1)
        val log=MetricsLog(root,"header",sinkFactory={ object:OutputStream() {
            var first=true
            override fun write(b:Int) { error("unexpected single byte") }
            override fun write(b:ByteArray,off:Int,len:Int) {
                if(first) { first=false; header.countDown(); gate.await(2,TimeUnit.SECONDS) }
                else throw java.io.IOException("disk stopped")
            }
        } })
        try {
            assertTrue(header.await(2,TimeUnit.SECONDS)); repeat(5) { log.line("row$it") }; gate.countDown()
            assertTrue(log.awaitStopped(3000)); assertTrue(log.failed); assertEquals(5L,log.droppedLines)
        } finally { gate.countDown(); log.close(); root.deleteRecursively() }
    }
    @Test fun stopDrainsBatchRotatesAndRejectsNewLines() {
        val root=Files.createTempDirectory("ha-drain").toFile()
        val log=MetricsLog(root,"header",segmentBytes=45)
        repeat(20) { log.line("$it,"+"x".repeat(30)) }; log.close()
        assertTrue(log.awaitStopped(3000)); log.line("late")
        assertEquals(1L,log.droppedLines)
        val segments=root.listFiles()!!.filter { it.name.startsWith("metrics-") }
        assertEquals(3,segments.size); assertTrue(segments.any { it.readText().contains("19,") })
        assertTrue(segments.all { it.length() <= 45 }); root.deleteRecursively()
    }
}
