import AppIntents
import Foundation

@available(iOS 17.0, *)
struct SetReceiverEnabledIntent: AudioPlaybackIntent, SetValueIntent {
    static var title: LocalizedStringResource = "设置 HyperAudio 接收"
    @Parameter(title: "接收") var value: Bool
    @MainActor func perform() async throws -> some IntentResult {
        #if HYPERAUDIO_APP
        let receiver = Receiver.shared
        if value {
            if !receiver.running || receiver.paused { receiver.resume() }
            if !receiver.running || receiver.paused {
                throw ReceiverIntentError.unavailable(receiver.status)
            }
        } else {
            receiver.stop()
        }
        #endif
        return .result()
    }
}

@available(iOS 17.0, *)
struct ToggleReceiverIntent: AudioPlaybackIntent {
    static var title: LocalizedStringResource = "切换 HyperAudio 接收"
    static var description = IntentDescription("接收中停止，已停止或暂停时开始或恢复。")
    @MainActor func perform() async throws -> some IntentResult {
        #if HYPERAUDIO_APP
        let receiver = Receiver.shared
        if receiver.running && !receiver.paused {
            receiver.stop()
        } else {
            receiver.resume()
            if !receiver.running || receiver.paused {
                throw ReceiverIntentError.unavailable(receiver.status)
            }
        }
        #endif
        return .result()
    }
}

// AudioPlaybackIntent executes in the app process, including when invoked by a control.
// The extension only provides metadata; it never creates its own audio receiver.
@available(iOS 17.0, *)
struct StartReceiverIntent: AudioPlaybackIntent {
    static var title: LocalizedStringResource = "开始 HyperAudio 接收"
    static var description = IntentDescription("连接蓝牙媒体耳机后开始或恢复接收安卓音频。首次配对需打开 App 确认。")
    @MainActor func perform() async throws -> some IntentResult {
        #if HYPERAUDIO_APP
        Receiver.shared.resume()
        if !Receiver.shared.running || Receiver.shared.paused {
            throw ReceiverIntentError.unavailable(Receiver.shared.status)
        }
        #endif
        return .result()
    }
}

private enum ReceiverIntentError: LocalizedError {
    case unavailable(String)
    var errorDescription: String? {
        switch self { case .unavailable(let message): return message }
    }
}

@available(iOS 17.0, *)
struct StopReceiverIntent: AudioPlaybackIntent {
    static var title: LocalizedStringResource = "停止 HyperAudio 接收"
    @MainActor func perform() async throws -> some IntentResult {
        #if HYPERAUDIO_APP
        Receiver.shared.stop()
        #endif
        return .result()
    }
}

@available(iOS 17.0, *)
struct CheckReceiverIntent: AudioPlaybackIntent {
    static var title: LocalizedStringResource = "HyperAudio 一键自检"
    @MainActor func perform() async throws -> some IntentResult & ProvidesDialog {
        #if HYPERAUDIO_APP
        Receiver.shared.runDiagnostics()
        return .result(dialog: IntentDialog(stringLiteral: Receiver.shared.diagnostics))
        #else
        return .result(dialog: "请打开 HyperAudio 查看自检。")
        #endif
    }
}

#if HYPERAUDIO_APP
@available(iOS 17.0, *)
struct ReceiverShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: StartReceiverIntent(), phrases: ["开始 \(.applicationName) 接收"],
                    shortTitle: "开始接收", systemImageName: "earbuds")
        AppShortcut(intent: StopReceiverIntent(), phrases: ["停止 \(.applicationName) 接收"],
                    shortTitle: "停止接收", systemImageName: "stop.fill")
        AppShortcut(intent: CheckReceiverIntent(), phrases: ["检查 \(.applicationName)"],
                    shortTitle: "一键自检", systemImageName: "checkmark.circle")
    }
}
#endif
