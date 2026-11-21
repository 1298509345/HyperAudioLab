import SwiftUI
import WidgetKit

@main
struct HyperAudioControls: WidgetBundle {
    var body: some Widget {
        ReceiverControl()
    }
}

struct ReceiverControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: ReceiverControlChannel.kind, provider: ReceiverControlValueProvider()) { isOn in
            ControlWidgetToggle(isOn: isOn, action: SetReceiverEnabledIntent()) {
                Label("接收开关", systemImage: "waveform")
            } valueLabel: { enabled in
                Text(enabled ? "接收中" : "已关闭")
            }
        }.displayName("HyperAudio 接收开关")
         .description("接收时亮起，停止或暂停时熄灭；开启需要蓝牙媒体耳机。")
    }
}

struct ReceiverControlValueProvider: ControlValueProvider {
    var previewValue: Bool { false }
    func currentValue() async throws -> Bool {
        try await ReceiverControlChannel.app.currentValue()
    }
}
