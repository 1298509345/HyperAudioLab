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
    func canAttemptManualActivation(outputAllowed: Bool) -> Bool {
        requested && !userPaused && (outputAllowed || interrupted)
    }
    mutating func manualActivationSucceeded() { interrupted = false }
    mutating func interruptionBegan() { interrupted = true }
    mutating func interruptionEnded(shouldResume: Bool) {
        interrupted = false
        needsUserResume = !shouldResume
    }
    func canRender(outputAllowed: Bool, engineRunning: Bool = true) -> Bool {
        canAutomaticallyResume && outputAllowed && engineRunning
    }
}

enum ReceiverOutputMode: String, CaseIterable {
    case bluetoothOnly, followSystem

    var title: String {
        switch self {
        case .bluetoothOnly: return "仅蓝牙耳机"
        case .followSystem: return "跟随系统输出"
        }
    }
}

enum ReceiverAudioRoute: String {
    case none, bluetoothA2DP, bluetoothLE, speaker, other

    var isBluetooth: Bool { self == .bluetoothA2DP || self == .bluetoothLE }
    var isExternal: Bool { self != .none && self != .speaker }
}

struct ReceiverOutputPolicy: Equatable {
    var mode: ReceiverOutputMode
    private(set) var route: ReceiverAudioRoute = .none
    private(set) var speakerNeedsConsent: Bool

    init(mode: ReceiverOutputMode = .bluetoothOnly, speakerNeedsConsent: Bool = false) {
        self.mode = mode
        self.speakerNeedsConsent = speakerNeedsConsent
    }

    var needsSpeakerConfirmation: Bool {
        mode == .followSystem && route == .speaker && speakerNeedsConsent
    }

    mutating func updateRoute(_ route: ReceiverAudioRoute, previousRoute: ReceiverAudioRoute? = nil) {
        self.route = route
        // An empty route between the headset and speaker must not clear the guard.
        if route.isExternal || previousRoute?.isExternal == true { speakerNeedsConsent = true }
    }

    func canPlay(confirmingSpeaker: Bool = false) -> Bool {
        if mode == .bluetoothOnly { return route.isBluetooth }
        return route != .none && (!needsSpeakerConfirmation || confirmingSpeaker)
    }

    mutating func confirmSpeaker() {
        if route == .speaker { speakerNeedsConsent = false }
    }
}

enum ReceiverDiagnostics {
    static func report(addressAvailable: Bool, listening: Bool, output: ReceiverOutputPolicy,
                       volume: Float, packetAgeMS: Int, signalPeak: Int, paused: Bool, receiverGain: Float = 1) -> String {
        var lines = [addressAvailable ? "✓ 已发现局域网地址" : "! 未发现局域网地址，请检查 Wi-Fi / 热点",
                     listening ? "✓ 接收监听已就绪" : "— 接收尚未就绪（未启动或等待网络）：收包和电平未测试",
                     outputLine(output),
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
        lines.append("软件状态不能证明人耳实际有声；请试听确认。")
        return lines.joined(separator: "\n")
    }

    private static func outputLine(_ output: ReceiverOutputPolicy) -> String {
        if output.needsSpeakerConfirmation { return "— 已暂停外放；确认使用 iPhone 扬声器后点恢复" }
        if !output.canPlay() {
            return output.mode == .bluetoothOnly ? "! 未连接蓝牙媒体输出；请连接并选择蓝牙耳机" : "! 当前没有可用的系统音频输出"
        }
        if output.route.isBluetooth { return "✓ 当前为蓝牙媒体输出" }
        return output.route == .speaker ? "✓ 当前为 iPhone 扬声器" : "✓ 当前系统音频输出可用"
    }
}
