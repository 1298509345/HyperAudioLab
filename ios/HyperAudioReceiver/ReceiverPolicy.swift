import Foundation

struct ReceiverAutomaticPreparation {
    private var generation = 0
    private var explicitAction = false
    mutating func schedule() -> Int { generation += 1; return generation }
    mutating func handleExplicitAction() { generation += 1; explicitAction = true }
    mutating func leaveForeground() { generation += 1; explicitAction = false }
    func shouldPrepare(_ scheduled: Int, foreground: Bool) -> Bool {
        foreground && scheduled == generation && !explicitAction
    }
}

/// Pure state; all app accesses are serialized on the main queue.
struct ReceiverPolicy {
    private(set) var requested = false
    private(set) var userPaused = false
    private(set) var interrupted = false
    private(set) var needsUserResume = false
    var canAutomaticallyResume: Bool { requested && !userPaused && !interrupted && !needsUserResume }
    mutating func start() { requested = true; userPaused = false; interrupted = false; needsUserResume = false }
    mutating func stop() { requested = false; userPaused = false; interrupted = false; needsUserResume = false }
    mutating func pause() { userPaused = true }
    mutating func resume() { userPaused = false; needsUserResume = false }
    func canAttemptManualActivation(bluetooth: Bool) -> Bool {
        requested && !userPaused && (bluetooth || interrupted)
    }
    mutating func manualActivationSucceeded() { interrupted = false }
    mutating func interruptionBegan() { interrupted = true }
    mutating func interruptionEnded(shouldResume: Bool) {
        interrupted = false
        needsUserResume = !shouldResume
    }
    func canRender(bluetooth: Bool, engineRunning: Bool = true) -> Bool {
        canAutomaticallyResume && bluetooth && engineRunning
    }
}

enum ReceiverDiagnostics {
    static func report(addressAvailable: Bool, listening: Bool, bluetooth: Bool,
                       volume: Float, packetAgeMS: Int, signalPeak: Int, paused: Bool, receiverGain: Float = 1) -> String {
        var lines = [addressAvailable ? "✓ 已发现局域网地址" : "! 未发现局域网地址，请检查 Wi-Fi / 热点",
                     listening ? "✓ 接收监听已就绪" : "— 接收尚未就绪（未启动或等待网络）：收包和电平未测试",
                     bluetooth ? "✓ 当前为蓝牙媒体输出" : "! 未连接蓝牙媒体输出；请连接并选择蓝牙耳机（需支持媒体播放）",
                     volume > 0 ? "✓ 系统媒体音量非零" : "! 系统媒体音量为零，请低音量重试"]
        if receiverGain <= 0 { lines.append("! 接收音量为零，请调高 App 内接收音量") }
        if listening {
            if paused { lines.append("— 当前暂停输出，恢复后再验证声音") }
            if packetAgeMS < 0 { lines.append("— 尚未收包：请在安卓连接并播放音源") }
            else if packetAgeMS > 2000 { lines.append("! 最近音频包已超时：检查安卓采集与两端网络") }
            else {
                lines.append("✓ 最近持续收到音频包")
                lines.append(signalPeak > 0 ? "✓ 最近 PCM 非零" : "! 最近 PCM 为静音：检查音源是否在播放、是否允许采集")
            }
        }
        lines.append("软件状态不能证明人耳实际有声；请结合耳机听感确认。")
        return lines.joined(separator: "\n")
    }
}
