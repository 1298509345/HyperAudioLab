package lab.hyperaudio

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors

data class ReceiverEndpoint(val name: String, val host: String, val port: Int = 50006, val expectedID: String? = null)
data class ConnectionInfo(val host: String, val token: String, val port: Int, val name: String, val network: Network? = null, val receiverID: String? = null, val controlPort: Int = 50006, val supportsStereo: Boolean = false)

/** NSD + remembered address / gateway fallbacks. No subnet scans or public relay. */
class AutoConnect(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor()
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicast: WifiManager.MulticastLock? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var generation = 0
    @Volatile private var closed = false
    private val clientID = prefs.getString("clientID", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("clientID", it).apply()
    }
    fun find(done: (List<ReceiverEndpoint>) -> Unit) {
        cancel()
        val run = generation
        val found = linkedMapOf<String, ReceiverEndpoint>()
        val waiting = ArrayDeque<NsdServiceInfo>()
        var resolving = false
        fun valid() = !closed && run == generation
        lateinit var resolveNext: () -> Unit
        resolveNext = resolve@{
            if (!valid() || resolving || waiting.isEmpty()) return@resolve
            resolving = true
            val service = waiting.removeFirst()
            @Suppress("DEPRECATION")
            nsd.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                    main.post { resolving = false; resolveNext() }
                }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    main.post {
                        if (valid()) {
                            @Suppress("DEPRECATION")
                            val address = if (Build.VERSION.SDK_INT >= 34)
                                info.hostAddresses.firstOrNull { it is java.net.Inet4Address } ?: info.hostAddresses.firstOrNull()
                            else info.host
                            address?.hostAddress?.let { host ->
                                if (info.port in 1..65535) found["$host:${info.port}"] = ReceiverEndpoint(info.serviceName, host, info.port)
                            }
                        }
                        resolving = false; resolveNext()
                    }
                }
            })
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post {
                    if (valid() && found.size < 12 && waiting.size < 12 && info.serviceType.trimEnd('.') == "_hyperaudio._tcp") {
                        waiting.addLast(info); resolveNext()
                    }
                }
            }
        }
        discovery = listener
        runCatching {
            multicast = context.getSystemService(WifiManager::class.java).createMulticastLock("HyperAudio:discovery").apply {
                setReferenceCounted(false); acquire()
            }
            nsd.discoverServices("_hyperaudio._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        }
        main.postDelayed({
            if (!valid()) return@postDelayed
            stopDiscovery()
            if (found.isNotEmpty()) {
                val remembered = prefs.getString("lastID", "") ?: ""
                // Names only select a candidate expected identity; the full ID + HMAC still gate resume.
                val identities = prefs.all.keys.filter { it.startsWith("key:") }.map { it.removePrefix("key:") }
                done(found.values.map { endpoint ->
                    endpoint.copy(expectedID=PairingPolicy.discoveryIdentity(endpoint.name,identities))
                }.sortedBy { when (it.expectedID) { remembered -> 0; null -> 2; else -> 1 } })
            } else {
                // Bonjour can be blocked by a hotspot; only probe saved host / actual gateways.
                val candidates = linkedMapOf<String, ReceiverEndpoint>()
                prefs.getString("lastHost", null)?.let { host ->
                    candidates[host] = ReceiverEndpoint("上次配对的 接收端", host, prefs.getInt("lastPort", 50006), prefs.getString("lastID", null))
                }
                val cm = context.getSystemService(ConnectivityManager::class.java)
                @Suppress("DEPRECATION") // All current networks include Wi-Fi when mobile data is the default.
                val networks = cm.allNetworks
                networks.forEach { network ->
                    val caps = cm.getNetworkCapabilities(network)
                    if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true && caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) != true)
                        return@forEach
                    cm.getLinkProperties(network)?.routes?.filter { it.isDefaultRoute }?.forEach { route ->
                        val host = route.gateway?.hostAddress
                        if (host != null && route.gateway is java.net.Inet4Address && route.gateway!!.isSiteLocalAddress)
                            candidates.putIfAbsent(host, ReceiverEndpoint("热点网关上的接收端", host))
                    }
                }
                io.execute {
                    val reachable = candidates.values.take(6).filter { endpoint ->
                        if (!valid()) false else runCatching {
                            Socket().use { probe ->
                                LanRouting.networkFor(context,endpoint.host)?.bindSocket(probe)
                                probe.connect(InetSocketAddress(endpoint.host, endpoint.port), 700)
                            }; true
                        }.getOrDefault(false)
                    }
                    main.post { if (valid()) done(reachable) }
                }
            }
        }, 4000)
    }
    fun connect(endpoint: ReceiverEndpoint, progress: (String) -> Unit, done: (Result<ConnectionInfo>) -> Unit,
        allowPair: Boolean = true) {
        cancel()
        val run = generation
        fun valid() = !closed && run == generation
        io.execute {
            if (!valid()) return@execute
            val result = runCatching {
                Socket().use { s ->
                    socket = s
                    val timeout = Runnable { runCatching { s.close() } }
                    main.postDelayed(timeout, if (allowPair) 45_000 else 7_000)
                    try {
                        check(valid()) { "连接已取消" }
                        val network = LanRouting.networkFor(context,endpoint.host)
                        network?.bindSocket(s)
                        s.connect(InetSocketAddress(endpoint.host, endpoint.port), 2500)
                        s.soTimeout = if (allowPair) 40000 else 3500
                        val input = s.getInputStream()
                        val output = s.getOutputStream()
                        val challenge = ControlProtocol.challenge(input, output, clientID, endpoint.expectedID, Build.MODEL)
                        val receiverID = challenge.getString("receiverId")
                        val secret = prefs.getString("key:$receiverID", null)
                        check(valid()) { "连接已取消" }
                        if (secret != null) {
                            check(allowPair || PairingPolicy.canResume(receiverID, endpoint.expectedID, true)) { "只允许恢复当前已配对的 接收端" }
                            ControlProtocol.send(output, JSONObject().put("type", "resume").put("proof",
                                PairingProof.sign(secret,clientID,receiverID,challenge.getString("nonce"))))
                            main.post { if (valid()) progress("正在连接已配对的 接收端…") }
                        } else {
                            check(allowPair) { "后台恢复不发起首次配对" }
                            ControlProtocol.send(output, JSONObject().put("type", "pair"))
                            main.post { if (valid()) progress("首次连接：请在 接收端 上点允许（40 秒内）") }
                        }
                        val response = ControlProtocol.read(input)
                        check(response.optString("type") == "ready") {
                            when (response.optString("reason")) {
                                "denied" -> "接收端 拒绝了配对"
                                "pair_required" -> "配对记录已失效；请前台重新配对"
                                "busy" -> "接收端 正在处理另一项配对，请稍后重试"
                                else -> "接收端没有同意连接"
                            }
                        }
                        check(response.getString("receiverId") == receiverID)
                        val token = response.getString("token")
                        check(token.matches(Regex("[0-9a-f]{8}")))
                        val port = response.getInt("port"); check(port == 50005)
                        val edit = prefs.edit()
                        if (response.has("secret")) {
                            check(allowPair && secret == null) { "恢复连接不能更换配对密钥" }
                            val issued = response.getString("secret"); check(issued.matches(Regex("[0-9a-f]{64}")))
                            edit.putString("key:$receiverID", issued)
                        }
                        val name = response.optString("name", endpoint.name).take(64)
                        check(valid()) { "连接已取消" }
                        edit.putString("lastID", receiverID).putString("lastHost", endpoint.host)
                            .putInt("lastPort",endpoint.port).putString("lastName",name).apply()
                        catalog { it.authenticated(receiverID,name,endpoint.host,endpoint.port,System.currentTimeMillis()) }
                        ConnectionInfo(endpoint.host,token,port,name,network,receiverID,endpoint.port,AudioWire.supportsStereo(response))
                    } finally {
                        main.removeCallbacks(timeout)
                        if (socket === s) socket = null
                    }
                }
            }
            main.post { if (valid()) done(result) }
        }
    }
    fun rememberedEndpoint(): ReceiverEndpoint? {
        val id = prefs.getString("lastID", null) ?: return null
        if (!hasKey(id)) return null
        val host = prefs.getString("lastHost", null) ?: return null
        return ReceiverEndpoint(rememberedName() ?: "已配对的 接收端", host, prefs.getInt("lastPort",50006), id)
    }
    fun hasKey(id: String): Boolean = prefs.getString("key:$id",null) != null

    /** Independent instances are used by capture recovery and UI diagnostics. Never pairs. */
    fun inspect(endpoint: ReceiverEndpoint, done: (Result<ReceiverStatus>) -> Unit) {
        cancel()
        val run = generation
        fun valid() = !closed && run == generation
        io.execute {
            if (!valid()) return@execute
            val result = runCatching {
                val id = endpoint.expectedID ?: error("未配对：认证自检未测试")
                val secret = prefs.getString("key:$id",null) ?: error("未配对：认证自检未测试")
                Socket().use { s ->
                    socket = s
                    val timeout = Runnable { runCatching { s.close() } }
                    main.postDelayed(timeout,6000)
                    try {
                        check(valid()) { "自检已取消" }
                        LanRouting.networkFor(context,endpoint.host)?.bindSocket(s)
                        s.connect(InetSocketAddress(endpoint.host,endpoint.port),2000)
                        s.soTimeout = 2500
                        ControlProtocol.inspect(s.getInputStream(),s.getOutputStream(),clientID,id,secret,Build.MODEL)
                    } finally {
                        main.removeCallbacks(timeout)
                        if (socket === s) socket = null
                    }
                }
            }
            main.post { if (valid()) done(result) }
        }
    }
    /** Resume only this session's identity, trying the saved address before discovery. */
    fun recover(id: String, done: (Result<ConnectionInfo>) -> Unit) {
        if (!hasKey(id)) { done(Result.failure(IllegalStateException("配对密钥已移除；需前台重新配对"))); return }
        fun discover() {
            find { candidates ->
                val queue = ArrayDeque(candidates.map { it.copy(expectedID = id) }.take(6))
                fun next() {
                    if (queue.isEmpty()) { done(Result.failure(IllegalStateException("未找到已配对的 接收端"))); return }
                    connect(queue.removeFirst(), {}, { result ->
                        if (result.isSuccess) done(result) else next()
                    }, allowPair = false)
                }
                next()
            }
        }
        val saved = rememberedEndpoint()?.takeIf { it.expectedID == id }
        if (saved == null) discover()
        else connect(saved, {}, { result -> if (result.isSuccess) done(result) else discover() }, allowPair = false)
    }
    private fun <T> catalog(action: (PairingCatalog) -> T): T = synchronized(pairingLock) {
        val before = prefs.all.toMap()
        val data = before.toMutableMap()
        val result = action(PairingCatalog(data))
        val edit = prefs.edit()
        (before.keys - data.keys).forEach { edit.remove(it) }
        data.forEach { (key,value) -> if (before[key] != value) when (value) {
            is String -> edit.putString(key,value)
            is Int -> edit.putInt(key,value)
            is Long -> edit.putLong(key,value)
        } }
        edit.apply()
        result
    }
    fun devices(): List<PairedDevice> = catalog { it.devices() }
    fun rename(id: String, alias: String) { catalog { it.rename(id,alias) } }
    fun rememberedName(): String? = devices().firstOrNull { it.id == prefs.getString("lastID",null) }?.displayName
    fun forget(id: String): Boolean {
        val snapshot=LabState.snapshot
        if (snapshot.running && snapshot.endpoint?.expectedID == id) return false
        return catalog { it.forget(id, if (snapshot.running) snapshot.endpoint?.expectedID else null) }
    }
    fun forget(): Boolean {
        if (LabState.running) return false
        cancel()
        return catalog { it.forgetAll(null) }
    }
    companion object { private val pairingLock = Any() }
    private fun stopDiscovery() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }; discovery = null
        multicast?.let { if (it.isHeld) it.release() }; multicast = null
    }
    fun cancel() { generation++; stopDiscovery(); runCatching { socket?.close() }; socket = null }
    fun close() { cancel(); closed = true; io.shutdownNow() }
}

