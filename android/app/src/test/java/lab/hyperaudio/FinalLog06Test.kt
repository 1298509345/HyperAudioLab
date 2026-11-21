package lab.hyperaudio

import org.junit.Assert.*
import org.junit.Test
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FinalLog06Test {
    @Test fun finalWriterFailureUpdatesStoppedSameSessionWithoutChangingEndState() {
        val root=Files.createTempDirectory("ha-final-fail").toFile()
        val header=CountDownLatch(1);val release=CountDownLatch(1)
        val id=LabState.begin(ConnectionInfo("host","12345678",50005,"phone"))
        val log=MetricsLog(root,"header",onClosed={ LabState.completeLog(id,it) },sinkFactory={ file ->
            val out=file.outputStream()
            object:OutputStream() {
                var first=true
                override fun write(b:Int)=out.write(b)
                override fun write(b:ByteArray,off:Int,len:Int) {
                    if(first) { first=false;header.countDown();release.await(2,TimeUnit.SECONDS);out.write(b,off,len) }
                    else throw java.io.IOException("drain failed")
                }
                override fun close()=out.close()
            }
        })
        try {
            assertTrue(header.await(2,TimeUnit.SECONDS));repeat(2) { log.line("row$it") }
            LabState.end(id,"timer stopped",reason=CaptureEndReason.SLEEP_EXPIRED)
            val stopped=LabState.snapshot;log.close();release.countDown()
            assertTrue(log.awaitStopped(3000))
            val final=LabState.snapshot
            assertTrue(final.loggingComplete);assertTrue(final.loggingFailed);assertEquals(2L,final.loggingDropped)
            assertFalse(final.running);assertEquals(stopped.endReason,final.endReason);assertEquals(stopped.text,final.text)
            assertEquals(stopped.sessionID,final.sessionID)
        } finally { log.close();release.countDown();root.deleteRecursively() }
    }
    @Test fun timedOutOldWriterCannotOverwriteNewSessionSnapshot() {
        val root=Files.createTempDirectory("ha-final-old").toFile()
        val header=CountDownLatch(1);val release=CountDownLatch(1);val callbacks=AtomicInteger()
        val old=LabState.begin(ConnectionInfo("host","12345678",50005,"old"))
        val log=MetricsLog(root,"header",drainTimeoutMS=0,onClosed={ callbacks.incrementAndGet();LabState.completeLog(old,it) },sinkFactory={ file ->
            val out=file.outputStream()
            object:OutputStream() {
                override fun write(b:Int)=out.write(b)
                override fun write(b:ByteArray,off:Int,len:Int) { header.countDown();release.await(2,TimeUnit.SECONDS);out.write(b,off,len) }
                override fun close()=out.close()
            }
        })
        var next=0L
        try {
            assertTrue(header.await(2,TimeUnit.SECONDS));repeat(2) { log.line("row$it") }
            LabState.end(old,"old stopped")
            next=LabState.begin(ConnectionInfo("newhost","87654321",50005,"new"))
            LabState.update(next) { it.copy(paused=true,channels=2,loggingDropped=7) }
            val current=LabState.snapshot
            log.close();release.countDown();assertTrue(log.awaitStopped(3000))
            assertEquals(1,callbacks.get());assertEquals(2L,log.droppedLines)
            assertSame(current,LabState.snapshot)
        } finally { log.close();release.countDown();if(next!=0L) LabState.end(next,"done");root.deleteRecursively() }
    }
}
