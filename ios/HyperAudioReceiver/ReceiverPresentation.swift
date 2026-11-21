import Foundation

struct PairedDevice: Identifiable, Equatable, Codable {
    let id: String
    var name: String
    var alias: String
    var lastSeen: Date?
    var title: String { alias.isEmpty ? name : alias }
}

/// Display metadata only. Authorization continues to live in pairedClientsV1.
final class PairedDeviceStore {
    private let defaults: UserDefaults
    private let key = "pairedClientDisplayV1"
    private var records: [String: PairedDevice]
    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        records = defaults.data(forKey: key).flatMap { try? JSONDecoder().decode([String: PairedDevice].self, from: $0) } ?? [:]
    }
    func devices(trusted: Set<String>) -> [PairedDevice] {
        trusted.map { records[$0] ?? fallback($0) }.sorted {
            if $0.lastSeen != $1.lastSeen { return ($0.lastSeen ?? .distantPast) > ($1.lastSeen ?? .distantPast) }
            return $0.id < $1.id
        }
    }
    func remember(id: String, name: String, seen: Date = Date()) {
        var record = records[id] ?? fallback(id)
        let cleaned = Self.displayText(name)
        if !cleaned.isEmpty { record.name = cleaned }
        record.lastSeen = seen; records[id] = record; persist()
    }
    func rename(id: String, alias: String) {
        var record = records[id] ?? fallback(id)
        record.alias = Self.displayText(alias); records[id] = record; persist()
    }
    func remove(id: String) { records.removeValue(forKey: id); persist() }
    func removeAll() { records.removeAll(); defaults.removeObject(forKey: key) }
    private func fallback(_ id: String) -> PairedDevice {
        PairedDevice(id:id, name:"已配对设备 " + String(id.prefix(8)), alias:"", lastSeen:nil)
    }
    private func persist() {
        if let data = try? JSONEncoder().encode(records) { defaults.set(data, forKey: key) }
    }
    private static func displayText(_ value: String) -> String {
        String(value.components(separatedBy: .controlCharacters).joined().trimmingCharacters(in: .whitespacesAndNewlines).prefix(48))
    }
}

enum ReceiverPhase: String, CaseIterable {
    case idle, starting, waitingNetwork, ready, playing, userPaused, noBluetooth, systemPaused, localPriority, reconnecting, sleepEnded, failed
    var title: String {
        switch self {
        case .idle: return "准备好，随时连接"
        case .starting: return "正在准备接收"
        case .waitingNetwork: return "等待网络连接"
        case .ready: return "接收已就绪"
        case .playing: return "正在播放接收音频"
        case .userPaused: return "声音已暂停"
        case .noBluetooth: return "请选择蓝牙耳机"
        case .systemPaused: return "系统暂时中断了声音"
        case .localPriority: return "iPhone 本机音频优先"
        case .reconnecting: return "等待音频恢复"
        case .sleepEnded: return "睡眠定时已结束"
        case .failed: return "暂时无法开始"
        }
    }
    var detail: String {
        switch self {
        case .idle: return "先连接蓝牙耳机，再开始接收 Android 的声音。"
        case .starting: return "正在准备网络和音频输出，请稍候。"
        case .waitingNetwork: return "请检查 Wi-Fi 或热点连接，再点重试接收。"
        case .ready: return "在 Android 点连接并播放音源，首次配对在这里确认。"
        case .playing: return "声音跟随当前系统输出，已开始播放后可锁屏。"
        case .userPaused: return "接收会话保持，点恢复继续播放。"
        case .noBluetooth: return "在系统输出选择中连接并选择蓝牙媒体耳机。"
        case .systemPaused: return "系统允许后恢复；需要时点恢复声音。"
        case .localPriority: return "本机播放结束后尝试恢复，也可以手动恢复声音。"
        case .reconnecting: return "检查 Android 音源和两端网络，接收会话仍保留。"
        case .sleepEnded: return "本机接收已停止，Android 音源需单独暂停。"
        case .failed: return "打开一键自检查看原因，再重试接收。"
        }
    }
    var symbol: String {
        switch self {
        case .playing: return "waveform"
        case .userPaused, .systemPaused, .localPriority: return "pause.circle"
        case .noBluetooth: return "headphones"
        case .reconnecting, .waitingNetwork: return "wifi.exclamationmark"
        case .sleepEnded: return "moon.zzz"
        case .failed: return "exclamationmark.circle"
        default: return "antenna.radiowaves.left.and.right"
        }
    }
}

struct ReceiverStateInput {
    var running=false, listening=false, bluetooth=false, buffered=false
    var userPaused=false, systemBlocked=false, localAudioPause=false, stalled=false
    var endedByTimer=false, failed=false
    var networkWaiting=false
    var stopPhase: ReceiverPhase = .idle
    var phase: ReceiverPhase {
        guard running else {
            if stopPhase == .noBluetooth && bluetooth { return .idle }
            if stopPhase != .idle { return stopPhase }
            return endedByTimer ? .sleepEnded : failed ? .failed : .idle
        }
        if !bluetooth { return .noBluetooth }
        if userPaused { return .userPaused }
        if systemBlocked { return .systemPaused }
        if localAudioPause { return .localPriority }
        if !listening { return networkWaiting ? .waitingNetwork : .starting }
        if stalled { return .reconnecting }
        return buffered ? .playing : .ready
    }
}

/// No identifiers, address, device names, raw status strings or preference dictionaries.
struct ReceiverReport {
    let version: String
    let system: String
    let mode: String
    let channels: Int
    let running: Bool
    let paused: Bool
    let seconds: Int
    let received: Int
    let dropped: Int
    let concealed: Int
    let routeType: String
    let phase: ReceiverPhase
    var text: String {
        let modes: Set<String> = ["lowLatency", "stable"]
        let routes: Set<String> = ["bluetoothA2DP", "bluetoothLE", "none", "other"]
        let values: [String:Any] = [
            "schema":1, "version":String(version.prefix(32)), "system":String(system.prefix(64)),
            "mode":modes.contains(mode) ? mode : "unknown", "channels":channels == 2 ? 2 : 1,
            "running":running, "paused":paused, "seconds":max(0,seconds),
            "received":max(0,received), "dropped":max(0,dropped), "concealed":max(0,concealed),
            "routeType":routes.contains(routeType) ? routeType : "unknown", "state":phase.rawValue
        ]
        guard let data=try? JSONSerialization.data(withJSONObject: values, options:[.prettyPrinted,.sortedKeys]),
              let text=String(data:data,encoding:.utf8) else { return "{}" }
        return text
    }
}
