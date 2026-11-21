package lab.hyperaudio

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReceiverDiagnostics07Test {
    @Test fun receiverReportExportsOnlyWhitelistedFieldsFromRealSnapshot() {
        val canary="PRIVATE-CANARY-secret-UUID-192.168.99.88"
        val snapshot=ReceiverSnapshot(running=true, paused=false, status=canary, token=canary,
            addresses=canary, pending=ReceiverPairRequest(canary,canary,canary,99),
            clients=listOf(ReceiverClient(canary,canary)),activeClient=canary,
            received=17,playedFrames=160,writtenFrames=320,channels=2,queuedMs=40,
            actualOutput=canary, speaker=true, bluetooth=true,lastPacketAgeMs=12,
            waitingForAudio=true,audioPhase="waiting")
        val report=ReceiverDiagnostics.export(snapshot,"0.7","Android 35")
        assertFalse(report.contains(canary))
        val value=JSONObject(report)
        assertEquals(setOf("appVersion","platform","role","running","paused","phase","waitingForAudio",
            "received","playedFrames","writtenFrames","channels","queuedMs","lastPacketAgeMs","routeType"),
            value.keys().asSequence().toSet())
        assertEquals("0.7",value.getString("appVersion"));assertEquals("Android 35",value.getString("platform"))
        assertEquals("receive",value.getString("role"));assertTrue(value.getBoolean("running"))
        assertFalse(value.getBoolean("paused"));assertTrue(value.getBoolean("waitingForAudio"))
        assertEquals("waiting",value.getString("phase"));assertEquals(17L,value.getLong("received"))
        assertEquals(160L,value.getLong("playedFrames"));assertEquals(320L,value.getLong("writtenFrames"))
        assertEquals(2,value.getInt("channels"));assertEquals(40,value.getInt("queuedMs"))
        assertEquals(12L,value.getLong("lastPacketAgeMs"));assertEquals("none",value.getString("routeType"))
    }

    @Test fun receiverReportDoesNotExportArbitraryPhaseOrRouteText() {
        val report=ReceiverDiagnostics.export(ReceiverSnapshot(audioPhase="SECRET-phase",actualOutput="SECRET-output"),"0.7","Android")
        assertFalse(report.contains("SECRET"))
        assertEquals("stopped",JSONObject(report).getString("phase"))
        assertEquals("none",JSONObject(report).getString("routeType"))
    }

    @Test fun receiverReportUsesOnlyCoarseConfirmedRoutes() {
        val routes=mapOf("扬声器（本次主动选择）" to "speaker","蓝牙耳机（实际路由）" to "bluetooth",
            "有线 / USB 耳机（实际路由）" to "wired","静音 · 等待耳机" to "none",
            "静音 · 正在确认实际输出" to "none","接收已停止" to "none")
        routes.forEach { (output,expected) ->
            val report=ReceiverDiagnostics.export(ReceiverSnapshot(actualOutput=output),"0.7","Android")
            assertEquals(output,expected,JSONObject(report).getString("routeType"))
        }
    }

    @Test fun receiverDetailReflectsReceiverAndMediaStateWithoutCaptureClaims() {
        val detail=ReceiverDiagnostics.detail(ReceiverSnapshot(running=true,paused=false,received=25,
            playedFrames=480,writtenFrames=960,channels=2,queuedMs=60,lastPacketAgeMs=125,
            waitingForAudio=true,audioPhase="waiting",actualOutput="蓝牙耳机（实际路由）"),3,15,false)
        for (value in listOf("running=true","paused=false","phase=waiting","waitingForAudio=true",
            "25","playedFrames=480","writtenFrames=960","queuedMs=60","channels=2","125 ms",
            "3 / 15","mute=false","蓝牙耳机（实际路由）","不代表耳机可听")) assertTrue(value,detail.contains(value))
        assertFalse(detail.contains("未采集"));assertFalse(detail.contains("LabState"))
    }

    @Test fun receiverDetailShowsStoppedPausedAndNoPacketAge() {
        val detail=ReceiverDiagnostics.detail(ReceiverSnapshot(paused=true,audioPhase="paused"),0,15,true)
        assertTrue(detail.contains("running=false"));assertTrue(detail.contains("paused=true"))
        assertTrue(detail.contains("phase=paused"));assertTrue(detail.contains("尚无音频包"))
        assertTrue(detail.contains("0 / 15"));assertTrue(detail.contains("mute=true"))
    }
}
