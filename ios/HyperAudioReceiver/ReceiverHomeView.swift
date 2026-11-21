import SwiftUI

struct ReceiverHomeView: View {
    @ObservedObject private var receiver=Receiver.shared
    @ObservedObject private var control=Receiver.shared.control
    @Environment(\.dynamicTypeSize) private var textSize
    private var sourceName:String { control.devices.first { $0.id == control.activeClientID }?.title ?? "Android 发端" }
    var body: some View {
        ScrollView {
            VStack(alignment:.leading,spacing:18) {
                Text("接收 Android 的声音，用你喜欢的耳机听。")
                    .font(.subheadline).foregroundStyle(.secondary).padding(.horizontal,4)
                ReceiverCard {
                    Label(receiver.phase.title,systemImage:receiver.phase.symbol).font(.subheadline.weight(.medium))
                        .foregroundStyle(receiver.phase == .playing ? Color.green : HyperStyle.accent)
                    if textSize.isAccessibilitySize {
                        VStack(alignment:.leading,spacing:16) { source; output }
                    } else {
                        HStack(alignment:.top,spacing:12) {
                            source; Spacer(minLength:4)
                            Image(systemName:"arrow.right").foregroundStyle(.tertiary).padding(.top,23).accessibilityHidden(true)
                            Spacer(minLength:4); output
                        }.padding(.vertical,4)
                    }
                    Text(receiver.phase.detail).font(.subheadline).foregroundStyle(.secondary).fixedSize(horizontal:false,vertical:true)
                    if receiver.phase == .noBluetooth {
                        HStack {
                            Text("选择系统音频输出").font(.subheadline); Spacer()
                            RoutePicker().frame(width:52,height:44).accessibilityLabel("选择蓝牙音频输出")
                        }
                    }
                    HStack(spacing:12) {
                        Button {
                            if receiver.phase == .waitingNetwork { receiver.retryNetwork() }
                            else { receiver.running ? (receiver.paused ? receiver.resume() : receiver.pause()) : receiver.start() }
                        } label: {
                            Label(receiver.phase == .waitingNetwork ? "重试接收" : !receiver.running ? "开始接收" : receiver.paused ? "恢复声音" : "暂停声音",
                                  systemImage:!receiver.running || receiver.paused ? "play.fill" : "pause.fill")
                                .frame(maxWidth:.infinity).padding(.vertical,5)
                        }.buttonStyle(.borderedProminent).controlSize(.large)
                        if receiver.running { Button("停止") { receiver.stop() }.buttonStyle(.bordered).controlSize(.large) }
                    }
                }
                ReceiverCard {
                    HStack {
                        Text("接收音量").font(.headline); Spacer()
                        Text("\(Int(receiver.gain*100))%").font(.subheadline.monospacedDigit()).foregroundStyle(.secondary)
                    }
                    Slider(value:Binding(get:{Double(receiver.gain)},set:{receiver.setGain(Float($0))}),in:0...1)
                        .accessibilityLabel("接收音量").accessibilityValue("\(Int(receiver.gain*100))%")
                    Text("只调接收声音，不更改 iPhone 系统音量。").font(.caption).foregroundStyle(.secondary)
                    Picker("播放模式",selection:Binding(get:{receiver.playbackMode},set:{receiver.setPlaybackMode($0)})) {
                        ForEach(PlaybackMode.allCases,id:\.self) { Text($0.title).tag($0) }
                    }.pickerStyle(.segmented)
                    NavigationLink { SleepSettingsView() } label: {
                        Label(receiver.sleepRemaining.map { "睡眠定时 · 还剩 \(($0+59)/60) 分钟" } ?? (receiver.sleepMinutes > 0 ? "睡眠定时 · \(receiver.sleepMinutes) 分钟" : "睡眠定时 · 关闭"),systemImage:"moon").font(.subheadline)
                    }
                }
                HStack {
                    Text(receiver.running ? "\(receiver.channels == 2 ? "双声道" : "单声道") · 48 kHz" : "同一 Wi-Fi 或热点")
                        .font(.caption).foregroundStyle(.secondary)
                    Spacer()
                    NavigationLink { ReceiverDiagnosticsView() } label: { Label("一键自检",systemImage:"checkmark.circle").font(.subheadline) }
                }.padding(.horizontal,4)
                Text("耳机断开时暂停输出；实际输出跟随 iOS 系统路由。").font(.caption).foregroundStyle(.secondary).padding(.horizontal,4)
            }.padding(20)
        }.background(HyperStyle.background).navigationTitle("HyperAudio")
    }
    private var source:some View {
        VStack(alignment:.leading,spacing:8) {
            Image(systemName:"iphone").font(.title2).foregroundStyle(HyperStyle.accent)
                .frame(width:44,height:44).background(HyperStyle.accent.opacity(0.09),in:RoundedRectangle(cornerRadius:12)).accessibilityHidden(true)
            Text(sourceName).font(.headline).lineLimit(2)
            Text(control.activeClientID == nil ? "等待连接" : "当前音频来源").font(.caption).foregroundStyle(.secondary)
        }.frame(maxWidth:.infinity,alignment:.leading)
    }
    private var output:some View {
        VStack(alignment:.leading,spacing:8) {
            Image(systemName:"headphones").font(.title2).foregroundStyle(HyperStyle.accent)
                .frame(width:44,height:44).background(HyperStyle.accent.opacity(0.09),in:RoundedRectangle(cornerRadius:12)).accessibilityHidden(true)
            Text(receiver.bluetoothReady ? receiver.routeName : "蓝牙耳机").font(.headline).lineLimit(2)
            Text(receiver.bluetoothReady ? "当前系统输出" : "尚未选择媒体输出").font(.caption).foregroundStyle(.secondary)
        }.frame(maxWidth:.infinity,alignment:.leading)
    }
}
