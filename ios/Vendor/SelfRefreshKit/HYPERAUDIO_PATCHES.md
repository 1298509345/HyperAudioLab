# HyperAudio 集成记录

上游：https://github.com/Cc-Cece/SelfRefreshKit
提交：`1c1bbe05e4ba41bfff05520573b5682f06d3b4f3`（源码内版本号 0.3.0，并非对应发布标签）。许可证：MIT，见 LICENSE。

本地修改仅用于 HyperAudio 的签名维护：

- 日常续期枚举已安装主 App 与 PlugIns/*.appex，下载并核对全部描述文件后安装；校验 Bundle ID、Team、设备、期限及原嵌入描述文件的证书集合覆盖。失败不报告整体成功；显示最早到期时间。
- 日常续期不调用 ensureKeyStore，不创建或撤销证书。完整重签遇证书额度不足只报错，移除自动撤销旧证书分支。
- 配置增加 requiresCertificateTakeover（默认 true，保留上游默认设置流程）。HyperAudio 设为 false，首次设置第三步改为沿用原证书完成一次安全续期验证，成功后可日常刷新；不强制导入 IPA 重签。
- 首次到期摘要取主 App/扩展最早期限；免接管模式的健康检查依据首次续期验证状态。
- iOS 0.6.9 的 RPPairing 续期从主 App 与扩展的现有描述文件确认唯一设备。涉及多台设备时，通过配对连接读取实际设备 UDID。注册设备与核对新描述文件都使用同一 UDID。
- 本地安全流程测试见 tools/test_self_refresh.py；原上游测试仍保留。

依赖沿用上游清单，由主工程 Package.resolved 锁定实际提交。构建使用 -disableAutomaticPackageResolution，不将 branch 名称视为可任意升级的授权。SideSign 与 AnisetteKit 交叉引用同一 branch，SwiftPM 不支持在此图中同时改成 revision 要求，因此使用统一 branch 要求和精确锁文件。二进制依赖保留上游 URL 与 checksum。

不自动配置 OTA 发布源，不自动登录账户。首次配对与账户验证由用户操作；编译、离线测试和签名校验不等于真机续期成功。
