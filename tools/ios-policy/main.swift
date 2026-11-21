import Foundation

func check(_ condition: @autoclosure () -> Bool, _ message: String) {
    if !condition() { fatalError(message) }
}
var policy = ReceiverPolicy()
var automatic = ReceiverAutomaticPreparation()
let beforeCheck = automatic.schedule()
automatic.handleExplicitAction()
automatic.leaveForeground()
check(!automatic.shouldPrepare(beforeCheck, foreground: true), "check/background revived stale auto-start")
let foreground = automatic.schedule()
check(!automatic.shouldPrepare(foreground, foreground: false), "auto-start ran in background")
check(automatic.shouldPrepare(foreground, foreground: true), "fresh foreground was not prepared")
automatic.handleExplicitAction()
check(!automatic.shouldPrepare(foreground, foreground: true), "explicit action failed to invalidate pending start")
check(!policy.canRender(bluetooth: true), "inactive receiver rendered")
policy.start()
check(policy.canRender(bluetooth: true), "start did not allow audio")
check(!policy.canRender(bluetooth: true, engineRunning: false), "stopped audio engine rendered")
check(!policy.canRender(bluetooth: false), "speaker route was allowed")
policy.pause()
check(!policy.canRender(bluetooth: true), "manual pause was ignored")
policy.interruptionBegan(); policy.interruptionEnded(shouldResume: true)
check(!policy.canRender(bluetooth: true), "interruption overrode manual pause")
policy.resume()
check(policy.canRender(bluetooth: true), "manual resume failed")
policy.interruptionBegan()
check(!policy.canRender(bluetooth: true), "interrupted receiver rendered")
policy.stop(); policy.interruptionEnded(shouldResume: true)
check(!policy.canRender(bluetooth: true), "interruption revived manual stop")
policy.start(); policy.interruptionBegan(); policy.interruptionEnded(shouldResume: false)
check(!policy.canRender(bluetooth: true), "resumed without recommendation")
policy.resume()
check(policy.canRender(bluetooth: true), "explicit resume failed")

let idle = ReceiverDiagnostics.report(addressAvailable: true, listening: false, bluetooth: true,
    volume: 0.2, packetAgeMS: -1, signalPeak: 0, paused: false)
check(idle.contains("未测试"), "idle treated as capture failure")
let missingRoute = ReceiverDiagnostics.report(addressAvailable: true, listening: true, bluetooth: false,
    volume: 0.2, packetAgeMS: 10, signalPeak: 100, paused: false)
check(missingRoute.contains("蓝牙"), "missing route not explained")
let silence = ReceiverDiagnostics.report(addressAvailable: true, listening: true, bluetooth: true,
    volume: 0, packetAgeMS: 10, signalPeak: 0, paused: false)
check(silence.contains("音量") && silence.contains("静音"), "silence diagnosis missing")
let good = ReceiverDiagnostics.report(addressAvailable: true, listening: true, bluetooth: true,
    volume: 0.2, packetAgeMS: 10, signalPeak: 100, paused: false)
check(good.contains("不能证明"), "diagnostic claimed real audibility")
let mutedGain = ReceiverDiagnostics.report(addressAvailable: true, listening: true, bluetooth: true,
    volume: 0.2, packetAgeMS: 10, signalPeak: 100, paused: false, receiverGain: 0)
check(mutedGain.contains("接收音量为零"), "muted receiver gain was not diagnosed")
print("RECEIVER_POLICY_CHECKS_PASSED")
check(PlaybackMode.lowLatency.startupPackets(frames: 240) == 4, "stereo low latency target")
check(PlaybackMode.stable.startupPackets(frames: 480) == 8, "mono stable startup target")
check(PlaybackMode.stable.queuePackets(frames: 240) == 12, "stereo stable queue target")
check(PlaybackMode.lowLatency.maximumPackets(frames: 480) == 12, "latency bound")
var fade = GainEnvelope()
fade.ramp(to: 0.8, now: 10, duration: 0.4)
check(abs(fade.value(at: 10.2)-0.4) < 0.001, "gain did not fade in")
fade.ramp(to: 0.2, now: 10.2, duration: 0.2)
check(abs(fade.value(at: 10.2)-0.4) < 0.001, "gain change jumped")
check(abs(fade.value(at: 10.4)-0.2) < 0.001, "gain did not reach target")
fade.mute()
check(fade.value(at: 20) == 0, "reset retained sound")
check(GainEnvelope.clamp(.nan) == 0.5 && GainEnvelope.clamp(2) == 1, "unsafe stored gain")
var sleep = ReceiverSleepTimer()
let firstSession = UUID(), secondSession = UUID()
sleep.arm(minutes: 15, now: 100, session: firstSession)
check(!sleep.expired(now: 999, session: firstSession), "sleep fired early")
check(sleep.expired(now: 1000, session: firstSession), "sleep did not expire")
check(!sleep.expired(now: 2000, session: secondSession), "old sleep stopped new session")
sleep.cancel()
check(!sleep.expired(now: 2000, session: firstSession), "cancelled sleep fired")
policy.start(); policy.interruptionBegan(); policy.interruptionEnded(shouldResume: false)
check(!policy.canAutomaticallyResume, "system denial allowed automatic resume")
policy.resume()
check(policy.canAutomaticallyResume, "explicit resume could not restore session")
policy.pause()
check(!policy.canAutomaticallyResume, "manual pause was auto-resumable")
print("AUDIO_EXPERIENCE_CHECKS_PASSED")

let noDeadlines = ReceiverMaintenancePlan.next(now: 100, lastPacket: nil, streamStalled: false,
    hasPeer: false, sleepDeadline: nil, retryRecovery: false)
check(noDeadlines == nil, "quiet background has no periodic wakeup")
check(ReceiverMaintenancePlan.next(now: 100, lastPacket: 100, streamStalled: false,
    hasPeer: true, sleepDeadline: 400, retryRecovery: false) == 100.5, "stall deadline is first")
check(ReceiverMaintenancePlan.next(now: 100.5, lastPacket: 100, streamStalled: true,
    hasPeer: true, sleepDeadline: 400, retryRecovery: false) == 103, "stalled peer retains its reconnect window")
check(ReceiverMaintenancePlan.next(now: 100.5, lastPacket: 100.25, streamStalled: false,
    hasPeer: true, sleepDeadline: 400, retryRecovery: false) == 100.75, "new packets move the stall deadline")
check(ReceiverMaintenancePlan.next(now: 100, lastPacket: nil, streamStalled: false,
    hasPeer: false, sleepDeadline: 250, retryRecovery: false) == 130, "lock screen countdown changes on the minute boundary")
check(ReceiverMaintenancePlan.next(now: 190, lastPacket: nil, streamStalled: false,
    hasPeer: false, sleepDeadline: 250, retryRecovery: false) == 250, "sleep expiry survives quiet background")
check(ReceiverMaintenancePlan.next(now: 260, lastPacket: nil, streamStalled: false,
    hasPeer: false, sleepDeadline: 250, retryRecovery: false) == 250, "expired sleep deadline still requires immediate work")
check(ReceiverMaintenancePlan.next(now: 100, lastPacket: nil, streamStalled: false,
    hasPeer: false, sleepDeadline: nil, retryRecovery: true) == 101, "recovery fallback only runs while needed")
check(ReceiverMaintenancePlan.repeatingMilliseconds(audioInterval: 5, background: true) == 5 &&
      ReceiverMaintenancePlan.repeatingMilliseconds(audioInterval: 10, background: true) == 10, "background audio cadence unchanged")
check(ReceiverMaintenancePlan.repeatingMilliseconds(audioInterval: 250, background: true) == nil &&
      ReceiverMaintenancePlan.repeatingMilliseconds(audioInterval: 250, background: false) == 250, "only background idle polling is removed")
print("BACKGROUND_MAINTENANCE_CHECKS_PASSED")
