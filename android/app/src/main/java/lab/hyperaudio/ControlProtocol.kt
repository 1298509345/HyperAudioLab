package lab.hyperaudio

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import org.json.JSONObject

data class ReceiverStatus(val receiverID: String, val name: String, val running: Boolean, val paused: Boolean,
    val bluetooth: Boolean, val received: Long, val lastPacketAgeMs: Long, val queuedMS: Int)
class InspectUnavailable : Exception("认证自检不可用；请确认配对有效并升级 接收端 接收端至 0.4")
object ControlProtocol {
    fun read(input: InputStream): JSONObject {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < 4096) {
            val b = input.read()
            check(b != -1) { "接收端 已关闭连接" }
            if (b == 10) return JSONObject(bytes.toString("UTF-8"))
            bytes.write(b)
        }
        error("控制消息超过限制")
    }
    fun send(output: OutputStream, value: JSONObject) {
        output.write((value.toString()+"\n").toByteArray(Charsets.UTF_8)); output.flush()
    }
    fun challenge(input: InputStream, output: OutputStream, client: String, expected: String?, name: String): JSONObject {
        send(output, JSONObject().put("type", "hello").put("v", 1).put("clientId", client).put("name", name.take(48)))
        val result = read(input)
        check(result.optString("type") == "challenge" && result.optInt("v") == 1) { "不是兼容的 HyperAudio 接收端" }
        val receiver = result.getString("receiverId")
        UUID.fromString(receiver)
        check(expected == null || receiver == expected) { "此地址的设备身份已变化；请选择正确接收端" }
        check(result.getString("nonce").length in 32..128) { "无效的认证挑战" }
        return result
    }
    /** This exchange has no pair/resume path and never handles tokens or secrets from the peer. */
    fun inspect(input: InputStream, output: OutputStream, client: String, expected: String, secret: String, name: String): ReceiverStatus {
        val challenge = challenge(input, output, client, expected, name)
        send(output, JSONObject().put("type", "inspect").put("proof",
            PairingProof.sign(secret, client, expected, challenge.getString("nonce"))))
        val status = read(input)
        if (status.optString("type") != "status") throw InspectUnavailable()
        check(status.optInt("v") == 1 && status.getString("receiverId") == expected) { "接收端状态身份不匹配" }
        val received = status.getLong("received")
        val age = status.getLong("lastPacketAgeMs")
        val queued = status.getInt("queuedMS")
        check(received >= 0 && age >= -1 && queued >= 0) { "接收端状态不合法" }
        return ReceiverStatus(expected, status.optString("name").take(64), status.getBoolean("running"),
            status.getBoolean("paused"), status.getBoolean("bluetooth"), received, age, queued)
    }
}
