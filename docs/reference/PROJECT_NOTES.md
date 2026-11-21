> 历史项目说明：保留0.6/0.6.1阶段的技术背景与验证语境。当前版本、安装包和操作以 [完整手册](../guide/安装操作手册.md) 及根目录README为准。

# HyperAudio Lab：Android 媒体音频 → iPhone → 蓝牙耳机

研究基线：2026-11-05 / MVP 0.2；2026-11-07 两端更新至 0.6，采用“连接／设备／设置”三页，加入逐台配对管理、使用引导、脱敏报告及缓冲/日志性能优化，保留 0.5 音频体验、后台恢复和快捷入口。Android 于 2026-11-08 更新至 **0.6.1/code7**，重做蓝紫卡片视觉，iOS 保持 0.6。日常操作见 [QUICKSTART-v0.6.1.md](../history/QUICKSTART-v0.6.1.md)。0.5 已由用户反馈实际播放正常；0.6 本轮完成开发与本机验证，真机性能按用户要求后测。

**结论：允许采集的媒体可以尝试转发，普通未 Root 应用无法保证“小米在所有时刻、所有场景完全不出声”。** 本工程用于验证你的设备上能否同时做到“采集信号非零、媒体本地无声、AirPods 正常播放”，不是已经通过红米真机认证的成品。

目标设备参数最初按用户提供信息记录。2026-11-06 已确认 iPhone 真机型号/系统及基础音频互通；Android 型号/固件版本仍按原始目标记录，未独立核验。详细结果见 [VALIDATION.md](../../validation/VALIDATION.md)。

| 发端 | 收端 | 优先验证 App |
|---|---|---|
| 红米 K80 Pro，HyperOS 3.0.305，Android 16 | iPhone 18 Pro Max，iOS 27.2，AirPods Pro | 哔哩哔哩、抖音、斗鱼 |

## 先看可行边界

| 目标 | 结论 |
|---|---|
| 采集允许捕获的媒体/游戏音频 | Android 10+ 有公开 API；需要录音权限、本次 MediaProjection 授权、同一用户空间及音源允许 |
| 手机媒体音量设零，采集仍然有声 | 有公开同类工程支持独立音量的证据；值得优先测试；不是 Google 对所有 OEM 路径的硬保证 |
| 用户按音量键、App 恢复音量时也绝不外放 | 普通应用做不到保证；250ms 复查会有竞态，且调度可能更慢 |
| 所有通知、铃声、闹钟、紧急声音都不响 | 本工程不具备这种控制能力；媒体音量不等于全机音量 |
| 强行采集明确禁止捕获/受保护的内容 | 不支持，不能从公开接口绕过 |
| 电话、微信通话、直播连麦双方语音 | 不作为可用范围；语音用途通常不符合媒体捕获条件 |
| iPhone 接收网络 PCM 并经所选 AirPods 播放 | 公共网络和 AVAudioEngine API 足以实现 MVP；实际系统版本需 Mac 构建和真机验证 |
| AirPods 断开时 iPhone 瞬间绝不外放 | 本版会检测并停止，系统路由切换的瞬间不能硬保证 |

详细依据和备选路线在 [RESEARCH.md](RESEARCH.md)。

## 文件

- `android/`：Android Studio / Kotlin 工程，minSdk 29，compileSdk / targetSdk 35，JDK 17，AGP 8.7.3，Gradle 8.9，Kotlin 2.0.21。target 35 是可复现的实验基线，可在 Android 16 运行；不代表已覆盖 target 36 的全部行为，更不是已通过商店上架检查。
- `ios/HyperAudioReceiver.xcodeproj`：完整 Xcode 项目及共享 Scheme，Swift 5 模式，最低 iOS 16；使用能识别你实际 iPhone / iOS 的 Xcode。
- `tools/pcm_tool.py`：电脑接收探针、已知音源发送器、PCM 转 WAV；`control_probe.py` 用于单独测试自动配对控制通道。
- `QUICKSTART-v0.6.1.md`：Android 新界面与覆盖安装；`QUICKSTART-v0.6.md`：三页操作、配对、蓝牙与报告；`QUICKSTART-v0.5.md` 保留音频参数，`QUICKSTART-v0.4.md` 保留基本后台设置和快捷入口步骤。
- `tools/export_diagnostics.py`：用 ADB 导出实验日志。
- `VALIDATION.md`：本环境检查记录；明确区分构建、协议测试和真机验证。
- `test-results.csv`：针对你的设备和三个 App 的待填测试表。
- 当前 Android 包为优化版 `HyperAudioLab-0.6.1.apk` 与调试版 `HyperAudioLab-0.6.1-debug.apk`，iOS 为 Release 开发签名 `HyperAudioReceiver-0.6-signed.ipa`，包含控件扩展和图标。Android 可覆盖本机 0.3–0.6；原 Windows 0.2 签名不同，更新前需导出日志/PCM 再处理安装。旧 APK/IPA 均保留。验证结果和未验收范围见 `VALIDATION.md`。

## 构建与安装

### Android

1. Android Studio 打开 `android/`，安装 SDK Platform 35、Build Tools 35.0.0，选择 JDK 17。
2. 等待 Gradle 同步，运行到红米手机。也可在 `android/` 执行：

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Mac/Linux：`chmod +x gradlew && ./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest`。

安装调试 APK、授予应用权限均不需要 Root；正式转发功能也不需要 ADB，ADB 仅用于安装和导出诊断。工程没有申请 `CAPTURE_AUDIO_OUTPUT`、系统路由特权、无障碍或悬浮窗权限。

### iPhone

1. 在 Mac 上用 Xcode 打开 `ios/HyperAudioReceiver.xcodeproj`。
2. 在 Signing & Capabilities 选择自己的 Team，必要时更换 Bundle Identifier，开启手机开发者模式并信任开发证书。
3. 选择实际 iPhone 并 Run。本机另有适用于已登记 iPhone 的开发签名 IPA，具体有效期见 `validation/ios-0.6-ipa.json`；其他设备需使用自己的签名和描述文件。
4. 已设置 `UIBackgroundModes=audio` 和本地网络用途说明。若系统询问本地网络访问，允许；不同版本及单向 UDP 接收可能不会弹窗，没弹窗不等于失败。

Mac 编译检查（不签名，仅检查源码和链接）：

```sh
xcodebuild -project ios/HyperAudioReceiver.xcodeproj \
  -scheme HyperAudioReceiver -sdk iphoneos -configuration Debug \
  -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build
```

## 首次连通：先验证收端，再验证采集

### 日常使用的简化流程

1. 两机连同一可信局域网，可以是路由器，也可以由红米或 iPhone 开热点。AirPods 保持连接 iPhone。
2. iPhone 打开接收 App，点“开始接收”；首次出现本地网络权限时允许。
3. 红米点“一键连接并采集”。首次在 iPhone 弹窗点“允许”；以后记住配对，不需要输入 IP 或 8 位码。
4. 红米确认系统采集授权。Android 每次新建 MediaProjection 会话仍需这一步。

约 4 秒自动寻找 Bonjour 接收端。出现多个接收端时在红米选择；优先选择上次配对的接收端。重新连接会自动取得新的音频码，并清理收端旧 UDP 会话，无须为了重连而重启 iPhone App。

若自动发现被热点或路由器屏蔽，工程会尝试上次配对地址以及实际 Wi-Fi 网关上的控制端口。仍然找不到时，展开红米“手动连接与配对管理”，可选择：

- **只填地址配对**：填 iPhone 的局域网 IPv4，点“按此地址配对（不用填码）”。首次仍在 iPhone 允许，成功后可记住地址。
- **完全手动连接**：填地址和 iPhone 当前码，点“按手动信息开始采集”，保留 0.1 的调试路径。

优先试“红米开热点，iPhone 连接红米”。iPhone 开热点时也可尝试自动发现或网关回退；手动信息已改为显示 Wi-Fi、桥接和热点接口的 IPv4，不再只检查 en0。**这些热点与 Bonjour 行为仍需你的设备实测；代码有回退不等于所有固件均支持自动发现。**

配对记录失效时，可在红米展开备选面板并点“忘记自动配对记录”，重连后在 iPhone 再次允许。iPhone 的“忘记已允许的发端”需先停止接收。忘记任意一端后，都可以重新走首次确认流程。

首次配对通过未加密 TCP 在可信局域网交换随机设备密钥，后续用 HMAC-SHA256 随机挑战证明持有配对密钥；每次音频码仍会改变。这减少手工输入和错误重放，**不是端到端加密或抗中间人方案**。设备名称不是身份凭证，只允许你刚刚发起的配对。密钥保存在应用私有偏好存储，本实验版未加入硬件密钥库。音频仍是未加密 UDP。

### 首次调试与手动对照

1. 两机连同一可信 Wi-Fi，优先信号稳定的 5GHz 网络；关闭会隔离客户端的访客网络。避免测试过程中切换热点、VPN 或 Wi-Fi。
2. AirPods 连接 **iPhone**；在 iPhone 控制中心选择 AirPods 为媒体输出。收端仅放行 Bluetooth A2DP/LE，不能从公开 API 强制选中指定 AirPods；请核对界面显示的设备名称。
3. 收端点“开始接收”，展开“手动连接信息”，记录当前局域网 IPv4 和 **新生成**的 8 位码。开始接收及自动建立新会话时均会换码，手动测试应使用当前显示的码。
4. 可先用电脑测试 iPhone，替换地址和码：

```sh
python tools/pcm_tool.py send --host 192.168.1.10 --token 1a2b3c4d --seconds 10
```

应在 AirPods 听到低幅度 1kHz 测试音，收端约每秒增加 100 包。这个步骤不涉及小米、目标 App 或 Android 采集策略。开始前调低 iPhone 音量。

5. Android 打开 HyperAudio Lab，展开手动连接，填相同 IPv4 和码，选择“媒体音量设零”。先手动把媒体音量归零，再点“按手动信息开始采集”，授予录音和系统捕获授权。也可直接使用一键连接跳过输入。若系统提供单 App / 整屏选择，本轮选整个屏幕以减少范围变量；本工程不创建视频采集流。
6. 打开 Android 测试音源，点“播放：允许采集”。测试音自身没有请求音频焦点、没有修改系统音量；保持媒体为零。
7. 返回采集界面可查看电平；约 1 秒更新一次。已知音源的理论 RMS 约 **−23dBFS**，具体混音增益可不同。只要明显高于底噪并随播放/停止变化，就有采集信号。`−120dBFS` 是本工程显示下限，不是硬件噪声测量值。
8. 在 AirPods 确认有声，同时用另一台设备在红米扬声器旁录音，确认没有本地测试音。**屏幕显示音量 0、能听到 AirPods，并不足以单独证明扬声器没声。**
9. 点“播放：禁止采集”，确保没有其他音源，此时应降到零信号。重新点“允许采集”应恢复。它验证的是播放器捕获策略，和同 App 自采集一样受公开规则约束。
10. Android 0.3 持续转发，不再设置五分钟会话上限；测试后停止测试音，再在界面或前台通知中停止采集。系统撤销授权、采集错误或原有设备/音量保护仍会停止会话。不会自动恢复媒体音量；恢复前先暂停所有源，再手动调音量。

跨 App 播放时，在 Android 授权窗口选择“整个屏幕”。系统播放音频采集也使用这个 MediaProjection 授权窗口，另需录音权限；本工程只通过 AudioRecord 采集和发送 PCM，不创建视频流或读取麦克风。识别当前进程不能替代这项用户授权。系统停止会话后仍需重新授权，不能自动复用过期许可。

## 三种本地静音实验

### A：媒体音量归零（首选）

`AudioManager.setStreamVolume(STREAM_MUSIC, 0, 0)`；前台服务每 250ms 复查。记录捕获 RMS、峰值、当前媒体音量及 mute 状态。没有改变铃声、闹钟、通知等音量。

通过条件：允许采集的测试音在 `media_volume=0` 时 PCM 仍非零，AirPods 有声，外部录音没有红米本地声。即使通过，也只能说明此次固件、App 版本、场景下成立。

若 PCM 同时为零，不要放大数字零。改做 B 或 C 对照，判断是固件混音、源策略还是音源自己已经暂停/静音。

### B：USB DAC / 有线静音负载

K80 Pro 可优先使用兼容的 USB-C 数字音频 DAC，并在模拟输出接合适的静音负载。普通只转接没有 DAC 的转接头不一定可识别；没有任何输出设备的裸转接头也不一定建立路由。不建议自行短路音频端子。

选择“外接 USB/有线静音负载”，允许保持非零媒体音量，以观察采集是否恢复。工程只检查 **存在** USB/有线输出设备；这不能证明目标 App 真正路由到该设备。先用已知音源确认扬声器没有发声，再测试商业 App。

设备断开时会请求媒体音量归零并停止采集；回调与路由切换有竞态，仍可能短暂外放。若对任何漏音都零容忍，此路线也不能靠普通应用给出绝对承诺。

### C：媒体流 ADJUST_MUTE

`adjustStreamVolume(STREAM_MUSIC, ADJUST_MUTE, 0)`；每 250ms 检查 `isStreamMute()`，仅未静音时重新请求。对比 A 的采集电平，可以发现固件是否区别处理“音量指数为零”和“流被静音”。停止不主动解除 mute；恢复前先停止所有音源，再用系统音量控制解除。

三种方法均不修改音源 App 自身的 `AudioTrack.setVolume`，因为普通应用没有控制其他播放器增益的权力。音源 App 内的静音按钮可能在复制之前就把样本变为零，测试时应保持该按钮处于“有声”。

## HyperOS 3.0.305 / Android 16 的准备与限制

- 在系统设置搜索此应用的“省电策略”，实验时选“无限制”并允许后台联网。小米其他机型官方说明确认后台省电可能限制网络，K80 Pro 此固件菜单和实际效果需要你验证。
- 可在最近任务中锁住应用，避免一键清理；这不是系统不杀进程的保证。自启动并非本工程启动条件，本工程没有开机自动采集，也不能绕过每次捕获授权。
- 先关闭其他录屏/投屏/录音工具，避免并发导致失败。必要时临时关闭音效、空间音频和音量助手中的多 App 音量功能进行对照；这是控制实验变量，不是已知必需修复。
- 保持红米屏幕点亮做第一轮。Android 15 QPR1+ 官方说明锁屏可停止 MediaProjection；Android 16 / HyperOS 是否对音频独用场景同样处理，需要测 `onStop()`。被系统停止后工程不会偷偷重启，需再次授权。
- Android 16 原生局域网限制处于选择加入阶段。本工程使用 target 35 + `INTERNET`，没有主动加入限制。若出现 EPERM、发包失败或联网权限提示，检查 HyperOS 单独的联网控制、开发者兼容开关及 VPN；不要把网络问题归因为音频保护。
- 单独媒体静音不足以满足“全机静音”。你可以手动关闭通知、触感声音和配置勿扰以减少干扰，但闹钟、紧急提示和强制声音仍不能由本应用保证。

## 三个目标 App 的验证顺序

先保证已知音源测试通过，再逐个关闭其他音源测试：

| App | 分开验证的场景 | 特别观察 |
|---|---|---|
| 哔哩哔哩 | 普通 UGC 视频、直播、后台播放；付费/受保护内容另列 | 视频切换/横竖屏后是否改变捕获；不要用受保护内容代表全部视频 |
| 抖音 | 普通短视频、直播、切到下一条、退出后重开 | App 内静音、自动恢复音量、不同视频音轨；不要将连麦模式与普通视频混为一谈 |
| 斗鱼 | 普通直播、切换清晰度/直播间、后台返回 | 直播 SDK、路由和声音用途可能随场景改变；连麦不属于本 MVP 的保证范围 |

不预先宣称任何一个 App 的全部版本允许或拒绝捕获。记录版本号、时间、页面类别、A/B/C 模式、Android dBFS、iOS 收包数、AirPods 听感和外部录音结果。填写 `test-results.csv`。

## 分层排障和日志

### Android 采集是否有信号

勾选保存 PCM 后采集，PCM 可选。0.5 每次只保存前 5 分钟音频：单声道28,800,000字节（约28.8MB）、立体声57,600,000字节（约57.6MB）；达到上限只关闭存档，转发继续。CSV按段轮转，历史指标超过七天/20MiB时清理，当前日志和PCM保留。实际PCM文件名标注声道、时长和额度，旧版示例路径如下：

```text
/storage/emulated/0/Android/data/lab.hyperaudio/files/session-<time>/
  metrics.csv
  capture-48k-mono-s16le.pcm
```

用导出工具（需 Android Platform Tools）：

```sh
python tools/export_diagnostics.py --out diagnostics
python tools/pcm_tool.py wav diagnostics/unpacked/session-xxx/capture-48k-mono-s16le.pcm capture.wav
```

立体声PCM转换时使用实际文件路径，并加 `--channels 2`，例如 `python tools/pcm_tool.py wav stereo.pcm stereo.wav --channels 2`。电脑UDP探针和合成发端仍保留HAP1单声道模式；Android手动token路径也保守使用单声道。

导出工具保存 TAR；用系统归档工具解压至 `diagnostics/unpacked/` 再执行示例中的 WAV 转换。PCM/日志可能含私人音频；本工程不上传云端，测试结束可在系统应用设置清除数据。

### 不依赖 iPhone，测电脑接收

电脑执行以下命令，Android 的目标地址填电脑局域网 IPv4，配对码手动填 `1a2b3c4d`。必要时允许电脑防火墙接收 UDP 50005。

```sh
python tools/pcm_tool.py listen --token 1a2b3c4d --seconds 20 --out probe.wav
```

- Android PCM 非零，电脑有包而 iPhone 没包：检查 iPhone IP、开始后新码、本地网络设置和 AP 隔离。
- iPhone 有包但 AirPods 无声：检查 iPhone 音量、输出路由、系统中断、接收端错误及负载；不再归因于 Android 捕获策略。
- 测试音源有信号，目标 App 没信号：先核对 App 内音量/播放状态和场景，再考虑捕获策略、DRM 或特殊音频路径；单凭数字零不能精确断言原因。
- 所有音源始终零：检查录音权限、系统捕获授权、同一用户空间、系统音频隐私开关、其他捕获进程及固件行为。不要改用麦克风冒充系统内录。

## 必须做的故障实验

1. 红米锁屏、App 退后台、系统撤销录屏、撤销录音权限、最近任务清理、Wi-Fi 断开/恢复。
2. 播放期间按音量键、重开目标 App、切换直播间，旁边的外部录音设备持续记录是否漏音。
3. B 模式拔 DAC；确认工程请求归零并停止，同时记录插拔瞬间是否外放。
4. 收端断开 AirPods、切换到 iPhone 扬声器、来电、其他音乐抢占；本版停止并要求手动重新开始，不自动改回扬声器继续播放。
5. 自动模式恢复会话时，Android 停止后再次一键连接，收端自动换码并清旧队列。iPhone 若已被系统停止，则先重新开始接收。手动模式仍建议两端停止，再使用当前码。

## 协议、延迟与 MVP 取舍

Android AudioRecord → 48kHz / 16-bit / mono PCM → 同局域网单向 UDP → Swift NWListener → 约 60ms 起播缓冲与有界重排 → AVAudioPlayerNode → iOS 所选媒体输出 → AirPods。

每 10ms 480 个样本，984 字节/包：`HAP1` 4B，序号 4B（大端），Android 单调纳秒 8B（大端），配对码 ASCII 8B，再接 PCM 960B（小端）。100 包/秒，应用数据约 0.787Mbps；不含网络开销。音频没有压缩、加密或重传。0.2 新增独立 TCP 50006 控制通道与 `_hyperaudio._tcp` Bonjour 发现；TCP 控制消息有确认，UDP 音频仍没有播放回执。

自动连接仅临时持有 Android multicast lock，发现结束或取消时释放。控制和音频 Socket 优先绑定能到达接收端的 Wi-Fi 局域网路由，避免有线/无线局域网无互联网时误用默认移动数据网络；不改变全应用默认网络。没有直接发送或接收原始 IP 多播，iOS 使用系统 Bonjour，已声明 NSBonjourServices。

收端最多保存 30 包，序号超前太多会丢旧数据重缓冲；缺包且已收到足够后续包时补零。500ms 无包清空播放队列。播放器软件队列目标 6 包；回调、主线程调度、系统音频和 AirPods 自身缓冲会再增加延迟。

界面显示的排队毫秒数不是端到端延迟。Android 与 iOS 单调时钟没有同步，不能直接相减测时延。建议用画面闪烁/声脉冲样本和外部高帧率录音录像测端到端偏移；预计会有可感知延迟，不承诺游戏或口型同步。

本版采用主线程串行管理网络/调度以便审查，未实现专业实时渲染队列或时钟漂移补偿。先验证可行性；长时间稳定使用再改为专用音频队列、Opus/WebRTC、可靠配对及加密，并做功耗、漂移和后台测试。iPhone 已声明后台音频能力，但并不保证网络断流后仍可在后台长期保活。
