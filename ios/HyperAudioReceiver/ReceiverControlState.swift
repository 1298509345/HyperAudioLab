import Foundation
import notify

/// A system-owned, in-memory state value. Readers never keep a registration alive.
struct ReceiverControlChannel {
    static let kind = "lab.hyperaudio.receiver.start"
    static var app: ReceiverControlChannel { forBundle(.main) }
    static func forBundle(_ bundle: Bundle) -> ReceiverControlChannel {
        let identifier = bundle.bundleIdentifier ?? "lab.hyperaudio.receiver"
        let host: String
        if bundle.bundleURL.pathExtension == "appex" {
            let appURL = bundle.bundleURL.deletingLastPathComponent().deletingLastPathComponent()
            let legacyHost = identifier.hasSuffix(".controls") ? String(identifier.dropLast(".controls".count)) : identifier
            host = Bundle(url: appURL)?.bundleIdentifier ?? legacyHost
        } else {
            host = identifier
        }
        return ReceiverControlChannel(namespace: host + ".control.state.v1")
    }
    let namespace: String
    var stateName: String { namespace + ".value" }

    func currentValue() async throws -> Bool {
        var token: Int32 = -1
        let registered = notify_register_check(stateName, &token)
        guard registered == NOTIFY_STATUS_OK else { throw ReceiverControlStateError.system(registered) }
        defer { notify_cancel(token) }
        var value: UInt64 = 0
        let read = notify_get_state(token, &value)
        guard read == NOTIFY_STATUS_OK else { throw ReceiverControlStateError.system(read) }
        guard value <= 1 else { throw ReceiverControlStateError.invalidValue }
        return value == 1
    }
}

final class ReceiverControlStatePublisher {
    private var token: Int32 = -1
    var isRegistered: Bool { token >= 0 }
    init(channel: ReceiverControlChannel = .app) {
        var registration: Int32 = -1
        if notify_register_check(channel.stateName, &registration) == NOTIFY_STATUS_OK {
            token = registration
            publish(false)
        }
    }
    @discardableResult func publish(_ enabled: Bool) -> Bool {
        guard token >= 0 else { return false }
        return notify_set_state(token, enabled ? 1 : 0) == NOTIFY_STATUS_OK
    }
    deinit { if token >= 0 { notify_cancel(token) } }
}

private enum ReceiverControlStateError: LocalizedError {
    case system(UInt32)
    case invalidValue
    var errorDescription: String? { "暂时无法读取接收状态，请打开 HyperAudio 检查。" }
}
