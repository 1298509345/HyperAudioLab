# 当前交付：Android 0.6.5 / iOS 0.6.5

两端“设置 → 关于与赞助 → 隐私说明”新增随包离线、分节、可滚动页面，说明采集权限、局域网传输、本地配对、CSV及可选PCM、主动报告导出、数据清理和赞助。iOS 锁屏媒体卡片禁用Stop并移除handler，保留Play/Pause/Toggle，使暂停保留接收会话；完整停止仍在App、控制中心接收开关和既有快捷指令可用。实时流元数据保留；用户已在真机确认暂停图标正确、可暂停恢复。

- [Android 优化包](android/HyperAudioLab-0.6.5.apk)：516521字节，0.6.5/code10；SHA-256 `24c105a80166972f945b7c9227f1611ce6c2f25bf62f4466d16cf1580c1d970c`。
- [Android 调试包](android/debug/HyperAudioLab-0.6.5-debug.apk)：3064769字节。
- [iOS 完整包](ios/HyperAudioReceiver-0.6.5-signed.ipa)：1761676字节，主App与扩展0.6.5/build11；SHA-256 `c0848e1d9f36b35339572eb11b6173de8fbf64df68d7b4102f4d13f4a30e2146`。
- [Android 验证与包信息](../validation/android-0.6.5-artifacts.json)、[iOS 包与签名](../validation/ios-0.6.5-ipa.json)、[本轮验证记录](../validation/0.6.5-notes.md)。

Android既有51项单测通过，Debug/优化Release构建成功，lint 0错误、1项既有MonochromeLauncherIcon告警；新APK证书与本机0.6.3一致，可覆盖升级。优化包已在专用API35模拟器安装启动，未发现本轮崩溃日志；电脑控制工具不能识别模拟器窗口，新增页点击/滚动/返回尚未验证。

iOS 19项生产源码/边界回归通过，设备Release及模拟器Debug构建成功，IPA打包前后嵌套签名通过。登记iPhone已覆盖安装、回读0.6.5/build11并成功启动；用户已确认锁屏暂停图标、暂停/恢复和隐私页均正常。构建存在既有非致命AppIntents SSU归档提示，退出码0。

当前iOS控制中心保留0.6.4已由用户验收的单一状态开关：接收开启常亮，停止/暂停熄灭，系统刷新可能略有延迟。旧版按钮若仍仅闪亮，移除后重新添加一个“HyperAudio 接收开关”。旧Start/Stop快捷指令兼容。

iOS主App签名已续至2026-11-15 17:42:17（北京时间），controls至同日17:42:21。已向Apple重新申请描述文件并重新签名；当前IPA未重新安装到手机，手机原有安装仍使用旧期限，须覆盖安装新包后生效。仅适用于登记设备，其他用户按手册自行重签。旧IPA、ZIP和描述文件均在交付目录外备份；历史版本按平台/版本保留。

## 2026-11-08 同版本重新签名

17:37（北京时间）使用本机现有有效开发证书，先重签 controls 扩展，再重签主 App，原路径 IPA 已替换。版本0.6.5/build11、资源和权限不变；打包后解包再次通过嵌套签名与ZIP校验。当次仅重签，期限仍为11月13日；随后续期见下方记录。此前真机安装和UI验收针对重签前同版本包；本次新签名包未再次安装手机。旧IPA与旧ZIP备份在交付目录外保留，完整ZIP内的IPA、元数据和校验清单同步更新。

## 2026-11-08 17:43 重新申请描述文件并续期

按Apple官方自动签名缓存刷新步骤，仅备份移出本项目两份旧缓存，再通过xcodebuild -allowProvisioningUpdates获取新描述文件。主App与controls均于11月8日重新签发，有效期延至11月15日17:42（北京时间）。保留原0.6.5/build11的代码、功能和权限，替换描述文件后按扩展→主App顺序重签；原路径IPA与ZIP已更新。打包后解包嵌套签名、描述文件签发/到期、登记设备与资源不变均验证通过。详见validation/ios-0.6.5-renewal.json。本次未在手机覆盖安装；此前手机验收只证明相同版本的功能，不代表手机现有安装已续期。

## 以下为0.6.2发布时的历史记录

下述APK现位于 `archive/packages/android/0.6.2/`。iOS0.6.2后续已在登记iPhone安装并启动成功，本次未重新安装。

整理日期：2026-11-08。当前包与 archive 中历史包分开；安装步骤见项目根目录的离线 HTML 手册。

| 文件 | 版本 | 字节数 |
|---|---|---|
| `releases/android/HyperAudioLab-0.6.2.apk` | Android 0.6.2 / code8 | 509129 |
| `releases/android/debug/HyperAudioLab-0.6.2-debug.apk` | Android 0.6.2 / code8 | 3059057 |
| `releases/ios/HyperAudioReceiver-0.6.2-signed.ipa` | iOS 0.6.2 / build8，包含 controls 扩展 | 1735221 |

## iOS 签名期限与适用范围

现有开发签名仅适用于描述文件包含的设备，不是任意 iPhone 通用安装包。其他用户在 Windows/macOS 用自己的 Apple 账户重签；需要保留主 App 和 controls 扩展。免费签名通常需要每七天续签。
- `lab.hyperaudio.receiver`：有效至 **2026-11-13 13:02:15（北京时间）**。
- `lab.hyperaudio.receiver.controls`：有效至 **2026-11-13 15:00:20（北京时间）**。

本次重建沿用已有有效描述文件，没有把期限延长。0.6.2 已验证构建与嵌套签名，尚未安装到真实手机；此前0.6在登记手机安装并启动成功。Windows重签和第三方工具保留扩展仍需实际验证。

## SHA-256

`releases/android/HyperAudioLab-0.6.2.apk`

```text
e80d1b24bd092cf3b37cdd3f2b27a7f713d2497d5061e7605d1b2f7493e1b867
```

`releases/android/debug/HyperAudioLab-0.6.2-debug.apk`

```text
c3b16fe9e8947106523da9f766dafa5c9bd9ba551769a1cee9fa71026e63e534
```

`releases/ios/HyperAudioReceiver-0.6.2-signed.ipa`

```text
33b048f9aab2a83e36d9bafa8fc9b5d2489a6ff52c8b85b2f2a26251e5a9d1c7
```

## Android 签名

两种构建使用与本机0.3–0.6.1相同的测试证书，可覆盖对应旧版。原Windows0.2可能不同。交付包不包含签名私钥，不保证其他电脑重新构建的 APK 能覆盖。

## 本轮验证

Android既有51项单测通过、Debug及优化Release构建通过；lint 0错误，1项可选主题单色图标告警。默认全彩图标复用iOS原图，系统形状由adaptive icon适配。iOS设备Release与模拟器Debug构建成功；主App、控件版本均为0.6.2/build8，IPA ZIP和嵌套签名校验通过。赞助原JPG两端保持一致，二维码解码内容一致。未执行支付、Windows真机重签、0.6.2手机试听或新增页面运行态验收。
