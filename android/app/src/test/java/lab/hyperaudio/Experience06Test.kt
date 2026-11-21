package lab.hyperaudio
import org.junit.Assert.*
import org.junit.Test
class Experience06Test {
    @Test fun migratesLastAndOrphanKeysWithoutLosingIdentityAndUsesFallback() {
        val data = mutableMapOf<String,Any?>("key:one" to "secret1", "key:two" to "secret2", "clientID" to "stable", "lastID" to "one", "lastHost" to "192.168.1.5", "lastPort" to 50006, "lastName" to "old phone", "unrelated" to "keep")
        val catalog = PairingCatalog(data)
        val devices = catalog.devices()
        assertEquals(2, devices.size)
        assertEquals("old phone", devices.single { it.id == "one" }.displayName)
        assertNull(devices.single { it.id == "two" }.host)
        assertTrue(devices.single { it.id == "two" }.displayName.isNotBlank())
        catalog.rename("one", "卧室")
        catalog.authenticated("one", "new phone", "192.168.1.6", 50006, 42)
        assertEquals("卧室", catalog.devices().single { it.id == "one" }.displayName)
        assertEquals(42L, catalog.devices().single { it.id == "one" }.lastSeenMS)
        assertEquals("secret1",data["key:one"]); assertEquals("stable",data["clientID"])
    }
    @Test fun forgetExactIdentityRejectsActiveAndPreservesOthersAndPreferences() {
        val data = mutableMapOf<String,Any?>("key:a" to "a-secret", "key:ab" to "ab-secret", "clientID" to "stable", "unrelated" to 3, "lastID" to "a", "lastHost" to "private")
        val catalog = PairingCatalog(data)
        assertFalse(catalog.forget("a", "a")); assertTrue(data.containsKey("key:a"))
        assertTrue(catalog.forget("a", null))
        assertFalse(data.containsKey("key:a")); assertFalse(data.containsKey("lastHost"))
        assertEquals("ab-secret",data["key:ab"]); assertEquals("stable",data["clientID"]); assertEquals(3,data["unrelated"])
        assertFalse(catalog.forgetAll("ab")); assertTrue(catalog.forgetAll(null))
        assertEquals(mapOf("clientID" to "stable", "unrelated" to 3),data)
    }
    @Test fun reportWhitelistExcludesPrivateValuesFromRealSnapshot() {
        val snapshot = CaptureSnapshot(running=true, channels=2, elapsedMS=8123, packets=22, sent=20,
            text="token=private-token secret=private-secret path=/private/pcm SSID=private-wifi", connection="private-error",
            endpoint=ReceiverEndpoint("private-alias", "192.168.99.88", expectedID="private-full-id"),
            receiver=ReceiverStatus("private-receiver-id","private-name",true,false,true,19,3,40))
        val report = DiagnosticReport.export(snapshot, "0.6", "Android 35")
        for (value in listOf("private", "192.168", "SSID", "token", "secret", "/pcm")) assertFalse(report.contains(value))
        val obj = org.json.JSONObject(report)
        assertEquals(setOf("version","os","channels","mode","durationMS","packets","sent","received","errorCode","routeType"),obj.keys().asSequence().toSet())
        assertEquals(22L,obj.getLong("packets")); assertEquals("bluetooth",obj.getString("routeType"))
    }
}
