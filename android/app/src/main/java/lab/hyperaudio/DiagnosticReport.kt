package lab.hyperaudio

import org.json.JSONObject

/** Explicit whitelist: never collect status text, endpoint, names, identifiers or PCM. */
object DiagnosticReport {
    fun export(snapshot: CaptureSnapshot, version: String, os: String): String = JSONObject()
        .put("version",version).put("os",os).put("channels",snapshot.channels)
        .put("mode",snapshot.mode).put("durationMS",snapshot.elapsedMS)
        .put("packets",snapshot.packets).put("sent",snapshot.sent)
        .put("received",snapshot.receiver?.received ?: 0)
        .put("errorCode",snapshot.errorCode.name)
        .put("routeType",when (snapshot.receiver?.bluetooth) { true -> "bluetooth"; false -> "other"; null -> "unknown" })
        .toString(2)
}
enum class CaptureError { NONE, AUTHORIZATION_REVOKED, NETWORK_UNAVAILABLE, CAPTURE_FAILED }
