package lab.hyperaudio
import org.junit.Assert.*
import org.junit.Test
class State06Test {
    @Test fun typedFailureSurvivesStopAndNewSessionResetsIt() {
        val id=LabState.begin(ConnectionInfo("private-host","private-token",50005,"private-name"))
        LabState.end(id,"unstructured private error",CaptureError.AUTHORIZATION_REVOKED)
        assertFalse(LabState.snapshot.running)
        assertEquals(CaptureError.AUTHORIZATION_REVOKED,LabState.snapshot.errorCode)
        val next=LabState.begin(ConnectionInfo("private-host","private-token",50005,"private-name"))
        assertEquals(CaptureError.NONE,LabState.snapshot.errorCode)
        LabState.end(id,"stale",CaptureError.CAPTURE_FAILED)
        assertTrue(LabState.snapshot.running); assertEquals(CaptureError.NONE,LabState.snapshot.errorCode)
        LabState.end(next,"done")
    }
}
