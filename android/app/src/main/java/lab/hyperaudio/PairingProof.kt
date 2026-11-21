package lab.hyperaudio

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object PairingProof {
    fun sign(secret: String, client: String, receiver: String, nonce: String): String {
        require(secret.matches(Regex("[0-9a-f]{64}")))
        val key = secret.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key,"HmacSHA256"))
        return mac.doFinal("HAC1|$client|$receiver|$nonce".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
