package lab.hyperaudio
import org.junit.Assert.*
import org.junit.Test
class Review06Test {
    @Test fun unnamedLegacyDevicesHaveDistinctShortLocalIdentityWithoutFullId() {
        val data=mutableMapOf<String,Any?>("key:common-prefix-111111" to "a", "key:common-prefix-222222" to "b")
        val devices=PairingCatalog(data).devices()
        assertEquals(2,devices.map { it.displayName }.toSet().size)
        assertTrue(devices.all { it.displayName.length<40 && !it.displayName.contains(it.id) })
    }
    @Test fun typedEndReasonOverridesStaleNetworkAndResetsOnNextSession() {
        val id=LabState.begin(ConnectionInfo("host","12345678",50005,"phone"))
        LabState.update(id) { it.copy(connection="正在发送",sleepRemainingMS=1000) }
        LabState.end(id,"arbitrary old message",CaptureError.AUTHORIZATION_REVOKED)
        assertEquals("采集授权已撤销；请重新授权",CapturePresentation.connection(LabState.snapshot))
        assertNull(LabState.snapshot.sleepRemainingMS)
        val next=LabState.begin(ConnectionInfo("host","12345678",50005,"phone"))
        LabState.end(next,"arbitrary timer message",reason=CaptureEndReason.SLEEP_EXPIRED)
        assertEquals("睡眠定时已到；采集已停止",CapturePresentation.connection(LabState.snapshot))
    }
    private class Transport:ConnectionTransport {
        var finds=0; var connects=0; var cancellations=0
        var found:((List<ReceiverEndpoint>)->Unit)?=null
        var progress:((String)->Unit)?=null
        var result:((Result<ConnectionInfo>)->Unit)?=null
        override fun remembered():ReceiverEndpoint?=null
        override fun hasKey(id:String)=false
        override fun find(done:(List<ReceiverEndpoint>)->Unit) { finds++;found=done }
        override fun connect(endpoint:ReceiverEndpoint,allowPair:Boolean,progress:(String)->Unit,done:(Result<ConnectionInfo>)->Unit) { connects++;this.progress=progress;result=done }
        override fun cancel() { cancellations++ }
    }
    @Test fun discoveryAndFirstPairingSurviveDetachedUiAndResultIsConsumedOnce() {
        val driver=Transport(); val flow=ConnectionCoordinator(driver)
        var oldUpdates=0; flow.attach { oldUpdates++ }
        flow.startAutomatic(); flow.detach()
        val updatesBefore=oldUpdates
        driver.found!!(listOf(ReceiverEndpoint("phone","host")))
        driver.progress!!("等待首次允许")
        assertTrue(flow.busy);assertEquals(1,driver.finds);assertEquals(1,driver.connects)
        val expected=ConnectionInfo("host","12345678",50005,"phone")
        driver.result!!(Result.success(expected))
        assertEquals(updatesBefore,oldUpdates)
        var newUpdates=0;flow.attach { newUpdates++ }
        assertTrue(newUpdates>0);assertSame(expected,flow.takeReady());assertNull(flow.takeReady())
        assertEquals(0,driver.cancellations)
    }
    @Test fun detachedChoiceAndCancelledLateResultCannotReissueAuthorization() {
        val driver=Transport();val flow=ConnectionCoordinator(driver)
        flow.startAutomatic();flow.detach()
        driver.found!!(listOf(ReceiverEndpoint("one","1"),ReceiverEndpoint("two","2")))
        assertEquals(2,flow.candidates.size)
        flow.attach {};flow.choose(1)
        val oldResult=driver.result!!;flow.cancel()
        oldResult(Result.success(ConnectionInfo("2","12345678",50005,"two")))
        assertFalse(flow.busy);assertNull(flow.takeReady())
    }
}
