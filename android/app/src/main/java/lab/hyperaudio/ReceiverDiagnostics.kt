package lab.hyperaudio

import org.json.JSONObject

/** Receiver-only diagnostics. Export uses an explicit, narrow field whitelist. */
object ReceiverDiagnostics {
    fun export(snapshot: ReceiverSnapshot, version: String, platform: String): String = JSONObject()
        .put("appVersion",version).put("platform",platform).put("role","receive")
        .put("running",snapshot.running).put("paused",snapshot.paused)
        .put("phase",snapshot.audioPhase.takeIf { it in setOf("stopped","paused","waiting","buffering","playing") } ?: "stopped")
        .put("waitingForAudio",snapshot.waitingForAudio).put("received",snapshot.received)
        .put("playedFrames",snapshot.playedFrames).put("writtenFrames",snapshot.writtenFrames)
        .put("channels",snapshot.channels).put("queuedMs",snapshot.queuedMs)
        .put("lastPacketAgeMs",snapshot.lastPacketAgeMs)
        // Match only the service's fixed route labels; arbitrary output/device text never leaves the app.
        .put("routeType",when(snapshot.actualOutput) {
            "扬声器（本次主动选择）" -> "speaker"
            "蓝牙耳机（实际路由）" -> "bluetooth"
            "有线 / USB 耳机（实际路由）" -> "wired"
            else -> "none"
        }).toString(2)

    fun detail(snapshot: ReceiverSnapshot, mediaVolume: Int, mediaMax: Int, muted: Boolean): String =
        "接收服务：running=${snapshot.running} · paused=${snapshot.paused}\n"+
            "音频阶段：phase=${snapshot.audioPhase} · waitingForAudio=${snapshot.waitingForAudio}\n"+
            "已收 ${snapshot.received} 包 · 最近音频包："+
            (if(snapshot.lastPacketAgeMs<0) "尚无音频包" else "${snapshot.lastPacketAgeMs} ms 前")+"\n"+
            "队列 queuedMs=${snapshot.queuedMs} · channels=${snapshot.channels}\n"+
            "播放头 playedFrames=${snapshot.playedFrames} · 已写 writtenFrames=${snapshot.writtenFrames}\n"+
            "实际输出：${snapshot.actualOutput}\n"+
            "系统媒体音量：$mediaVolume / $mediaMax · mute=$muted\n"+
            "播放头推进不代表耳机可听，实际听感需人工确认。自检只读取本机状态，不发声、不改变接收或输出，也不进行网络探测。"
}
