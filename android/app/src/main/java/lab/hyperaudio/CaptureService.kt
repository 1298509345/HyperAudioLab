package lab.hyperaudio

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.*
import android.net.Network
import android.os.*
import android.service.quicksettings.TileService
import android.content.ComponentName
import android.util.Log
import java.io.File
import java.net.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.*

class CaptureService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private var session: Session? = null
    private class Session(val id: Long, val info: ConnectionInfo, val mode: Int, val save: Boolean, val auto: AutoConnect, val inspector: AutoConnect) {
        val live = AtomicBoolean(true)
        val paused = AtomicBoolean(false)
        val target = AtomicReference(info)
        val begin = SystemClock.elapsedRealtime()
        val health = ReceiverHealth()
        val sleepTimer = SessionSleepTimer()
        var channels = 1
        var formatStatus = "单声道 HAP1 · 10ms"
        var lastNotificationAt = Long.MIN_VALUE
        val backoff = RecoveryBackoff()
        var projection: MediaProjection? = null
        var callback: MediaProjection.Callback? = null
        var record: AudioRecord? = null
        var worker: Thread? = null
        @Volatile var udp: DatagramSocket? = null
        var lease: CaptureWakeLease? = null
        @Volatile var recovering = false
        @Volatile var exhausted = false
        var inspectSupported = true
        var healthPoller: HealthPoller? = null
        var lastCheckedSent = 0L
        @Volatile var sent = 0L
        @Volatile var networkStatus = "正在发送"
    }
    private fun active(s: Session) = s.live.get() && LabState.owns(s.id)
    private fun external(d: AudioDeviceInfo) = d.isSink && d.type in setOf(
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET)
    private fun mute() { audio.setStreamVolume(AudioManager.STREAM_MUSIC,0,0) }
    private fun streamMute() {
        if (!audio.isStreamMute(AudioManager.STREAM_MUSIC))
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_MUTE,0)
    }
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            val s = session ?: return
            if (s.mode == 1 && removed.any { external(it) }) {
                runCatching { mute() }
                finish(s,"外接音频设备断开；已请求媒体音量归零，切换瞬间仍需真机测试",CaptureError.CAPTURE_FAILED)
            }
        }
    }
    private val guard = object : Runnable {
        override fun run() {
            val s = session ?: return
            if (!active(s)) return
            if (s.sleepTimer.expired(s.id,SystemClock.elapsedRealtime())) {
                finish(s,"睡眠定时到期；已停止本机采集，媒体音量不自动恢复",reason=CaptureEndReason.SLEEP_EXPIRED); return
            }
            try {
                if (s.mode != 1) {
                    if (s.mode == 2) streamMute() else mute()
                    check(if (s.mode == 2) audio.isStreamMute(AudioManager.STREAM_MUSIC)
                        else audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) { "媒体音量无法归零" }
                } else if (!audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { external(it) }) {
                    mute(); error("未检测到外接音频设备")
                }
                s.lease?.refresh(SystemClock.elapsedRealtime())
            } catch (e: Exception) { finish(s,"静音/唤醒控制失败: ${e.message}",CaptureError.CAPTURE_FAILED); return }
            main.postDelayed(this,250)
        }
    }
    private fun notification(s: Session): Notification {
        fun action(code: Int, name: String, command: String) = Notification.Action.Builder(null,name,
            PendingIntent.getService(this,code,Intent(this,CaptureService::class.java).setAction(command),PendingIntent.FLAG_IMMUTABLE)).build()
        val open = PendingIntent.getActivity(this,2,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val remaining = s.sleepTimer.remainingMS(s.id,SystemClock.elapsedRealtime())
        val state = (if (s.paused.get()) "已暂停发送（授权保留）" else s.networkStatus) +
            " · ${s.formatStatus}" + (remaining?.let { " · 定时剩余 ${(it+999)/1000}秒" } ?: "")
        return Notification.Builder(this,"capture").setContentTitle("HyperAudio · ${s.target.get().name}")
            .setContentText(state).setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).setContentIntent(open)
            .setWhen(System.currentTimeMillis() - (SystemClock.elapsedRealtime()-s.begin)).setUsesChronometer(true)
            .addAction(action(3,if (s.paused.get()) "恢复发送" else "暂停发送","TOGGLE_PAUSE"))
            .addAction(action(1,"停止","STOP")).build()
    }
    private fun refreshNotification(s: Session) {
        val now = SystemClock.elapsedRealtime()
        if (active(s) && (s.lastNotificationAt == Long.MIN_VALUE || now-s.lastNotificationAt >= 1000)) {
            s.lastNotificationAt=now
            getSystemService(NotificationManager::class.java).notify(1,notification(s))
        }
    }
    override fun onBind(i: Intent?) = null
    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i?.action == "SET_SLEEP") {
            session?.let { s ->
                if (active(s) && i.getLongExtra("sessionID",0) == s.id) {
                    val minutes = i.getIntExtra("sleepMinutes",0)
                    if (minutes in listOf(0,15,30,60,120)) {
                        s.sleepTimer.update(s.id,minutes,SystemClock.elapsedRealtime())
                        LabState.update(s.id) { it.copy(sleepRemainingMS=s.sleepTimer.remainingMS(s.id,SystemClock.elapsedRealtime())) }
                        refreshNotification(s)
                    }
                }
            }
            if (session == null) stopSelf()
            return START_NOT_STICKY
        }
        if (i?.action == "STOP") {
            session?.let { finish(it,"用户停止；媒体音量不自动恢复") } ?: stopSelf()
            return START_NOT_STICKY
        }
        if (i?.action == "TOGGLE_PAUSE") {
            session?.let { s ->
                if (active(s)) {
                    s.paused.set(!s.paused.get())
                    s.health.resetObservation()
                    LabState.update(s.id) { it.copy(paused=s.paused.get()) }
                    refreshNotification(s)
                }
            }
            if (session == null) stopSelf()
            return START_NOT_STICKY
        }
        if (i == null || session != null) return START_NOT_STICKY
        if (AppRole.current(this) != AudioRole.SEND || !AppRole.claim(AudioRole.SEND,i.getLongExtra("roleEpoch",-1))) {
            stopSelf(); return START_NOT_STICKY
        }
        audio = getSystemService(AudioManager::class.java)
        val info = ConnectionInfo(i.getStringExtra("host") ?: "",i.getStringExtra("token") ?: "",50005,
            i.getStringExtra("name") ?: "接收端",getNetwork(i),i.getStringExtra("receiverID"),i.getIntExtra("controlPort",50006),i.getBooleanExtra("supportsStereo",false))
        val s = Session(LabState.begin(info),info,i.getIntExtra("mode",0),i.getBooleanExtra("save",false),AutoConnect(applicationContext),AutoConnect(applicationContext))
        session = s
        try {
            check(info.token.matches(Regex("[0-9a-f]{8}")) && info.host.isNotBlank()) { "无效接收目标" }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                throw SecurityException("录音权限已撤销，请回到界面重新授权")
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("capture","音频采集实验",NotificationManager.IMPORTANCE_LOW))
            startForeground(1,notification(s),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            s.lastNotificationAt=SystemClock.elapsedRealtime()
            if (s.mode != 1) {
                check(!audio.isVolumeFixed) { "设备采用固定音量策略" }
                if (s.mode == 2) { streamMute(); check(audio.isStreamMute(AudioManager.STREAM_MUSIC)) { "媒体静音失败" } }
                else { mute(); check(audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) { "媒体音量没有归零" } }
            } else check(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { external(it) }) { "先接 USB DAC/有线静音负载" }
            @Suppress("DEPRECATION")
            val consent = i.getParcelableExtra<Intent>("consent") ?: error("缺少本次授权")
            val projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(i.getIntExtra("code",0),consent)
            s.projection = projection
            s.callback = object : MediaProjection.Callback() {
                override fun onStop() { finish(s,"系统撤销采集（可能锁屏/用户终止）；需重新授权",CaptureError.AUTHORIZATION_REVOKED) }
            }.also { projection.registerCallback(it,main) }
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
            fun buildRecord(channels: Int): AudioRecord {
                val mask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
                val min = AudioRecord.getMinBufferSize(48000,mask,AudioFormat.ENCODING_PCM_16BIT)
                check(min > 0) { "48kHz ${channels}声道不支持: $min" }
                val record = AudioRecord.Builder().setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setChannelMask(mask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(max(min*2,9600)).build()
                if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); error("AudioRecord 初始化失败") }
                return record
            }
            val preferStereo = i.getBooleanExtra("preferStereo",true)
            if (preferStereo && info.supportsStereo) {
                s.record = runCatching { buildRecord(2) }.getOrNull()
                s.channels=AudioWire.channels(preferStereo,info.supportsStereo,s.record != null)
                if (s.channels == 2) s.formatStatus="立体声 HAP2 · 5ms"
                else s.formatStatus="立体声采集初始化失败；已回退单声道 HAP1 · 10ms"
            } else s.formatStatus = if (preferStereo) "收端未协商立体声；已回退单声道 HAP1 · 10ms" else "单声道 HAP1 · 10ms"
            if (s.record == null) s.record=buildRecord(1)
            val sleepMinutes = i.getIntExtra("sleepMinutes",0).takeIf { it in listOf(0,15,30,60,120) } ?: 0
            s.sleepTimer.update(s.id,sleepMinutes,SystemClock.elapsedRealtime())
            LabState.update(s.id) { it.copy(mode=s.mode,channels=s.channels,connection=s.formatStatus,text=s.formatStatus,
                sleepRemainingMS=s.sleepTimer.remainingMS(s.id,SystemClock.elapsedRealtime())) }
            audio.registerAudioDeviceCallback(devices,main)
            val lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"HyperAudio:capture")
                .apply { setReferenceCounted(false) }
            s.lease = CaptureWakeLease({ timeout -> lock.acquire(timeout) }, { if (lock.isHeld) lock.release() })
                .also { it.refresh(SystemClock.elapsedRealtime()) }
            main.post(guard)
            s.worker = Thread({ capture(s) },"PlaybackCapture-${s.id}").also { it.start() }
            TileService.requestListeningState(this,ComponentName(this,CaptureTileService::class.java))
            s.healthPoller = HealthPoller(
                { task, delay -> main.postDelayed(task,delay) }, { task -> main.removeCallbacks(task) }
            ) { inspect(s) }.also { it.start() }
        } catch (e: Exception) { finish(s,"启动失败: ${e.javaClass.simpleName}: ${e.message}",if(e is SecurityException) CaptureError.AUTHORIZATION_REVOKED else CaptureError.CAPTURE_FAILED) }
        return START_NOT_STICKY
    }
    @Suppress("DEPRECATION") private fun getNetwork(i: Intent): Network? = i.getParcelableExtra("network")

    private fun inspect(s: Session) {
        if (!active(s) || !s.inspectSupported) return
        val info = s.target.get()
        val id = info.receiverID
        if (id == null || !s.auto.hasKey(id)) return // Manual token mode has no authenticated diagnostics.
        if (s.recovering) return
        // Separate socket/generation: recovery cancellation cannot consume a health-check completion.
        // Each inspect has a 6 s total timeout, below the 10 s cadence; no accumulating IO requests.
        s.inspector.inspect(ReceiverEndpoint(info.name,info.host,info.controlPort,id)) { result ->
            if (!active(s)) return@inspect
            result.onSuccess { status ->
                LabState.update(s.id) { it.copy(receiver=status, inspectMessage="认证状态已更新") }
                val stalled = s.health.observe(status.received,SystemClock.elapsedRealtime(),
                    s.paused.get() || status.paused,s.sent > s.lastCheckedSent)
                s.lastCheckedSent = s.sent
                if (stalled || (!status.running && !s.paused.get())) requestRecovery(s)
            }.onFailure { error ->
                LabState.update(s.id) { it.copy(inspectMessage=error.message ?: "认证状态不可用") }
                if (error is InspectUnavailable) {
                    s.health.unsupported(); s.inspectSupported = false; s.healthPoller?.close()
                    s.networkStatus = "收端认证自检不可用；请确认配对并升级至 0.4"
                    refreshNotification(s)
                } else if (s.health.failure(s.paused.get())) requestRecovery(s)
            }
        }
    }
    private fun requestRecovery(s: Session) {
        if (!active(s) || s.recovering || s.exhausted) return
        s.recovering = true
        s.networkStatus = "网络恢复中；采集授权保留"
        LabState.update(s.id) { it.copy(errorCode=CaptureError.NETWORK_UNAVAILABLE) }
        refreshNotification(s)
        fun attempt() {
            if (!active(s)) return
            val delay = s.backoff.nextDelay()
            if (delay == null) {
                s.recovering = false; s.exhausted = true
                s.networkStatus = "自动恢复已暂停；请检查网络后停止并重新连接"
                LabState.update(s.id) { it.copy(connection=s.networkStatus) }
                refreshNotification(s); return
            }
            main.postDelayed({
                if (!active(s)) return@postDelayed
                val info = s.target.get()
                fun accept(updated: ConnectionInfo) {
                    if (!active(s)) return
                    if (!AudioWire.canResume(s.channels,updated.supportsStereo)) {
                        finish(s,"收端恢复后不再支持立体声；已停止，请选择单声道重新连接",CaptureError.CAPTURE_FAILED); return
                    }
                    s.target.set(updated); s.backoff.reset(); s.recovering=false
                    s.networkStatus="正在发送（网络已恢复）"
                    LabState.update(s.id) { it.copy(errorCode=CaptureError.NONE,connection=s.networkStatus,endpoint=ReceiverEndpoint(updated.name,updated.host,updated.controlPort,updated.receiverID)) }
                    refreshNotification(s)
                }
                val id = info.receiverID
                if (id == null) {
                    // No pairing key: recreate exactly the same UDP target, without any control pairing.
                    s.target.set(info.copy(network=null)) // Capture worker owns the socket handoff; avoid a spurious send failure.
                    s.recovering=false; s.networkStatus="已重建原 UDP 目标；等待发送"
                    LabState.update(s.id) { it.copy(errorCode=CaptureError.NONE,connection=s.networkStatus) }
                    refreshNotification(s)
                } else s.auto.recover(id) { result ->
                    if (!active(s)) return@recover
                    result.onSuccess { accept(it) }.onFailure { attempt() }
                }
            },delay)
        }
        attempt()
    }
    private fun capture(s: Session) {
        var csv: MetricsLog? = null
        var pcm: PcmArchive? = null
        val channelName = if (s.channels == 2) "stereo" else "mono"
        val quota = AudioWire.archiveBytes(s.channels)
        var archiveStatus = if (s.save) "保存前 5 分钟 $channelName PCM（${quota/1_000_000.0}MB）" else "未保存 PCM"
        val record = s.record ?: return
        var outgoing: DatagramSocket? = null
        var target: ConnectionInfo? = null
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val folder = File(getExternalFilesDir(null),"session-${System.currentTimeMillis()}-${s.id}")
            val logSessionID=s.id // Callback retains only this value, never Service/Session.
            csv = MetricsLog(folder,"# ${Build.MANUFACTURER},${Build.MODEL},${Build.DISPLAY},Android ${Build.VERSION.RELEASE}; mode=${s.mode}; channels=${s.channels}\n"+
                "elapsed_ms,packets,sent,paused,dbfs,peak,media_volume,media_muted,output_device_types",
                onClosed={ final -> LabState.completeLog(logSessionID,final) })
            if (s.save) folder.mkdirs()
            if (s.save) pcm = PcmArchive(File(folder,"capture-48k-$channelName-s16le-5min-${quota/1_000_000.0}MB.pcm").outputStream(),quota)
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            val samples = ShortArray(AudioWire.SAMPLE_COUNT)
            val encoder = PacketEncoder(s.channels)
            val datagram = DatagramPacket(ByteArray(AudioWire.PACKET_BYTES),AudioWire.PACKET_BYTES)
            var seq = 0L
            var metricAt = s.begin
            var socketRetryAt = 0L
            var stableSince = 0L
            var acknowledgedStable = false
            var sum = 0.0; var count = 0; var peak = 0
            while (active(s)) {
                var filled = 0
                while (filled < 480 && active(s)) {
                    val n = record.read(samples,filled,480-filled,AudioRecord.READ_BLOCKING)
                    check(n > 0) { "AudioRecord.read 错误 $n" }
                    filled += n
                }
                if (!active(s)) break
                val now = SystemClock.elapsedRealtime()
                val current = s.target.get()
                if (target !== current || outgoing?.isClosed == true) {
                    outgoing?.close(); outgoing=null; target=current
                }
                if (outgoing == null && !s.recovering && !s.exhausted && now >= socketRetryAt) {
                    try {
                        outgoing = DatagramSocket().also { udp ->
                            try {
                                (current.network ?: LanRouting.networkFor(this,current.host))?.bindSocket(udp)
                                udp.connect(InetAddress.getByName(current.host),current.port)
                            } catch (e: Exception) { udp.close(); throw e }
                        }
                        s.udp = outgoing
                    } catch (_: Exception) { socketRetryAt=now+2000; main.post { requestRecovery(s) } }
                }
                samples.forEach { v -> sum += v.toDouble()*v; count++; peak=max(peak,abs(v.toInt())) }
                val packet = encoder.encode(samples,s.paused.get(),seq,SystemClock.elapsedRealtimeNanos(),current.token)
                seq++
                if (pcm?.write(packet,AudioWire.HEADER_BYTES,960) == false) { pcm=null; archiveStatus="已保存前 5 分钟 $channelName PCM（${quota/1_000_000.0}MB）；音频转发继续" }
                try {
                    datagram.setData(packet,0,AudioWire.PACKET_BYTES)
                    outgoing?.send(datagram)
                    if (outgoing != null) {
                        s.sent++
                        if (stableSince == 0L) stableSince=now
                        if (current.receiverID == null && !acknowledgedStable && now-stableSince >= 5000) {
                            acknowledgedStable=true
                            main.post {
                                if (active(s)) { s.backoff.reset(); s.networkStatus="正在发送（手动目标，未认证收包）" }
                            }
                        }
                    }
                } catch (_: Exception) {
                    outgoing?.close(); outgoing=null; s.udp=null; socketRetryAt=now+2000
                    stableSince=0; acknowledgedStable=false
                    main.post { requestRecovery(s) }
                }
                if (now-metricAt >= 1000) {
                    val db = 20*log10(max(sqrt(sum/max(1,count))/32768,1e-6))
                    val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val muted = audio.isStreamMute(AudioManager.STREAM_MUSIC)
                    val output = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).joinToString("|") { it.type.toString() }
                    csv.line("${now-s.begin},$seq,${s.sent},${s.paused.get()},$db,$peak,$volume,$muted,$output")
                    LabState.update(s.id) { it.copy(paused=s.paused.get(),packets=seq,sent=s.sent,db=db,elapsedMS=now-s.begin,metricAt=now,
                        mode=s.mode,connection=s.networkStatus,sleepRemainingMS=s.sleepTimer.remainingMS(s.id,now),
                        text=s.formatStatus,volume=volume,muted=muted,outputTypes=output,
                        archiveStatus=archiveStatus,logDirectory=folder.absolutePath,
                        loggingDropped=csv.droppedLines,loggingFailed=csv.failed) }
                    main.post { refreshNotification(s) }
                    sum=0.0; count=0; peak=0; metricAt=now
                }
            }
        } catch (e: Exception) {
            if (active(s)) { Log.e("HyperAudio","capture stopped",e); main.post { finish(s,"采集失败: ${e.javaClass.simpleName}: ${e.message}",CaptureError.CAPTURE_FAILED) } }
        } finally {
            runCatching { record.stop() }; runCatching { record.release() }
            outgoing?.close()
            runCatching { pcm?.close() }; runCatching { csv?.close() }
        }
    }
    private fun finish(s: Session, message: String, errorCode: CaptureError = CaptureError.NONE,
        reason: CaptureEndReason = when(errorCode) {
            CaptureError.AUTHORIZATION_REVOKED -> CaptureEndReason.AUTHORIZATION_REVOKED
            CaptureError.CAPTURE_FAILED -> CaptureEndReason.CAPTURE_FAILED
            else -> CaptureEndReason.USER_STOPPED
        }) {
        if (session !== s || !LabState.owns(s.id)) return
        LabState.end(s.id,message,errorCode,reason)
        stopSession(s)
        session=null
        stopSelf()
    }
    private fun stopSession(s: Session) {
        s.live.set(false)
        s.sleepTimer.cancel(s.id)
        s.healthPoller?.close(); s.healthPoller=null
        s.inspector.close()
        s.auto.close()
        main.removeCallbacks(guard)
        if (::audio.isInitialized) audio.unregisterAudioDeviceCallback(devices)
        runCatching { s.record?.stop() }; runCatching { s.udp?.close() }
        if (s.worker == null) runCatching { s.record?.release() }
        s.callback?.let { s.projection?.unregisterCallback(it) }
        runCatching { s.projection?.stop() }; s.projection=null
        s.lease?.close(); s.lease=null
        AppRole.release(AudioRole.SEND)
        TileService.requestListeningState(this,ComponentName(this,CaptureTileService::class.java))
    }
    override fun onDestroy() {
        session?.let { s -> LabState.end(s.id,"已停止；媒体音量不自动恢复"); stopSession(s) }
        session=null
        main.removeCallbacksAndMessages(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
