package lab.hyperaudio

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.*
import android.service.quicksettings.TileService
import org.json.JSONObject
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/** Native playback-only FGS. It never asks for RECORD_AUDIO or MediaProjection. */
class ReceiverService:Service() {
    companion object {
        const val ACTION_START="lab.hyperaudio.receiver.START"
        const val ACTION_PAUSE="lab.hyperaudio.receiver.PAUSE"
        const val ACTION_RESUME="lab.hyperaudio.receiver.RESUME"
        const val ACTION_STOP="lab.hyperaudio.receiver.STOP"
        const val ACTION_ALLOW="lab.hyperaudio.receiver.ALLOW"
        const val ACTION_DENY="lab.hyperaudio.receiver.DENY"
        const val ACTION_SPEAKER="lab.hyperaudio.receiver.SPEAKER"
        const val ACTION_HEADPHONES="lab.hyperaudio.receiver.HEADPHONES"
        const val ACTION_FORGET="lab.hyperaudio.receiver.FORGET"
        const val EXTRA_REQUEST_ID="requestId"
        const val OPEN_RECEIVER="lab.hyperaudio.OPEN_RECEIVER"
    }
    private val main=Handler(Looper.getMainLooper())
    private val live=AtomicBoolean(false)
    private val reset=AtomicBoolean(false)
    private val received=AtomicLong();private val written=AtomicLong();private val played=AtomicLong()
    private val lastPacket=AtomicLong(-1);private val channels=AtomicInteger(1)
    private val jitter=ReceiverJitterBuffer()
    private val policy=ReceiverPlaybackPolicy()
    private val feed=ReceiverFeedPolicy()
    private lateinit var audio:AudioManager
    private lateinit var store:ReceiverStore
    private lateinit var model:ReceiverControlModel
    @Volatile private var server:ReceiverServer?=null
    @Volatile private var track:AudioTrack?=null
    @Volatile private var availableOutputs:List<AudioDeviceInfo> = emptyList()
    @Volatile private var output="静音 · 等待耳机"
    @Volatile private var routedBluetooth=false
    @Volatile private var playbackActive=false
    @Volatile private var message="正在启动接收"
    private var media:MediaSession?=null
    private var lastMediaState=PlaybackState.STATE_NONE
    private var focus:AudioFocusRequest?=null
    private var wake:PowerManager.WakeLock?=null
    private var nsd:NsdManager?=null
    private var registration:NsdManager.RegistrationListener?=null
    private var nextWake=0L
    private var noisyRegistered=false
    private var player:Thread?=null
    private var lastNotification=""
    private val attrs by lazy {AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE).build()}
    private val deviceCallback=object:AudioDeviceCallback(){override fun onAudioDevicesAdded(devices:Array<out AudioDeviceInfo>){refreshOutputs()};override fun onAudioDevicesRemoved(devices:Array<out AudioDeviceInfo>){refreshOutputs()}}
    private val routing=AudioRouting.OnRoutingChangedListener{invalidateRoute()}
    private val noisy=object:BroadcastReceiver(){override fun onReceive(context:Context?,intent:Intent?){invalidateRoute()}}
    private val pulse=object:Runnable{override fun run(){if(!live.get())return;if(policy.shouldRequestFocus(jitter.size>0,desiredOutput()!=null))requestFocus();publish();refreshNotification();val now=SystemClock.elapsedRealtime();if(now>=nextWake){wake?.acquire(120000);nextWake=now+60000};main.postDelayed(this,500)}}
    override fun onBind(intent:Intent?)=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        when(intent?.action) {
            ACTION_START -> if(!live.get())start(intent)
            ACTION_STOP -> stopReceiving("接收已停止")
            ACTION_PAUSE -> {policy.pause();silence();focus?.let{audio.abandonAudioFocusRequest(it)};policy.focusLost(true);message="已暂停接收声音";publish()}
            ACTION_RESUME -> if(live.get()){policy.resume();invalidateRoute();message="等待音频与安全输出";publish()}
            ACTION_SPEAKER -> if(live.get()){policy.speaker=true;invalidateRoute();publish()}
            ACTION_HEADPHONES -> if(live.get()){policy.speaker=false;invalidateRoute();publish()}
            ACTION_ALLOW -> intent.getStringExtra(EXTRA_REQUEST_ID)?.let{server?.approve(it)}
            ACTION_DENY -> intent.getStringExtra(EXTRA_REQUEST_ID)?.let{server?.deny(it)}
            ACTION_FORGET -> intent.getStringExtra("clientId")?.let{if(::model.isInitialized && model.forget(it))store.save(model)}
        }
        if(!live.get())stopSelf()
        return START_NOT_STICKY
    }
    private fun start(intent:Intent){
        if(AppRole.current(this)!=AudioRole.RECEIVE || !AppRole.claim(AudioRole.RECEIVE,intent.getLongExtra("roleEpoch",AppRole.epoch))){stopSelf();return}
        audio=getSystemService(AudioManager::class.java);availableOutputs=audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList();store=ReceiverStore(this);model=ReceiverControlModel(store.receiverId){SystemClock.elapsedRealtime()};model.restore(store.load())
        live.set(true);policy.newSession();received.set(0);written.set(0);played.set(0);lastPacket.set(-1)
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("receiver","音频接收",NotificationManager.IMPORTANCE_LOW))
            lastMediaState=PlaybackState.STATE_NONE
            media=MediaSession(this,"HyperAudio receiver").apply {
                setCallback(object:MediaSession.Callback(){override fun onPlay(){command(ACTION_RESUME)};override fun onPause(){command(ACTION_PAUSE)};override fun onStop(){command(ACTION_STOP)}},main)
                setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,"HyperAudio · 接收声音").putString(MediaMetadata.METADATA_KEY_ARTIST,"局域网音频").build());isActive=true
            }
            startForeground(7,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            // Focus is requested by pulse only after fresh audio and an allowed output exist.
            audio.registerAudioDeviceCallback(deviceCallback,main)
            if(Build.VERSION.SDK_INT>=33)registerReceiver(noisy,IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") registerReceiver(noisy,IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            noisyRegistered=true
            wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"HyperAudio:receiver").apply{setReferenceCounted(false);acquire(120000)}
            nextWake=SystemClock.elapsedRealtime()+60000
            player=Thread({playLoop()},"HyperAudio-playback")
            player?.start()
            Thread({try {
                val transport=ReceiverServer(model,store,{inspectStatus()},{policy.senderSession();invalidateRoute()}, {decoder,pcm ->
                    received.incrementAndGet();lastPacket.set(SystemClock.elapsedRealtime());channels.set(decoder.channels)
                    if(policy.wantsPlayback() && (policy.speaker || headphone()!=null))jitter.offer(decoder.sequence,decoder.channels,pcm)
                },{e -> main.post{stopReceiving("接收网络失败：${e.javaClass.simpleName}")}})
                synchronized(this){if(!live.get())return@Thread;server=transport;transport.start()}
                main.post{if(live.get()){message="等待发端连接；默认只向耳机播放";registerNsd();publish()}}
            }catch(e:Exception){main.post{stopReceiving("接收启动失败：${e.javaClass.simpleName}: ${e.message}")}}},"HyperAudio-listen").start()
            main.post(pulse);updateTile()
        }catch(e:Exception){stopReceiving("无法接收：${e.javaClass.simpleName}: ${e.message}")}
    }
    private fun command(action:String){onStartCommand(Intent(this,ReceiverService::class.java).setAction(action),0,0)}
    private fun requestFocus(){
        if(!live.get())return
        if(focus==null)focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs).setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({change ->
                if(!live.get())return@setOnAudioFocusChangeListener
                if(change==AudioManager.AUDIOFOCUS_GAIN){policy.focusGained();invalidateRoute()}
                else {policy.focusLost(change!=AudioManager.AUDIOFOCUS_LOSS);silence();message=if(change==AudioManager.AUDIOFOCUS_LOSS)"音频焦点已让出；点恢复继续" else "暂时让出音频，等待系统恢复"}
                publish()
            },main).build()
        if(audio.requestAudioFocus(focus!!)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            policy.focusGained()
            LockSupport.unpark(player)
        } else {policy.focusRequestFailed();message="等待系统允许播放；可点恢复重试"}
    }
    private fun external(d:AudioDeviceInfo)=when(d.type){
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,AudioDeviceInfo.TYPE_WIRED_HEADSET,AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,AudioDeviceInfo.TYPE_USB_DEVICE,AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_HEADSET -> true
        else -> false
    }
    private fun refreshOutputs(){availableOutputs=audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList();invalidateRoute()}
    private fun headphone():AudioDeviceInfo?=availableOutputs.firstOrNull{external(it)}
    private fun desiredOutput():AudioDeviceInfo?=if(policy.speaker)availableOutputs.firstOrNull{it.type==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER} else headphone()
    private fun silence(){
        playbackActive=false;runCatching{track?.setVolume(0f)};jitter.clear();reset.set(true)
        LockSupport.unpark(player)
    }
    private fun invalidateRoute(){silence();output="正在确认实际输出；保持静音"}
    private fun playLoop(){
        val pcm=ShortArray(480);var currentChannels=0;var lastHead=0L;var configuredDevice=-1
        fun sampleHead(){val t=track ?:return;val head=t.playbackHeadPosition.toLong() and 0xffffffffL;val diff=(head-lastHead) and 0xffffffffL;if(diff<480000){played.addAndGet(diff);if(diff>0)playbackActive=true};lastHead=head}
        fun pauseFlush(){playbackActive=false;val t=track ?:return;runCatching{t.setVolume(0f);sampleHead();t.pause();t.flush()};lastHead=0L}
        try{while(live.get()){
            val desired=desiredOutput();val c=channels.get()
            if(reset.getAndSet(false)) {pauseFlush();configuredDevice=-1}
            if(desired==null || !policy.canPlay(!policy.speaker)) {
                if(track?.playState==AudioTrack.PLAYSTATE_PLAYING)pauseFlush()
                if(!policy.wantsPlayback() || desired==null)jitter.clear()
                output=when {
                    !policy.wantsPlayback()->"声音已暂停"
                    desired!=null->"静音 · 等待系统允许播放"
                    policy.speaker->"静音 · 等待扬声器输出"
                    else->"静音 · 等待蓝牙、有线或 USB 耳机"
                }
                // Focus, route changes, pause/resume and stop signal this thread.
                // An early signal leaves a permit for the next park call.
                LockSupport.park()
                continue
            }
            if(track==null || currentChannels!=c){
                pauseFlush();track?.let{it.removeOnRoutingChangedListener(routing);it.release()};track=null
                val mask=if(c==2)AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
                val min=AudioTrack.getMinBufferSize(48000,mask,AudioFormat.ENCODING_PCM_16BIT);check(min>0){"设备不支持48kHz播放"}
                val t=AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(mask).build())
                    .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(min*2,3840*c)).build()
                check(t.state==AudioTrack.STATE_INITIALIZED){"AudioTrack初始化失败"};track=t;currentChannels=c;lastHead=0L;configuredDevice=-1;t.setVolume(0f);t.addOnRoutingChangedListener(routing,main)
            }
            val t=track!!
            if(configuredDevice!=desired.id){t.setVolume(0f);t.setPreferredDevice(desired);configuredDevice=desired.id}
            if(jitter.size==0 && feed.shouldFlushEmpty(age())){
                if(t.playState==AudioTrack.PLAYSTATE_PLAYING)pauseFlush()
                jitter.awaitData();continue
            }
            if(t.playState!=AudioTrack.PLAYSTATE_PLAYING){t.setVolume(0f);t.play()}
            val actual=t.routedDevice
            val safe=actual!=null && (if(policy.speaker)actual.type==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else external(actual))
            if(!safe){playbackActive=false;t.setVolume(0f);output="静音 · 正在确认实际输出";jitter.clear();Thread.sleep(20);continue}
            routedBluetooth=actual!!.type in listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,AudioDeviceInfo.TYPE_BLE_HEADSET,AudioDeviceInfo.TYPE_HEARING_AID)
            output=if(policy.speaker)"扬声器（本次主动选择）" else if(routedBluetooth)"蓝牙耳机（实际路由）" else "有线 / USB 耳机（实际路由）"
            val format=jitter.poll(pcm)
            if(format==0){
                sampleHead()
                // Keep native PCM across ordinary 5/10ms UDP gaps. Flushing each empty
                // prebuffer prevents AudioTrack from ever reaching its startup threshold.
                if(feed.shouldFlushEmpty(age()) && t.playState==AudioTrack.PLAYSTATE_PLAYING)pauseFlush()
                jitter.awaitData();continue
            }
            if(format!=currentChannels){jitter.clear();continue}
            // Recheck after dequeue: a focus/route callback may have arrived in between.
            if(!policy.canPlay(!policy.speaker) || reset.get()){t.setVolume(0f);continue}
            t.setVolume(1f)
            var offset=0
            while(offset<480 && live.get() && !reset.get()){
                val n=t.write(pcm,offset,480-offset,AudioTrack.WRITE_BLOCKING);check(n>=0){"AudioTrack写入失败 $n"};if(n==0){Thread.sleep(2);continue};offset+=n;written.addAndGet((n/currentChannels).toLong())
            }
            sampleHead()
        }}catch(e:Exception){if(live.get())main.post{stopReceiving("播放失败：${e.javaClass.simpleName}: ${e.message}")}}
        finally {runCatching{pauseFlush();track?.removeOnRoutingChangedListener(routing);track?.release()};track=null}
    }
    private fun inspectStatus():JSONObject {
        val phase=audioPhase()
        return JSONObject().put("running",live.get()).put("listening",live.get()).put("paused",policy.isPaused()).put("bluetooth",routedBluetooth)
            .put("waitingForAudio",ReceiverAudioStatus.waitingForAudio(phase)).put("audioPhase",phase)
            .put("received",received.get()).put("lastPacketAgeMs",age()).put("queuedMS",jitter.queuedMs()).put("channels",channels.get())
            .put("frameDurationMS",AudioWire.frameDurationMS(channels.get())).put("dropped",jitter.dropped).put("concealed",jitter.concealed)
    }
    private fun audioPhase()=ReceiverAudioStatus.phase(live.get(),policy.isPaused(),age(),jitter.queuedMs(),playbackActive)
    private fun age()=lastPacket.get().let{if(it<0)-1 else (SystemClock.elapsedRealtime()-it).coerceAtLeast(0)}
    private fun addresses()=runCatching{NetworkInterface.getNetworkInterfaces().toList().flatMap{it.inetAddresses.toList()}.filter{!it.isLoopbackAddress && it is java.net.Inet4Address}.joinToString(" / "){it.hostAddress ?: ""}}.getOrDefault("")
    private fun publish(){
        if(!::model.isInitialized)return
        model.expire()
        val phase=audioPhase()
        val status=when(phase){"waiting"->"接收已开启；等待发端连接或恢复音频";"buffering"->"已收到音频；正在缓冲或等待安全输出";"playing"->"正在播放接收到的声音";else->message}
        ReceiverState.publish(ReceiverSnapshot(live.get(),policy.isPaused(),if(model.pending!=null)"有首次配对请求，请在此允许或拒绝" else status,model.token,if(live.get())addresses() else "",model.pending,model.clients(),model.activeClient,
            received.get(),played.get(),written.get(),channels.get(),jitter.queuedMs(),output,policy.speaker,routedBluetooth,age(),jitter.dropped,jitter.concealed,ReceiverAudioStatus.waitingForAudio(phase),phase))
        val mediaState=if(!live.get())PlaybackState.STATE_STOPPED else if(ReceiverAudioStatus.isPlaying(phase))PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        media?.let { session ->
            if(mediaState!=lastMediaState) {
                session.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_PLAY_PAUSE)
                    .setState(mediaState,PlaybackState.PLAYBACK_POSITION_UNKNOWN,1f).build())
                lastMediaState=mediaState
            }
        }
    }
    private fun notification():Notification {
        val open=PendingIntent.getActivity(this,70,Intent(this,MainActivity::class.java).setAction(OPEN_RECEIVER).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun action(code:Int,title:String,value:String)=Notification.Action.Builder(null,title,PendingIntent.getService(this,code,Intent(this,ReceiverService::class.java).setAction(value),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
        val paused=policy.isPaused()
        return Notification.Builder(this,"receiver").setSmallIcon(R.drawable.ic_receiver_notification).setContentTitle(if(::model.isInitialized && model.pending!=null)"HyperAudio · 请确认首次配对" else "HyperAudio · 正在接收")
            .setContentText(if(::model.isInitialized && model.pending!=null)"打开 App 允许或拒绝 ${model.pending?.name}" else ReceiverState.snapshot.status+" · "+output).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(71,if(paused)"恢复" else "暂停",if(paused)ACTION_RESUME else ACTION_PAUSE)).addAction(action(72,"停止",ACTION_STOP))
            .setStyle(Notification.MediaStyle().setMediaSession(media?.sessionToken).setShowActionsInCompactView(0,1)).build()
    }
    private fun refreshNotification(){val key="${model.pending?.id}|${output}|${policy.isPaused()}|${ReceiverState.snapshot.audioPhase}";if(key!=lastNotification){lastNotification=key;getSystemService(NotificationManager::class.java).notify(7,notification());updateTile()}}
    private fun registerNsd(){
        nsd=getSystemService(NsdManager::class.java)
        val listener=object:NsdManager.RegistrationListener{
            override fun onServiceRegistered(info:NsdServiceInfo){}
            override fun onRegistrationFailed(info:NsdServiceInfo,errorCode:Int){message="自动发现不可用；请用地址与配对码连接"}
            override fun onServiceUnregistered(info:NsdServiceInfo){}
            override fun onUnregistrationFailed(info:NsdServiceInfo,errorCode:Int){}
        };registration=listener
        nsd?.registerService(NsdServiceInfo().apply{serviceName="HyperAudio-${model.receiverId.take(8)}";serviceType="_hyperaudio._tcp.";port=50006},NsdManager.PROTOCOL_DNS_SD,listener)
    }
    private fun updateTile(){TileService.requestListeningState(this,ComponentName(this,CaptureTileService::class.java))}
    private fun stopReceiving(reason:String){
        if(!live.getAndSet(false))return
        message=reason;silence();main.removeCallbacks(pulse)
        if(::audio.isInitialized){runCatching{audio.unregisterAudioDeviceCallback(deviceCallback)};focus?.let{runCatching{audio.abandonAudioFocusRequest(it)}}}
        if(noisyRegistered){runCatching{unregisterReceiver(noisy)};noisyRegistered=false}
        registration?.let{runCatching{nsd?.unregisterService(it)}};registration=null
        runCatching{if(wake?.isHeld==true)wake?.release()};wake=null
        media?.isActive=false;media?.release();media=null
        // Serialize with the background bind so stop cannot race a just-opening listener.
        // Socket shutdown and thread joining stay off the UI thread. Keep the role lease until cleanup ends.
        Thread({synchronized(this@ReceiverService){server?.close();server=null};player?.join(2500);AppRole.release(AudioRole.RECEIVE)},"HyperAudio-receiver-stop").start()
        model.stop();routedBluetooth=false;output="接收已停止";publish();stopForeground(STOP_FOREGROUND_REMOVE);updateTile();stopSelf()
    }
    override fun onDestroy(){stopReceiving("接收已停止");super.onDestroy()}
}
