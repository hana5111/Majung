package kr.ddakta.pickup

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

data class Appointment(
    val id: String, val driverName: String, val passengerName: String,
    val place: String, val meetingPoint: String, val meetingAt: Instant?,
    val note: String, val status: String, val inviteToken: String,
    val passengerContact: String, val pickupLocation: PickupLocation? = null, val version: Int = 1,
    val pickupMode: String = "SCHEDULED", val trip: PickupTrip = PickupTrip()
) {
    val active: Boolean get() = status == "PENDING" || status == "CONFIRMED"
    val label: String get() = when (status) {
        "PENDING" -> "응답 대기"
        "CONFIRMED" -> "약속 확정"
        "DECLINED" -> "탑승자 거절"
        "CANCELLED" -> "취소됨"
        else -> "만료됨"
    }
    val inviteUrl: String get() = "${BuildConfig.INVITE_ORIGIN.trimEnd('/')}/invite#$inviteToken"
    val canShare: Boolean get() = active && (pickupMode != "NOW" || trip.departedAt != null)
    fun timeLabel(now: Instant = Instant.now()): String = if (pickupMode == "NOW") trip.label(now)
        else meetingAt?.let { java.time.format.DateTimeFormatter.ofPattern("a h:mm", java.util.Locale.KOREAN).withZone(PickupZone).format(it) } ?: "출발 준비"
    companion object {
        fun parse(o: JSONObject) = Appointment(
            o.getString("id"), o.getString("driverName"), o.getString("passengerName"),
            o.getString("place"), o.optString("meetingPoint"), o.optString("meetingAt").takeIf { !o.isNull("meetingAt") && it.isNotBlank() }?.let(Instant::parse),
            o.optString("note"), o.getString("status"), o.optString("inviteToken"), o.optString("passengerContact"),
            o.optJSONObject("pickupLocation")?.let { PickupLocation.parse(it) }, o.optInt("version", 1),
            o.optString("pickupMode", "SCHEDULED"), PickupTrip.parse(o.optJSONObject("trip"))
        )
    }
}

class PickupRepository(context: Context, private val connectTimeoutMs: Int = 4000, private val readTimeoutMs: Int = 5000) {
    companion object { private val connectionLock = Any() }
    private val prefs = context.getSharedPreferences("ddakta_connection", Context.MODE_PRIVATE)
    private val key = "driver_${BuildConfig.API_URL}"
    private fun driverToken(): String = synchronized(connectionLock) {
        prefs.getString(key, null)?.let { return@synchronized it }
        val result = call("POST", "/api/drivers", JSONObject().put("displayName", "운전자"), authenticate = false)
        result.getString("accessToken").also { prefs.edit().putString(key, it).apply() }
    }
    private fun call(method: String, route: String, body: JSONObject? = null, authenticate: Boolean = true): JSONObject {
        val auth = if (authenticate) driverToken() else null
        val c = URL(BuildConfig.API_URL.trimEnd('/') + route).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = connectTimeoutMs; c.readTimeout = readTimeoutMs
            c.setRequestProperty("Accept", "application/json")
            if (auth != null) c.setRequestProperty("Authorization", "Bearer $auth")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val result = stream?.bufferedReader(Charsets.UTF_8)?.use { JSONObject(it.readText()) }
                ?: throw IllegalStateException("서버의 응답을 확인할 수 없습니다.")
            if (code !in 200..299) throw IllegalStateException(result.optString("error", "요청을 처리하지 못했습니다."))
            return result
        } finally { c.disconnect() }
    }
    fun list(): List<Appointment> {
        val a = call("GET", "/api/appointments").getJSONArray("appointments")
        return (0 until a.length()).map { Appointment.parse(a.getJSONObject(it)) }
    }
    fun create(name: String, contact: String, place: String, point: String, time: Instant?, note: String, clientRequestId: String? = null, pickupLocation: PickupLocation? = null): Appointment =
        Appointment.parse(call("POST", "/api/appointments", JSONObject()
            .put("passengerName", name).put("passengerContact", contact).put("place", place)
            .put("meetingPoint", point).put("meetingAt", time?.toString() ?: JSONObject.NULL).put("note", note)
            .put("pickupMode", if (time == null) "NOW" else "SCHEDULED")
            .apply { if (clientRequestId != null) put("clientRequestId", clientRequestId)
                if (pickupLocation != null) put("pickupLocation", pickupLocation.toJson()) }))
    fun setPickupLocation(a: Appointment, location: PickupLocation): Appointment = Appointment.parse(call("POST", "/api/appointments/${a.id}/location",
        JSONObject().put("expectedVersion", a.version).put("pickupLocation", location.toJson())))
    fun cancel(id: String): Appointment = Appointment.parse(call("POST", "/api/appointments/$id/cancel", JSONObject()))
    fun updateTrip(a: Appointment, state: String, observedAt: Long, remainingSeconds: Int? = null): Appointment = Appointment.parse(call("POST", "/api/appointments/${a.id}/trip",
        JSONObject().put("state", state).put("observedAt", observedAt).put("remainingSeconds", remainingSeconds ?: JSONObject.NULL)
            .put("pickupLocation", requireNotNull(a.pickupLocation).toJson())))
}
