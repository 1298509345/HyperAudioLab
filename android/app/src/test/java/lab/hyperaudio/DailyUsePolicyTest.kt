package lab.hyperaudio

import org.junit.Assert.*
import org.junit.Test

class DailyUsePolicyTest {
    @Test fun retryIsBoundedAndResetsAfterSuccessfulRecovery() {
        val retry = RecoveryBackoff()
        assertEquals(listOf(0L, 1_000L, 2_000L, 4_000L, 8_000L, 15_000L), (0..5).map { retry.nextDelay() })
        assertNull(retry.nextDelay())
        retry.reset()
        assertEquals(0L, retry.nextDelay())
    }
    @Test fun pausedReceiverNeverTriggersNoGrowthRecovery() {
        val health = ReceiverHealth()
        assertFalse(health.observe(100, 0, true, false))
        assertFalse(health.observe(100, 120_000, true, false))
        assertFalse(health.observe(100, 121_000, false, false))
        assertFalse(health.observe(101, 122_000, false, false))
    }
    @Test fun pauseTransitionsAndPausedFailuresNeverTriggerRecovery() {
        val health = ReceiverHealth()
        assertFalse(health.observe(10,0,false,true))
        health.resetObservation()
        assertFalse(health.observe(10,120_000,false,true))
        repeat(10) { assertFalse(health.failure(paused=true)) }
        assertFalse(health.failure())
    }
    @Test fun authenticatedNoGrowthOnlyTriggersAfterGracePeriod() {
        val health = ReceiverHealth()
        assertFalse(health.observe(100, 0, false, true))
        assertFalse(health.observe(100, 29_999, false, true))
        assertTrue(health.observe(100, 30_000, false, true))
        assertFalse(health.observe(101, 31_000, false, true))
    }
    @Test fun threeInspectionFailuresTriggerRecoveryButUnsupportedStopsPolling() {
        val health = ReceiverHealth()
        assertFalse(health.failure())
        assertFalse(health.failure())
        assertTrue(health.failure())
        health.unsupported()
        repeat(20) { assertFalse(health.failure()) }
        assertFalse(health.observe(0, 100_000, false, true))
    }
    @Test fun oldWorkerCannotPublishOrClearNewSession() {
        val owner = SessionOwnership()
        val old = owner.begin()
        val current = owner.begin()
        assertFalse(owner.owns(old))
        assertFalse(owner.end(old))
        assertTrue(owner.owns(current))
        assertTrue(owner.end(current))
        assertFalse(owner.owns(current))
    }
    @Test fun pauseConsumesInputButNeverSendsAudibleSamples() {
        val samples = shortArrayOf(1000, -2000, 0)
        assertArrayEquals(shortArrayOf(0, 0, 0), ForwardingPolicy.payload(samples, true))
        assertArrayEquals(shortArrayOf(1000, -2000, 0), ForwardingPolicy.payload(samples, false))
        assertArrayEquals(shortArrayOf(1000, -2000, 0), samples)
    }
    @Test fun idleAndPausedDiagnosticsDoNotClaimAudioFaultOrAudibility() {
        assertEquals("未测试：尚未采集", DiagnosticPolicy.capture(false, false, 0, -120.0))
        assertEquals("已暂停发送；采集授权保留", DiagnosticPolicy.capture(true, true, 100, -10.0))
        assertTrue(DiagnosticPolicy.capture(true, false, 100, -120.0).contains("当前静音"))
        assertTrue(DiagnosticPolicy.capture(true, false, 100, -10.0).contains("有电平"))
    }
    @Test fun staleMetricsAreReportedWithoutClaimingCurrentCaptureHealth() {
        assertTrue(DiagnosticPolicy.capture(true,false,100,-10.0,6_000).contains("已过期"))
        assertEquals("采集中：等待首包",DiagnosticPolicy.capture(true,false,0,-10.0,6_000))
    }
    @Test fun discoveryHintBindsTheFullStoredIdentityWithoutGrantingTrustByName() {
        assertEquals("abcdef12-full-b", PairingPolicy.discoveryIdentity("HyperAudio-abcdef12-phone",listOf("other-full-a","abcdef12-full-b")))
        assertNull(PairingPolicy.discoveryIdentity("Friendly iPhone",listOf("abcdef12-full-b")))
        assertFalse(PairingPolicy.canResume("attacker",PairingPolicy.discoveryIdentity("HyperAudio-abcdef12-forged",listOf("abcdef12-full-b")),true))
    }
    @Test fun onlyAnExactRememberedIdentityCanUseStoredSecret() {
        assertTrue(PairingPolicy.canResume("receiver-a", "receiver-a", true))
        assertFalse(PairingPolicy.canResume("receiver-a", "receiver-b", true))
        assertFalse(PairingPolicy.canResume("receiver-a", "receiver-a", false))
        assertFalse(PairingPolicy.canResume("receiver-a", null, true))
    }
}
