package lab.hyperaudio

/** Display metadata never participates in challenge authentication. Keys remain key:<exact ID>. */
data class PairedDevice(val id: String, val name: String, val alias: String?, val host: String?,
    val port: Int, val lastSeenMS: Long) {
    val displayName get() = alias?.takeIf { it.isNotBlank() } ?: name
}
class PairingCatalog(private val data: MutableMap<String, Any?>) {
    private fun prefix(id: String) = "device:$id:"
    init {
        val id = data["lastID"] as? String
        if (id != null && data["key:$id"] is String) {
            val p = prefix(id)
            for ((old, field) in listOf("lastHost" to "host", "lastPort" to "port", "lastName" to "name"))
                data[old]?.let { if (!data.containsKey(p+field)) data[p+field] = it }
        }
    }
    private fun shortID(id: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray(Charsets.UTF_8)).take(4).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun devices(): List<PairedDevice> = data.keys.filter { it.startsWith("key:") && data[it] is String }.map { key ->
        val id = key.removePrefix("key:"); val p = prefix(id)
        PairedDevice(id, data[p+"name"] as? String ?: "已配对的 iPhone · ${shortID(id)}", data[p+"alias"] as? String,
            data[p+"host"] as? String, (data[p+"port"] as? Number)?.toInt() ?: 50006,
            (data[p+"seen"] as? Number)?.toLong() ?: 0)
    }.sortedByDescending { it.lastSeenMS }
    fun authenticated(id: String, name: String, host: String, port: Int, nowMS: Long) {
        val p = prefix(id)
        data[p+"name"] = name.take(64); data[p+"host"] = host; data[p+"port"] = port; data[p+"seen"] = nowMS
    }
    fun rename(id: String, alias: String) {
        require(data["key:$id"] is String)
        if (alias.isBlank()) data.remove(prefix(id)+"alias") else data[prefix(id)+"alias"] = alias.trim().take(64)
    }
    fun forget(id: String, activeID: String?): Boolean {
        if (activeID == id) return false
        data.remove("key:$id")
        listOf("name","alias","host","port","seen").forEach { data.remove(prefix(id)+it) }
        if (data["lastID"] == id) listOf("lastID","lastHost","lastPort","lastName").forEach { data.remove(it) }
        return true
    }
    fun forgetAll(activeID: String?): Boolean {
        if (activeID != null) return false
        devices().map { it.id }.forEach { forget(it,null) }
        listOf("lastID","lastHost","lastPort","lastName").forEach { data.remove(it) }
        return true
    }
}
