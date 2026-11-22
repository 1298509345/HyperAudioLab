# HyperAudioLab

Android 媒体声音 → 同一局域网 → Android 或 iPhone 接收 → 耳机或扬声器。

当前版本为 Android 0.7.2 和 iOS 0.7.0。下载与 SHA-256 校验文件见 [Android 0.7.2 发布页](https://github.com/1298509345/HyperAudioLab/releases/tag/android-0.7.2) 和 [iOS 0.7.0 发布页](https://github.com/1298509345/HyperAudioLab/releases/tag/ios-0.7.0)。

打开 [安装操作手册.html](安装操作手册.html) 可查看通用安装、配对和故障排查流程。版本和文件名以对应发布页为准。

| 内容 | 位置 |
|---|---|
| Android 双机 | [Android 双机使用说明](docs/guide/Android双机使用.md) |
| 本机续期首次设置 | [SelfRefresh安装与续期](docs/guide/SelfRefresh安装与续期.md) |
| 完整手册 Markdown | [docs/guide/安装操作手册.md](docs/guide/安装操作手册.md) |
| 源码 | `android/`、`ios/`，保留工程结构；辅助脚本在 `tools/` |
| 历史说明／技术资料 | `docs/history/`、`docs/reference/` |

## 本版变化

- iOS 0.7.0 在「设置 → 声音 → 输出方式」新增「跟随系统输出」，可使用 iPhone 扬声器。默认仅蓝牙耳机。耳机断开切到扬声器时，App 保留接收连接并暂停，回 App 点「恢复声音」才继续外放。
- iOS 0.7.0 新增 SideInstaller 版，签名和续期均在 SideInstaller 里完成，保留控制中心接收开关。
- iOS 0.6.9 修正 SelfRefresh 续期时的设备识别，使用 iPhone 的实际 UDID 注册设备并校验描述文件。
- iOS 0.6.7 减少锁屏待机、暂停时的轮询和后台统计刷新。实际耗电仍待真机对比。
- iOS 0.6.8 修复其他 App 打断声音后无法手动恢复。点恢复时重新向系统申请播放，成功后沿用原接收连接。

- 安卓 0.7.2 在停止音频共享时恢复采集前的媒体音量。睡眠定时到期时保持静音。
- 安卓 0.7.1 减少接收待机和暂停时的轮询，沿用现有唤醒锁策略。

- iOS 0.6.6/build 12 集成可选 SelfRefreshKit，设置增加签名维护，支持主 App 与控件扩展同时续期；不自动撤销旧证书，最低 iOS 17。首次需要电脑配对、原开发者账户与回环 VPN；真机续期仍待用户验证。原 0.6.5 包与旧完整 ZIP 保留。

- Android0.7 同一APK新增发送／接收角色，支持两台安卓一对一传音频。接收端无需录音或屏幕共享授权，默认耳机，主动选择才外放；收发互斥。
- Android双机操作见 [使用说明](docs/guide/Android双机使用.md)；Android→iPhone继续使用原连接方式。

- iOS 锁屏媒体卡片禁用停止命令，保留播放／暂停，避免误结束接收会话。
- 两端“设置 → 关于与赞助 → 隐私说明”新增离线分节页面，说明权限、音频传输、本地数据、诊断、清理和赞助。

- iOS 0.6.5 将控制中心升级为持续显示状态的单一接收开关：接收开启时亮起，停止或暂停时熄灭；旧按钮请移除后重新添加，已有快捷指令保持兼容。
- Android 启动图标复用 iOS 蓝紫波形原图，并适配系统图标形状。
- 两端“设置 → 关于与赞助”提供用户指定的支付宝收款码，由使用者主动打开；无支付 SDK 或自动付款。
- 完整离线手册与分类目录；历史包全部按平台、版本保留。

## iOS 安装前先看

iOS 0.7.0 的两个 IPA 都未签名，需要用自己的 Apple 账户签名后安装。主 App 需要 iOS 17+，控制中心扩展需要 iOS 18+，签名时请保留扩展。免费 Apple 账户的描述文件自签发起 7 天到期。

| 下载 | 签名与续期 |
|---|---|
| `HyperAudioReceiver-0.7.0-unsigned.ipa` | 用电脑签名安装。完成配对、账号和回环 VPN 设置后，在 HyperAudio 里验证首次续期。[SelfRefresh 指南](docs/guide/SelfRefresh安装与续期.md) |
| `HyperAudioReceiver-0.7.0-SideInstaller-unsigned.ipa` | 在 SideInstaller 导入并签名安装。保留原始 IPA，到期前用同一账号和开发 Team 在「工具 → 已侧载 App」刷新。 |

Windows 签名安装、SideInstaller 版真机安装与续期仍待验证。手机 CPU、内存、耗电及长期锁屏仍需真机测量。

## 开发与重新打包

Android：JDK17、Android SDK35 / Build Tools35.0.0，在 `android/` 用 `bash ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease`。当前 Release 使用本机测试证书；本包不包含其私钥，换电脑自行构建时证书可能不同，不能假定可覆盖本次 APK。

iOS：在 macOS 用支持目标系统的完整 Xcode 打开 `ios/HyperAudioReceiver.xcodeproj`，两个 target 选择自己的 Team；工程内未固定个人 Team。详见手册第5节。

构建 SideInstaller 版时，向 `xcodebuild` 传入 `'OTHER_SWIFT_FLAGS=$(inherited) -DHYPERAUDIO_SIDEINSTALLER'`。制作未签名 IPA 时同时使用 `CODE_SIGNING_ALLOWED=NO`。

手册 HTML：安装可选文档依赖 `python3 -m pip install -r tools/requirements-docs.txt` 后运行 `python3 tools/render_install_manual.py`。阅读现成 HTML 不需要安装 Python。

本地交付 ZIP 由 `tools/package_delivery.py` 生成，需要先备齐脚本指定的安装包。历史记录可能包含本机设备和路径信息，完整 ZIP 用于受控交付。
