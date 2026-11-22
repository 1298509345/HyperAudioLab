import SwiftUI

struct ReceiverDevicesView: View {
    @ObservedObject private var receiver=Receiver.shared
    @ObservedObject private var control=Receiver.shared.control
    @State private var editing:PairedDevice?
    @State private var removing:PairedDevice?
    var body:some View {
        List {
            Section("当前音频输出") {
                HStack {
                    Label {
                        VStack(alignment:.leading,spacing:4) {
                            Text(receiver.routeName)
                            Text(receiver.outputIssue?.title ?? "当前系统输出已就绪").font(.caption).foregroundStyle(.secondary)
                        }
                    } icon: { Image(systemName:receiver.output.route == .speaker ? "speaker.wave.2" : "headphones") }
                    Spacer(); RoutePicker().frame(width:48,height:44).accessibilityLabel("切换音频输出")
                }
            }
            Section {
                if control.devices.isEmpty {
                    VStack(alignment:.leading,spacing:8) {
                        Label("还没有已配对设备",systemImage:"iphone.gen3")
                        Text("在这里开始接收，再在 Android 点连接；首次会弹出允许提示。").font(.subheadline).foregroundStyle(.secondary)
                    }.padding(.vertical,8)
                }
                ForEach(control.devices) { device in
                    VStack(alignment:.leading,spacing:10) {
                        HStack {
                            Label(device.title,systemImage:"iphone.gen3"); Spacer()
                            if control.activeClientID == device.id { Text("当前连接").font(.caption).foregroundStyle(.green) }
                        }
                        if let date=device.lastSeen {
                            Text("最近连接：\(date.formatted(date:.abbreviated,time:.shortened))").font(.caption).foregroundStyle(.secondary)
                        }
                        HStack {
                            Button("修改别名") { editing=device }.buttonStyle(.borderless); Spacer()
                            Button("解除配对",role:.destructive) { removing=device }.buttonStyle(.borderless)
                                .disabled(receiver.running && control.activeClientID == device.id)
                        }.font(.subheadline)
                    }.padding(.vertical,6)
                }
            } header: { Text("已允许的发端") }
            footer: { Text("iPhone 等待 Android 发起连接。别名只保存在本机；当前连接的设备需先停止接收再解除配对。") }
            Section {
                Button { receiver.start(confirmSpeaker: true) } label: { Label("准备连接新设备",systemImage:"plus.circle") }.disabled(receiver.running)
                NavigationLink { ReceiverManualView() } label: { Label("手动连接信息",systemImage:"network") }
            } footer: { Text(control.status).textSelection(.enabled) }
        }.navigationTitle("设备")
            .sheet(item:$editing) { device in ReceiverAliasEditor(device:device) { control.rename(id:device.id,alias:$0) } }
            .confirmationDialog("解除与“\(removing?.title ?? "")”的本地配对？",isPresented:Binding(get:{removing != nil},set:{if !$0 { removing=nil }}),titleVisibility:.visible) {
                Button("解除配对",role:.destructive) { if let device=removing { control.forget(id:device.id) }; removing=nil }
                Button("取消",role:.cancel) { removing=nil }
            } message: { Text("不会删除其他设备或对方手机上的记录；下次连接需要重新允许。") }
    }
}
private struct ReceiverAliasEditor:View {
    let device:PairedDevice
    let save:(String)->Void
    @Environment(\.dismiss) private var dismiss
    @State private var alias=""
    var body:some View {
        NavigationStack {
            Form {
                Section { TextField("留空使用设备名称",text:$alias) }
                footer: { Text("别名最多 48 个字符，仅用于本机显示，不改变设备身份。") }
            }.navigationTitle("设备别名")
                .toolbar {
                    ToolbarItem(placement:.cancellationAction) { Button("取消") { dismiss() } }
                    ToolbarItem(placement:.confirmationAction) { Button("保存") { save(alias); dismiss() } }
                }.onAppear { alias=device.alias }
        }.tint(HyperStyle.accent)
    }
}
struct ReceiverManualView:View {
    @ObservedObject private var receiver=Receiver.shared
    var body:some View {
        Form {
            Section {
                LabeledContent("局域网 IPv4") { Text(receiver.address).textSelection(.enabled) }
                LabeledContent("音频端口",value:"50005")
                LabeledContent("本次配对码") { Text(receiver.token).monospaced().textSelection(.enabled) }
            } header: { Text("手动连接信息（备选）") }
            footer: { Text("先开始接收再使用此码，每次重启接收会换码。Android 使用能到达的 iPhone 地址，不要向其他人分享配对码。") }
            if !receiver.running { Section { Button("开始接收") { receiver.start(confirmSpeaker: true) } } }
            Section { NavigationLink { ReceiverDiagnosticsView() } label: { Label("检查网络与音频状态",systemImage:"checkmark.circle") } }
        }.navigationTitle("手动连接")
    }
}
