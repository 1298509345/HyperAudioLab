package lab.hyperaudio

import org.junit.Assert.*
import org.junit.Test

class PairingProofTest {
    private val key = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
    private val client = "00112233-4455-6677-8899-aabbccddeeff"
    private val receiver = "ffeeddcc-bbaa-9988-7766-554433221100"
    @Test fun matchesIndependentPythonHmacVector() {
        assertEquals("b664d32f0d3c8ff7b9605f7d7f6b9f1d342ad67985eda8ba4f4910e7285cac9f",
            PairingProof.sign(key,client,receiver,"test-nonce-01"))
    }
    @Test fun proofIsBoundToNonceAndBothDevices() {
        val signature = PairingProof.sign(key,client,receiver,"nonce-1")
        assertNotEquals(signature, PairingProof.sign(key,client,receiver,"nonce-2"))
        assertNotEquals(signature, PairingProof.sign(key,receiver,client,"nonce-1"))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsMalformedStoredKey() {
        PairingProof.sign("not-a-key",client,receiver,"nonce")
    }
}
