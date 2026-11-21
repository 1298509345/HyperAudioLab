# 可行性核对与依据

核对日期：2026-11-05。设备版本来自用户描述；公开资料与源码推断不替代该固件的真机测量。以下链接是可复核来源，工程为独立实现，没有复制 sndcpy / scrcpy 的源码。

## 1. 公开 API 能提供什么

Android 官方将 AudioPlaybackCapture 定义为复制其他应用的播放音频。采集端使用 `RECORD_AUDIO` + 用户同意的 `MediaProjection`，再把 `AudioPlaybackCaptureConfiguration` 交给 `AudioRecord.Builder`。这条链路不需要 Root。

被采集播放器必须使用 MEDIA / GAME / UNKNOWN 用途，并允许普通应用捕获；进程、清单和单播放器策略共同约束，较严格的策略不能被采集端放宽。旧 target App 的默认策略也可能不同。同一用户空间是前提；双开、工作空间或第二空间需要单独核对。源自己暂停或把样本静音时，没有可恢复的音频。

来源：[Android 官方：Capture video and audio playback](https://developer.android.com/media/platform/av-capture)。本工程使用全部三个合格用途，不尝试添加 VOICE_COMMUNICATION；默认混合所有合格音源，未按 UID 只捕获某一个 App。

来源：[AudioPlaybackCaptureConfiguration.Builder](https://developer.android.com/reference/android/media/AudioPlaybackCaptureConfiguration.Builder)。若后续增加 UID 白名单，可以缩小捕获范围，但仍不能绕过源策略。

## 2. 捕获不等于独占路由

`setStreamVolume(STREAM_MUSIC,0,0)` 控制媒体流音量指数；`adjustStreamVolume(...,ADJUST_MUTE,0)` 请求媒体流静音。设备固定音量策略、系统策略和静音状态都会影响结果。公开接口没有赋予普通应用不可被别的 App/用户改变的媒体静音锁。

来源：[AudioManager 官方参考](https://developer.android.com/reference/android/media/AudioManager)。`getStreamVolume()==0` 是软件状态证据，不能替代扬声器的声学测量。停止捕获也不会暂停其他应用的播放器，故工程故意不自动恢复音量。

同类非 Root 工程 sndcpy 明确说明本地播放继续，手机和电脑可以分别调节音量。这支持“媒体本地零音量但捕获非零”的实验方向；它没有给出 K80 Pro / HyperOS 3.0.305 的保证。

来源：[sndcpy README](https://github.com/rom1v/sndcpy#readme)。证据等级是既有工程行为说明，不是 Android 对所有设备、所有播放器的契约。不能把手机媒体音量直接等同于应用自己的样本增益，也不能把“音量设零”和“播放器内部静音”当成同一件事。

若 A 或 C 模式出现 PCM 为零，应在外接静音负载下保持非零媒体音量作对照。只有对照结果才能判断此固件的实际处理，不能凭 API 名称推导一定在音量前或音量后复制。

## 3. 真正重定向音频的接口为什么不是普通 App 方案

`MediaRecorder.AudioSource.REMOTE_SUBMIX` 的官方语义包含把相应流从本地扬声器/耳机重定向出去；同时明确要求系统组件专属的 `CAPTURE_AUDIO_OUTPUT`。它还排除铃声、闹钟和通知，因此即使有该能力也不是全机静音开关。

来源：[REMOTE_SUBMIX 官方参考](https://developer.android.com/reference/android/media/MediaRecorder.AudioSource#REMOTE_SUBMIX)。不能仅在普通应用清单里声明该权限，就得到这项能力；普通运行时授权或常规 `pm grant` 也不能使签名级能力变为普通危险权限。

scrcpy 默认 output 音频模式使用该类能力，并支持本地禁播。它通过 ADB 启动具有 shell 身份的服务；“不 Root”不等于“普通安装应用权限”。其 playback 模式还有捕获策略限制。

来源：[scrcpy 官方 audio 文档](https://github.com/Genymobile/scrcpy/blob/master/doc/audio.md)、[维护者讨论 #4380](https://github.com/Genymobile/scrcpy/issues/4380)。本项目没有套用 hidden AudioPolicy、MODIFY_AUDIO_ROUTING 或假装自建 virtual audio device 具有特权。

`AudioRecord.setPreferredDevice()` 选择的是此录音实例输入；`AudioTrack.setPreferredDevice()` 作用于自己的播放器，不能重定向别的 App。`setSpeakerphoneOn(false)` / 通信设备选择不是全局媒体静音，`MODE_IN_COMMUNICATION` 还会改变路由和音量语义，可能干扰来电。音频焦点也不能保证对方持续产出可捕获样本，可能直接让其暂停。本工程不使用这些方法充当静音开关。

来源：[AudioRecord](https://developer.android.com/reference/android/media/AudioRecord#setPreferredDevice(android.media.AudioDeviceInfo))、[AudioTrack](https://developer.android.com/reference/android/media/AudioTrack#setPreferredDevice(android.media.AudioDeviceInfo))、[小米 Audio 接口使用规范](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1640)。

## 4. HyperOS 需要特别谨慎的点

小米公开音频规范明确描述：不同路由/静音状态影响音量读数；视频、直播 App 保存并恢复音量可能解除媒体静音。它也提醒正确处理通信模式。这是“定时压零不能保证零漏音”的直接厂商依据。

同一来源：[小米 Audio 接口使用规范](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1640)。不能从该 MIUI/HyperOS 通用文档推导具体 App 在本机一定如此；它证明该类风险不是仅凭空假设。

小米提供系统内录不证明第三方应用有同等能力。系统组件有不同捕获权限与策略；“小米 iOS 互联能同步电话”同样不能推导出存在第三方可调用的全局媒体通道。此次核对的公开资料中，没有找到针对 K80 Pro / HyperOS 3.0.305、向普通开发者承诺可独占接管全局媒体并关闭扬声器的 SDK 契约。

来源：[小米 Screen Recorder 官方帮助](https://www.mi.com/uk/support/article/KA-33151/)、Android 前述捕获策略文档。这里的结论是“未找到可依赖公开契约”，不是宣称绝对不存在任何厂商内部能力。

后台省电可能限制网络；“无限制”是实测前的合理配置，不是永不杀后台的保证。自启动设置也不能代替 MediaProjection 用户同意。

来源：[小米关于后台网络限制的官方说明](https://www.mi.com/uk/support/faq/details/KA-538010/)、[小米自启动权限管理说明](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1624)。前者以 Xiaomi 15 为例，应用到 K80 Pro 时只作为检查方向。

小米适配指南也确认了较新 Android 的每次会话授权等变化。本工程在可见 Activity 发起授权，得到结果后启动 mediaProjection 前台服务，在服务成为前台后取得 MediaProjection，并处理 `onStop()`；不自动重用旧授权。

来源：[小米 Android 15 适配指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1826)、[Android 前台服务类型：mediaProjection](https://developer.android.com/develop/background-work/services/fgs/service-types#media-projection)。

Android 15 QPR1+ 官方说明锁屏停止投影。对 Android 16 上音频独用的实际覆盖范围，需要本次真机测试；在此之前不能承诺红米息屏后一直采集。前台服务和短时唤醒锁也不能绕过系统撤销。

来源：[Android Media projection](https://developer.android.com/media/grow/media-projection)。

Android 16 原生局域网保护是 opt-in；后续 Android 17 / target 37 的强制权限不是本项目 Android 16 / target 35 的默认要求。若用户主动开启实验兼容限制，或 HyperOS 另设联网控制，需另行处理。本工程没有为未知系统变体臆造权限名称。

来源：[Android Local network permission](https://developer.android.com/privacy-and-security/local-network-permission)。

## 5. iPhone 输出到 AirPods 的公共能力

收端通过 `NWListener` 接收单播 UDP，再使用 `AVAudioEngine` / `AVAudioPlayerNode` 播放。`AVAudioSession.Category.playback` 是标准媒体播放类别，后台播放配合 `UIBackgroundModes=audio`。无需蓝牙底层开发或 AirPods 私有协议。

来源：[Apple playback category](https://developer.apple.com/documentation/avfaudio/avaudiosession/category-swift.struct/playback)。本版没有使用 `mixWithOthers`，可能中断 iPhone 其他媒体；它不是把 Android 音频自动混入任何通话。

用户在系统中选择 AirPods；应用观察当前路由和路由变化。MVP 只接受 Bluetooth A2DP / LE 输出，显示名称供人核对，**不能仅靠端口类型证明型号就是 AirPods**。换到其他输出、系统音频中断或引擎配置变化时停止，重新核对后手动开始。系统先切路由再通知时仍有瞬间风险。

来源：[Apple Responding to audio route changes](https://developer.apple.com/documentation/avfaudio/responding-to-audio-route-changes)、[routeChangeNotification](https://developer.apple.com/documentation/avfaudio/avaudiosession/routechangenotification)。

本地网络用途说明已包含。0.2 的自动连接使用系统 Bonjour 发布 `_hyperaudio._tcp`，已加入 NSBonjourServices，首次必须允许系统请求的本地网络访问。原始手动 UDP 路径则按 Apple TN3179 对单播接收的规则处理，是否出现弹窗不能单独用作故障判据。本版未做原始 IP 多播操作，没有申请原始多播 entitlement。

来源：[Apple TN3179](https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy)。目前只有源码和项目结构检查，不能声称已验证用户提供的 iOS 27.2。

新增发现接口依据：[Android NSD](https://developer.android.com/develop/connectivity/wifi/use-nsd)、[Apple NWListener.Service](https://developer.apple.com/documentation/network/nwlistener/service-swift.struct)、[NSBonjourServices](https://developer.apple.com/documentation/bundleresources/information-property-list/nsbonjourservices)。热点对 mDNS 的支持依具体系统及网络策略而定；保存地址、实际网关探测及手动地址配对是回退机制，不是所有热点可自动发现的保证。

## 6. 不能保证与决策标准

| 项目 | 可交付的证据 | 仍然缺少的证据 |
|---|---|---|
| Android API 使用 | 可审查 Kotlin / manifest / Gradle 构建结果 | K80 Pro 实际 MediaProjection、AudioRecord 行为 |
| 三个 App 捕获兼容 | 已知正负测试源、PCM、RMS/峰值记录 | 每个目标版本、页面、直播 SDK 的实测 |
| 本地媒体静音 | 请求设零/静音及状态日志 | 外部录音、切换瞬间、音量键/重开 App 漏音测试 |
| USB 静音负载 | 检查外接 sink 存在、断开请求压零 | 实际音源路由；断开到静音之间竞态 |
| iPhone / AirPods | 完整可导入项目、网络/播放实现、编译命令 | Mac 编译、实际设备播放、路由中断、后台及功耗测试 |
| 同步体验 | 有界队列、丢包补零、无包超时 | 真正端到端延迟、时钟漂移与持续播放测试 |

只要允许采集的测试音在静音时非零、外部测声无本地音、三个目标场景均可听，就可以把它作为个人使用候选，再做更长稳定性验证。每次系统或目标 App 更新都需回归。

如果你要求的是“无论音量键、App 行为、DAC 断开、系统报警，红米绝不出声”，本项目的普通应用路线应判定不满足；不要仅凭一次通过就做永久保证。

## 7. 备选路线及代价

| 路线 | 如何满足核心目的 | 限制/需要验证 |
|---|---|---|
| A/C 媒体归零或流静音 + 本 MVP | 无硬件、无 Root、AirPods 留在 iPhone | App 捕获许可；音量与系统竞态；不能全机绝对静音 |
| B USB DAC + 静音负载 + 本 MVP | 把普通媒体本地输出接到无扬声器负载，仍从公开复制路径转发 | 不是强制全局路由；占 USB 口；拔线回退；捕获限制仍在 |
| ADB / shell 服务 + 直接重定向 | 可研究 scrcpy 输出源或 shell AudioPolicy，减少普通媒体本地播放 | 无 Root 但有调试特权；需 ADB 配对/重启恢复、OEM 限制；不是本 MVP 的权限级别；不保证铃声/闹钟，也不假设能突破 DRM |
| 小米官方互联/投屏能力 | 若厂商当前正式产品支持目标媒体，系统能力可能更强 | 电话接续不等于公开第三方全媒体接口；须按官方产品实际功能验证 |
| 在 iPhone 使用同一内容 App | 直接从 iPhone 到 AirPods，避免跨手机捕获和网络延迟 | 不再播放小米正在运行的那个实例 |
| 支持双设备的耳机/硬件混音 | 从系统外解决设备切换或混音 | 可能要更换耳机或添设备；多点连接也不等于两路同时混音 |

若考虑 USB DAC 模拟输出进入 iPhone 的 USB 音频输入，并由 iOS App 监听再播放，也属于独立硬件方案；需要适配输入监听、供电、playAndRecord 类别和蓝牙输出，不能把“插线”当成已经可用的通用方案。
