# 自动连接协议 HAC1

## 0.5 格式公告和立体声

ready响应增加 `audioFormats:["HAP1","HAP2"]`。没有此字段的旧收端只按HAP1处理；Android只在自动连接已协商且设备初始化成功时选立体声，否则提示并回退单声道。手动token路径保守使用HAP1。恢复当前立体声会话时，如果新收端能力不再支持HAP2，则停止并要求单声道重新连接。

| 格式 | magic | PCM载荷 | 每包时长 | 总长 |
|---|---|---|---|---|
| 单声道 | HAP1 | 480帧 × 1声道 × 16bit | 10ms | 984字节 |
| 立体声 | HAP2 | 240帧 × 2声道 × 16bit，LR交错 | 5ms | 984字节 |

两者均48kHz，24字节头的序号/时间/token位置和端序相同，PCM仍为小端。HAP2只通过magic区分，不发送1944字节大包。旧HAP1工具与收端路径保留。inspect可返回可选channels/frameDurationMS；不影响鉴权或只读语义。

## 0.4 兼容扩展：只读自检

两端 0.4 新增 `inspect`。仍先完成 hello/challenge，随后发送：

```json
{"type":"inspect","proof":"<按现有HAC1挑战计算的HMAC>"}
```

只允许已配对 clientId 和有效证明；失败返回 `pair_required`，不会触发首次配对。成功响应示例：

```json
{"type":"status","v":1,"receiverId":"<UUID>","name":"HyperAudio-<前缀>","running":true,"listening":true,"paused":false,"bluetooth":true,"received":1234,"lastPacketAgeMs":15,"queuedMS":60,"signalPeak":123,"outputVolume":0.2}
```

`running` 表示接收会话仍有效，`listening` 表示 UDP 监听已就绪；`paused` 表示输出暂停。`lastPacketAgeMs=-1` 表示从未收到有效包，其余为单调时间计算的最近包间隔。`queuedMS` 只表示播放器软件排队，不是端到端延迟。`listening/signalPeak/outputVolume/dropped/concealed` 为可选诊断字段，客户端须兼容缺省。

inspect 不调用 issueToken，不替换 UDP peer，不改变正在播放的缓冲，不返回 token、secret 或配对表；响应采用字段白名单。两端原有 resume 首配/续连路径与 HAP1 不变。旧收端不识别 inspect 时，Android 提示升级并停用健康轮询，不因该错误循环重连。自检和自动恢复使用独立连接生命周期，避免取消恢复连接时意外取消后续健康轮询。

此扩展沿用原来只面向可信局域网的明文 HAC1 安全范围，HMAC 不等于通道加密。软件自检不能证明耳机实际可听到声音。

0.2 保留 HAP1 音频包格式，新增独立 TCP 控制通道。iOS 在开始接收后监听 TCP 50006，并通过系统 Bonjour 发布 `_hyperaudio._tcp`。公布的是服务名称和地址，不公布音频 token。

## 首次配对

1. Android 发送一行 JSON：`{"type":"hello","v":1,"clientId":"<持久UUID>","name":"<手机型号>"}`。
2. iOS 返回 `challenge`，包含版本、持久 receiverId、显示名称和本连接随机 nonce。
3. Android 没有该 receiverId 对应的密钥时，发送 `{"type":"pair"}`。
4. iOS 显示允许/拒绝。允许后生成随机 256-bit 密钥，在应用私有偏好存储记住 clientId 与密钥。
5. iOS 清旧音频连接、生成新音频 token，返回 `ready`，包含 receiverId、name、port=50005、8 位 token 和本次新发放的 64 位十六进制 secret。Android 保存 secret 及最后地址。

## 后续连接

完成 hello / challenge 后，Android 发送 `resume`，proof 为：

```text
hex(HMAC-SHA256(hexDecode(secret), UTF8("HAC1|" + clientId + "|" + receiverId + "|" + nonce)))
```

iOS 用 CryptoKit 验证证明，再发放新的音频 token。续连不再次传 secret。Android 使用新的 token 建立音频采集会话，仍需 Android 系统授权。iOS 在开始新自动会话时清理旧 UDP peer、序号和缓冲，避免旧音频包串入。

## 边界与错误

- 每条消息是单个 JSON 对象，以 LF 结尾，最长 4096 字节。TCP 分段由双方缓冲处理。
- iOS 最多保留 4 条控制连接，同时只展示 1 个配对请求；总连接期限 45 秒。Android 连接等待 2.5 秒，控制读空闲超时 40 秒；用户可取消关闭 Socket。
- 错误返回 `{"type":"error","reason":"denied|busy|pair_required"}`。错误、取消和过期均不能启动采集；手动备选入口始终保留。
- Android NSD 按顺序解析，约 4 秒收集结果；最多保存 12 个发现结果。发现失败时只尝试已保存地址和实际 Wi-Fi/以太网默认网关，不扫描整个网段。
- Android 控制和音频 Socket 优先绑定匹配接收端地址的非默认局域网路由；不会更改全应用默认网络。热点主机未暴露普通 Wi-Fi Network 时会保留系统默认路由，需要真机验证。
- 多个接收端需要用户选择；匹配上次名称时优先选择，真正的密钥记录按 receiverId 区分。设备名称可被伪造，不能充当身份凭证。

## 安全范围

本版用于可信局域网可行性实验。首次密钥交换、控制响应中的音频 token 和音频均未加密，不能对抗窃听或主动中间人。HMAC 只验证本次挑战中密钥的持有，不使整条链路成为安全传输。

密钥位于 Android 应用私有 SharedPreferences / iOS UserDefaults，未实现硬件密钥库。不要公开探针状态文件；日常仅允许自己刚发起的首次配对。清除应用数据或忘记配对后需要再次允许。

## 独立校验向量

```text
key = 000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f
clientId = 00112233-4455-6677-8899-aabbccddeeff
receiverId = ffeeddcc-bbaa-9988-7766-554433221100
nonce = test-nonce-01
proof = b664d32f0d3c8ff7b9605f7d7f6b9f1d342ad67985eda8ba4f4910e7285cac9f
```

Kotlin 生产签名函数与 Python 独立实现均通过该向量。2026-11-06，Swift 代码已通过 Xcode 27.2 构建；生产 ControlServer 的首配、HMAC 续连和换码通过 macOS 真实 TCP 测试。Android 与 iPhone 的基础配对和播放链路由用户真机确认通过；真机续连、超时和拒绝等异常场景仍待专项验证。
