# iOS SelfRefresh 安装与续期

适用于 HyperAudio Receiver 0.6.9（构建 15）。主 App 需 iOS 17+，控制中心开关需 iOS 18+。

## 1. 安装

下载 `HyperAudioReceiver-0.6.9-unsigned.ipa`，先在自己的 Windows 或 macOS 电脑上用自己的 Apple 账户签名安装。主 App 和 `HyperAudioControls.appex` 都需要签名。安装步骤见 [安装操作手册](安装操作手册.md)。[iOS 0.6.9 下载](https://github.com/1298509345/HyperAudioLab/releases/tag/ios-0.6.9)

## 2. 首次设置

1. 打开 App → 设置 → 签名与维护 → 进入维护页；也可先读“首次设置与续期说明”。
2. 从电脑生成并导入本机配对文件。iOS 17.4+ 需要包含 RPPairing 的记录，不可直接拿旧的 Xcode 无线配对记录代替。可参考上游 [SelfRefreshSetup](https://github.com/Cc-Cece/SelfRefreshKit/tree/master/tools/SelfRefreshSetup) 导出配对文件的功能；Windows 工具的端到端安装/注入在本项目尚未实测。也可使用兼容系统版本的配对工具导出后，从 App 的文件选择器导入。
3. iPhone 安装并连接 [LocalDevVPN](https://apps.apple.com/app/localdevvpn/id6755608044)，首次按系统提示允许添加 VPN 配置。也可以使用配置了兼容本机回环隧道的 StosVPN 或 WireGuard。配对文件必须来自这台 iPhone。
4. 在维护页由你自行输入**原签名使用的 Apple 账户**、密码和验证码，并确认正确的开发 Team。Anisette 服务用于账户验证辅助数据，可以在页面选择端点。不要把凭据或配对文件发给其他人。
5. 停止音频接收，连接回环 VPN，在“必要设置”第三步点 **验证现有签名**。本地适配会沿用原证书，下载主 App 和全部已嵌入扩展的描述文件；全部预检通过才安装。首次不强制导入整包重签。
6. 查看明确成功结果和有效期；以后覆盖安装其他版本后也先手动续期一次，上游历史缓存不能独立证明新版所有扩展已经续期。完成后断开回环 VPN，再恢复双机听音。App 不会替你停止现有音频。

如果电脑工具提示不能写入 App 文档目录，可以先导出配对文件，再在维护页手动导入。本版没有为了注入而开放整个 Documents 文件共享。

## 3. 日常与自动化

手动续期成功后，在 iPhone 上新建快捷指令，依次添加「连接 LocalDevVPN → 刷新应用签名描述文件（HyperAudio）→ 断开 LocalDevVPN」。英文界面的续期动作名是 **Refresh App Provisioning Profile**。手动运行成功后，在「快捷指令 → 自动化」设置定时运行这条快捷指令，并选择「立即运行」。

续期在 iPhone 上执行。iOS 后台调度、VPN 未连接、网络异常或 Apple 要求再次认证时，自动化可能失败。请在描述文件到期前检查维护页显示的到期时间。

## 4. 错误处理

- 配对无效或没有回环连接：更新本机 RPPairing、连接回环 VPN后重试。
- 账户/Team/Bundle ID/证书不匹配：确认原签名账户；需要更换签名身份时用电脑重新签名安装，不会自动撤销旧证书。
- 证书额度不足：本 App 只报错；请自己在电脑确认已有证书用途后处理。
- 任一扩展下载或预检失败：不会安装任何新描述文件。
- 中途安装失败：不会显示整体成功；此前已经安装的部分无法自动回滚。修复原因后再刷新全部目标。
- App 或开发证书已经到期：可能无法启动自刷新，回到电脑安装路线。

## 5. 本轮验证边界

0.6.9 已验证签名包覆盖安装及配对文件保留。真机的手机续期测试停在回环隧道连接步骤，Apple 描述文件安装与后台自动续期仍待验证。

第三方源码、许可证与改动说明见 `ios/Vendor/SelfRefreshKit/`；详细数据处理见 App 内离线隐私页。
