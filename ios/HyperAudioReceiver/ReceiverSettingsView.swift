import SwiftUI
#if !HYPERAUDIO_SIDEINSTALLER
import SelfRefreshKit
#endif

struct ReceiverSettingsView:View {
    @ObservedObject private var receiver=Receiver.shared
    @AppStorage("autoPrepare") private var autoPrepare=true
    @State private var introduction=false
    var body:some View {
        Form {
            Section {
                Picker("输出方式",selection:Binding(get:{receiver.output.mode},set:{receiver.setOutputMode($0)})) {
                    ForEach(ReceiverOutputMode.allCases,id:\.self) { Text($0.title).tag($0) }
                }
                Picker("播放模式",selection:Binding(get:{receiver.playbackMode},set:{receiver.setPlaybackMode($0)})) {
                    ForEach(PlaybackMode.allCases,id:\.self) { Text($0.title).tag($0) }
                }
                Toggle("iPhone 本机音频优先",isOn:Binding(get:{receiver.prioritizeLocalAudio},set:{receiver.setLocalPriority($0)}))
                NavigationLink { SleepSettingsView() } label: { Label("睡眠定时",systemImage:"moon") }
            } header: { Text("声音") }
              footer: { Text("跟随系统输出时可用 iPhone 扬声器。耳机断开切到扬声器时会暂停，回 App 点恢复声音后才外放。") }
            Section {
                Toggle("打开 App 自动准备接收",isOn:$autoPrepare)
                NavigationLink("后台与系统快捷开关") { ReceiverBackgroundHelp() }
            } header: { Text("连接与后台") }
            #if HYPERAUDIO_SIDEINSTALLER
            Section("签名与维护") {
                LabeledContent("签名管理", value: "SideInstaller")
                Text("保留导入 SideInstaller 的原始 IPA。到期前在 SideInstaller 的「工具 → 已侧载 App」刷新，沿用首次安装时的 Apple 账号和开发 Team。")
                    .font(.subheadline).foregroundStyle(.secondary)
            }
            #else
            Section {
                SelfRefreshSection()
                NavigationLink("首次设置与续期说明") { ReceiverSigningHelpView() }
            } header: { Text("签名与维护") }
              footer: { Text("可选功能。首次需要电脑配对、开发者账户和回环 VPN；日常听音频无需开启 VPN。") }
            #endif
            Section("帮助与高级") {
                NavigationLink { ReceiverDiagnosticsView() } label: { Label("一键自检",systemImage:"checkmark.circle") }
                NavigationLink { ReceiverReportView() } label: { Label("导出诊断报告",systemImage:"square.and.arrow.up") }
                Button { introduction=true } label: { Label("使用引导",systemImage:"book") }
                NavigationLink("高级与配对清理") { ReceiverAdvancedView() }
                NavigationLink("关于与赞助") { ReceiverAboutView() }
            }
        }.navigationTitle("设置").sheet(isPresented:$introduction) { ReceiverIntroductionView { introduction=false } }
    }
}
struct ReceiverDiagnosticsView:View {
    @ObservedObject private var receiver=Receiver.shared
    var body:some View {
        List {
            Section {
                Label(receiver.phase.title,systemImage:receiver.phase.symbol)
                Text(receiver.phase.detail).font(.subheadline).foregroundStyle(.secondary)
                if receiver.phase == .noBluetooth || receiver.phase == .noOutput {
                    HStack { Text("选择系统音频输出"); Spacer(); RoutePicker().frame(width:48,height:44) }
                }
                if receiver.phase == .waitingNetwork { Button("重试接收") { receiver.retryNetwork() } }
                else if receiver.running && receiver.paused { Button("恢复声音") { receiver.resume(confirmSpeaker: true) } }
                else if !receiver.running { Button("重试接收") { receiver.start(confirmSpeaker: true) } }
            }
            Section("检查结果") {
                Text(receiver.diagnostics).font(.subheadline).textSelection(.enabled)
                Button("重新检查") { receiver.runDiagnostics() }
            }
            Section("详细状态（本机查看）") {
                Text(receiver.status).font(.footnote).textSelection(.enabled)
                Text(receiver.route).font(.footnote).textSelection(.enabled)
                LabeledContent("收到音频包",value:"\(receiver.received)")
                LabeledContent("补静音／丢弃",value:"\(receiver.concealed)／\(receiver.dropped)")
                LabeledContent("软件排队",value:"\(receiver.queuedMS) ms")
                Text("软件排队不等于端到端延迟。请试听确认当前输出。").font(.caption).foregroundStyle(.secondary)
            }
            Section { NavigationLink("导出脱敏诊断报告") { ReceiverReportView() } }
        }.navigationTitle("一键自检").onAppear { receiver.runDiagnostics() }
    }
}
struct ReceiverReportView:View {
    @ObservedObject private var receiver=Receiver.shared
    @State private var report=""
    var body:some View {
        List {
            Section {
                Text("只在你操作后生成和分享。报告不包含配对码、密钥、IP、设备名称、路径或原始音频。").font(.subheadline).foregroundStyle(.secondary)
                Button("生成／更新报告") { report=receiver.diagnosticReport }
                if !report.isEmpty { ShareLink(item:report) { Label("保存或分享报告",systemImage:"square.and.arrow.up") } }
            }
            if !report.isEmpty { Section("报告预览") { Text(report).font(.footnote.monospaced()).textSelection(.enabled) } }
        }.navigationTitle("诊断报告")
    }
}
private struct ReceiverBackgroundHelp:View {
    var body:some View {
        List {
            Section("锁屏与后台") {
                Text("开始播放后可锁屏或切换 App。无音频时，iOS 不保证接收会话无限后台待命；回到 App 可检查或重新准备接收。")
                Text("默认本机音频优先。来电或 iPhone 播放其他内容时让路，系统允许后尝试恢复；否则点恢复声音。")
            }
            Section("系统快捷入口") {
                Text("在支持的 iOS 版本，打开控制中心的编辑界面，添加一个“HyperAudio 接收开关”。接收开启时亮起，停止或暂停时熄灭；图标刷新可能稍有延迟。旧版按钮如仍只闪亮一下，请移除后重新添加开关。旧的独立“停止接收”控件可移除。")
                Text("锁屏播放卡片可暂停或恢复；快捷指令 App 中可搜索 HyperAudio。首次配对仍需打开 App 确认。")
            }
            Section("音频输出") { Text("默认使用支持媒体播放的蓝牙耳机。选择跟随系统输出后可用 iPhone 扬声器。耳机断开切到扬声器时，请回 App 点恢复声音再外放。") }
        }.navigationTitle("后台与快捷开关")
    }
}
private struct ReceiverAdvancedView:View {
    @ObservedObject private var receiver=Receiver.shared
    @State private var clear=false
    var body:some View {
        Form {
            Section { NavigationLink("手动连接信息") { ReceiverManualView() } }
            Section { Button("忘记全部已允许发端",role:.destructive) { clear=true }.disabled(receiver.running) }
            footer: { Text("通常在设备页逐台管理即可。全部清理只移除本机配对；运行时请先停止接收。") }
        }.navigationTitle("高级")
            .confirmationDialog("忘记全部已允许发端？",isPresented:$clear,titleVisibility:.visible) {
                Button("忘记全部",role:.destructive) { receiver.control.forgetAll() }
                Button("取消",role:.cancel) {}
            } message: { Text("下次连接需要再次确认允许，不影响其他 App。") }
    }
}
private struct ReceiverAboutView:View {
    var body:some View {
        List {
            Section {
                Label("HyperAudio Receiver",systemImage:"waveform")
                LabeledContent("版本",value:Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0.6.2")
                #if HYPERAUDIO_SIDEINSTALLER
                LabeledContent("发行版", value: "SideInstaller")
                #endif
                Text("将 Android 允许采集的媒体音频通过局域网传到 iPhone 播放。")
            }
            Section("感谢你的支持") {
                Text("如果它帮到了你，可以通过支付宝自愿赞助。所有现有功能均可直接使用。")
                    .font(.subheadline).foregroundStyle(.secondary)
                NavigationLink("查看支付宝收款码") { ReceiverSupportView() }
            }
            Section("隐私与连接") {
                NavigationLink { ReceiverPrivacyView() } label: { Label("隐私说明",systemImage:"hand.raised") }
                Text("了解音频传输、权限、本地保存的数据和清理方式。说明随 App 提供，可离线阅读。")
                    .font(.subheadline).foregroundStyle(.secondary)
            }
        }.navigationTitle("关于")
    }
}

private struct ReceiverPrivacyView:View {
    private static let blocks:[String] = {
        guard let url=Bundle.main.url(forResource:"PrivacyNotice",withExtension:"txt"),
              let text=try? String(contentsOf:url,encoding:.utf8) else {
            return ["隐私说明读取失败\n请重新安装完整版本后重试。"]
        }
        #if HYPERAUDIO_SIDEINSTALLER
        let commonNotice = text.components(separatedBy: "\n8. 可选签名与维护（iOS）")[0]
        let notice = commonNotice + "\n\n8. SideInstaller 安装与续期\n开发者账号、设备配对和签名由 SideInstaller 管理。相关数据由你使用的签名工具和 Apple 服务处理。"
        #else
        let notice = text
        #endif
        return notice.trimmingCharacters(in:.whitespacesAndNewlines).components(separatedBy:"\n\n")
    }()
    var body:some View {
        List {
            ForEach(Array(Self.blocks.enumerated()),id:\.offset) { _,block in
                let parts=block.split(separator:"\n",maxSplits:1)
                Section(String(parts[0])) {
                    if parts.count > 1 { Text(String(parts[1])).textSelection(.enabled) }
                }
            }
        }.navigationTitle("隐私说明").navigationBarTitleDisplayMode(.inline)
    }
}

private struct ReceiverSupportView:View {
    var body:some View {
        ScrollView {
            VStack(alignment:.leading,spacing:16) {
                Text("感谢你的支持").font(.title2.bold())
                Text("自愿赞助，不影响任何现有功能。")
                    .font(.subheadline).foregroundStyle(.secondary)
                Image("SponsorAlipay").resizable().interpolation(.none).scaledToFit()
                    .background(Color.white)
                    .accessibilityLabel("支付宝赞助收款码，收款人以原图和支付宝确认页为准")
                Text("可截图后打开支付宝，使用“扫一扫 → 相册”识别。付款前请核对收款人和金额。")
                    .font(.footnote).foregroundStyle(.secondary)
            }.padding(20)
        }.background(HyperStyle.background).navigationTitle("支持 HyperAudio")
    }
}

#if !HYPERAUDIO_SIDEINSTALLER
private struct ReceiverSigningHelpView: View {
    var body: some View {
        List {
            Section("首次设置") {
                Text("先用电脑安装本版本，保留原开发者账户和证书。iOS 17.4 及以后需要包含 RPPairing 信息的配对文件；旧的 Xcode 无线配对记录不能直接代替该文件。")
                Text("在“签名与维护 → 必要设置”导入本机配对文件、配置回环 VPN，并由你在手机中登录原开发者账户。账户密码与验证码由你自行输入。")
                Text("建议先手动完成一次续期，确认主 App 与控制中心扩展均成功，再配置快捷指令。")
            }
            Section("日常续期") {
                Text("在当前描述文件到期前操作：连接 LocalDevVPN 等回环 VPN → 刷新应用签名描述文件 → 断开 VPN。可在快捷指令中创建定时自动化；自动化仍受 iOS、账户验证和网络条件限制。")
                Text("显示的有效期取主 App 与扩展中的最早到期时间。任何一个描述文件校验或安装失败都不会显示整体成功；若已经安装了部分文件，请修复原因后再次刷新。")
            }
            Section("证书与音频") {
                Text("这不是永久签名。日常续期沿用现有证书，不会创建或撤销证书；证书过期、账户不一致或签名不匹配时，需要电脑重新签名安装。")
                Text("回环 VPN 仅用于续期。为避免影响双机局域网音频连接，建议停止接收后续期，完成后断开 VPN 再恢复接收。本功能不会自动停止你的音频。")
            }
            Section { Text("配对文件包含设备信任凭据，请勿分享。详细数据处理方式见“关于与赞助 → 隐私说明”。") }
        }.navigationTitle("签名与维护说明").navigationBarTitleDisplayMode(.inline)
    }
}
#endif
