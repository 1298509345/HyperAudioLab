package lab.hyperaudio

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ControlProtocolTest {
    private val receiver = "ffeeddcc-bbaa-9988-7766-554433221100"
    private val client = "00112233-4455-6677-8899-aabbccddeeff"
    private val key = "00".repeat(32)
    private fun input(id: String = receiver, response: String = "status") = ByteArrayInputStream((
        JSONObject().put("type", "challenge").put("v", 1).put("receiverId", id).put("nonce", "a".repeat(32)).toString() + "\n" +
        JSONObject().put("type", response).put("reason", "pair_required").put("v", 1).put("receiverId", id)
            .put("name", "Phone").put("running", true).put("paused", false).put("bluetooth", true)
            .put("received", 123).put("lastPacketAgeMs", 10).put("queuedMS", 40).put("future", 99).toString() + "\n").toByteArray())
    @Test fun inspectAuthenticatesWithoutResumePairOrTokenRotation() {
        val out = ByteArrayOutputStream()
        val status = ControlProtocol.inspect(input(), out, client, receiver, key, "Android")
        assertEquals(123L, status.received)
        assertTrue(status.bluetooth)
        val messages = out.toString("UTF-8").trim().lines().map { JSONObject(it) }
        assertEquals(listOf("hello", "inspect"), messages.map { it.getString("type") })
        assertEquals(PairingProof.sign(key, client, receiver, "a".repeat(32)), messages[1].getString("proof"))
    }
    @Test fun identityMismatchNeverSendsProof() {
        val out = ByteArrayOutputStream()
        val result = runCatching { ControlProtocol.inspect(input("00000000-0000-0000-0000-000000000000"), out, client, receiver, key, "Android") }
        assertTrue(result.isFailure)
        assertEquals(1, out.toString("UTF-8").trim().lines().size)
    }
    @Test fun legacyPairRequiredIsExplicitCompatibilityErrorWithoutPairing() {
        val out = ByteArrayOutputStream()
        val result = runCatching { ControlProtocol.inspect(input(response="error"), out, client, receiver, key, "Android") }
        assertTrue(result.exceptionOrNull() is InspectUnavailable)
        assertFalse(out.toString("UTF-8").contains("\"type\":\"pair\""))
    }
    @Test fun oversizedControlMessageIsRejected() {
        val result = runCatching { ControlProtocol.read(ByteArrayInputStream(("x".repeat(4097)+"\n").toByteArray())) }
        assertTrue(result.isFailure)
    }
}
