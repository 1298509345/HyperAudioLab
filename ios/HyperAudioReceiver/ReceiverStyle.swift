import SwiftUI

enum HyperStyle {
    static let accent=Color(red:0.33,green:0.39,blue:0.82)
    static let background=Color(uiColor:.systemGroupedBackground)
}
struct ReceiverCard<Content:View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment:.leading,spacing:16) { content }
            .frame(maxWidth:.infinity,alignment:.leading).padding(20)
            .background(Color(uiColor:.secondarySystemGroupedBackground),in:RoundedRectangle(cornerRadius:24))
    }
}
struct SleepSettingsView: View {
    @ObservedObject private var receiver=Receiver.shared
    var body: some View {
        Form {
            Section {
                Picker("停止接收",selection:Binding(get:{receiver.sleepMinutes},set:{receiver.setSleepMinutes($0)})) {
                    Text("不定时").tag(0)
                    ForEach([15,30,60,120],id:\.self) { Text("\($0) 分钟后").tag($0) }
                }.pickerStyle(.inline)
            } footer: { Text("只停止 iPhone 接收，不控制 Android 音源。未开始时，设置用于下一次接收。") }
            if let remaining=receiver.sleepRemaining {
                Section { Label("还剩 \((remaining+59)/60) 分钟",systemImage:"moon.zzz") }
            }
        }.navigationTitle("睡眠定时")
    }
}
struct ReceiverIntroductionView: View {
    let finish: () -> Void
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment:.leading,spacing:22) {
                    Image(systemName:"waveform").font(.system(size:44)).foregroundStyle(HyperStyle.accent).accessibilityHidden(true)
                    Text("手机照常用，\n声音随你听。").font(.largeTitle.bold())
                    Text("将 Android 的媒体音频送到 iPhone，再由你选择的蓝牙耳机播放。").foregroundStyle(.secondary)
                    step("1",title:"准备耳机和网络",text:"两台手机连同一 Wi-Fi 或热点，在 iPhone 选择蓝牙媒体耳机并开始接收。")
                    step("2",title:"在 Android 连接",text:"点击连接，首次在 iPhone 允许配对；Android 跨 App 播放请选择共享整个屏幕。App 只采集允许的媒体音频，不创建视频流或读取麦克风。")
                    step("3",title:"锁屏也能听",text:"开始播放后可锁屏。设置中有后台与系统快捷入口指引；耳机断开会暂停输出。")
                    Text("仅在可信局域网使用。初次配对和音频未加密；新一轮 Android 采集仍需系统授权。")
                        .font(.footnote).foregroundStyle(.secondary)
                    Button("开始使用",action:finish).buttonStyle(.borderedProminent).controlSize(.large).frame(maxWidth:.infinity)
                }.padding(24)
            }.background(HyperStyle.background)
                .toolbar { ToolbarItem(placement:.navigationBarTrailing) { Button("跳过",action:finish) } }
        }.tint(HyperStyle.accent)
    }
    private func step(_ number:String,title:String,text:String) -> some View {
        HStack(alignment:.top,spacing:14) {
            Text(number).font(.headline).foregroundStyle(HyperStyle.accent)
                .frame(width:30,height:30).background(HyperStyle.accent.opacity(0.1),in:Circle())
            VStack(alignment:.leading,spacing:6) {
                Text(title).font(.headline)
                Text(text).font(.subheadline).foregroundStyle(.secondary).fixedSize(horizontal:false,vertical:true)
            }
        }
    }
}
