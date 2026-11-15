package kr.ddakta.pickup

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlin.math.ceil

/** Device-only demonstration data. Demo invitation markers never identify a server invitation. */
class LocalPickupStore(
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val lock: Any = Any()
) {
    private fun database(): JSONObject = read()?.let(::JSONObject)
        ?: JSONObject().put("appointments", JSONArray())

    private fun appointments(db: JSONObject): List<JSONObject> = db.getJSONArray("appointments").let { rows ->
        (0 until rows.length()).map(rows::getJSONObject)
    }

    private fun find(db: JSONObject, id: String): JSONObject = requireNotNull(appointments(db).find { it.getString("id") == id }) {
        "약속을 찾을 수 없습니다."
    }

    private fun status(a: JSONObject, at: Long): String = a.getString("status").let {
        if (it in setOf("PENDING", "CONFIRMED") && at >= a.getLong("expiresAt")) "EXPIRED" else it
    }

    private fun view(a: JSONObject, at: Long): Appointment {
        val result = JSONObject(a.toString()).put("status", status(a, at))
        a.optJSONObject("pickupLocation")?.let { result.put("place", it.getString("name")) }
        val trip = a.optJSONObject("trip") ?: JSONObject().put("state", "NOT_STARTED")
        val observedAt = trip.optLong("observedAt").takeIf { trip.has("observedAt") && !trip.isNull("observedAt") }
        val fresh = trip.optString("state") == "EN_ROUTE" && observedAt != null && at - observedAt < 45_000
        val arrival = trip.optString("arrivalAt").takeIf { fresh && it.isNotBlank() && !trip.isNull("arrivalAt") }
        result.put("trip", JSONObject().put("state", trip.optString("state", "NOT_STARTED"))
            .put("departedAt", trip.opt("departedAt") ?: JSONObject.NULL)
            .put("updatedAt", observedAt ?: JSONObject.NULL).put("etaFresh", fresh)
            .put("arrivalAt", arrival ?: JSONObject.NULL)
            .put("remainingSeconds", arrival?.let { ceil((Instant.parse(it).toEpochMilli() - at) / 1000.0).toLong().coerceAtLeast(0) } ?: JSONObject.NULL))
        return Appointment.parse(result)
    }

    private fun clean(value: String, label: String, limit: Int, optional: Boolean = false): String {
        if (optional && value.isEmpty()) return ""
        val trimmed = value.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= limit) { "${label}을(를) 확인해 주세요." }
        return trimmed
    }

    private fun normalized(location: PickupLocation): PickupLocation = location.copy(
        name = clean(location.name, "선택한 장소 정보", 100), address = clean(location.address, "선택한 장소 정보", 240, true),
        poiId = clean(location.poiId, "선택한 장소 정보", 160, true), poiSubId = clean(location.poiSubId, "선택한 장소 정보", 80, true),
        poiProvider = clean(location.poiProvider, "선택한 장소 정보", 80, true)
    )

    private fun fingerprint(name: String, contact: String, place: String, point: String, meetingAt: String?, note: String, location: PickupLocation?): String {
        // Arrays give persistent request fingerprints a stable field order on Android and the JVM.
        val target = location?.let { JSONArray(listOf(it.latitude, it.longitude, it.displayLatitude, it.displayLongitude,
            it.name, it.address, it.poiId, it.poiSubId, it.poiProvider)) }
        val fields = JSONArray(listOf(name, contact, place, point, meetingAt, note, target))
        return MessageDigest.getInstance("SHA-256").digest(fields.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun list(): List<Appointment> = synchronized(lock) {
        val at = now()
        appointments(database()).sortedByDescending { it.getLong("createdAt") }.map { view(it, at) }
    }

    fun create(name: String, contact: String, place: String, point: String, time: Instant?, note: String,
        clientRequestId: String? = null, pickupLocation: PickupLocation? = null): Appointment = synchronized(lock) {
        val db = database()
        val passengerName = clean(name, "탑승자 이름", 40)
        val passengerContact = clean(contact, "연락처", 30, true)
        require(passengerContact.isEmpty() || passengerContact.matches(Regex("[+0-9 ()-]{5,30}"))) { "연락처 형식을 확인해 주세요." }
        val destination = clean(place, "만날 장소", 100)
        val meetingPoint = clean(point, "상세 승차 지점", 120, time == null)
        val message = clean(note, "전달 메모", 240, true)
        val location = pickupLocation?.let(::normalized)
        require(clientRequestId == null || clientRequestId.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) {
            "약속 생성 요청 정보를 확인해 주세요."
        }
        val meetingMs = time?.toEpochMilli()
        val meetingAt = meetingMs?.let { Instant.ofEpochMilli(it).toString() }
        val requestFingerprint = fingerprint(passengerName, passengerContact, destination, meetingPoint, meetingAt, message, location)
        val at = now()
        if (clientRequestId != null) appointments(db).find { it.optString("clientRequestId") == clientRequestId }?.let {
            require(it.getString("requestFingerprint") == requestFingerprint) { "같은 생성 요청으로 약속 내용을 변경할 수 없습니다." }
            return@synchronized view(it, at)
        }
        require(meetingMs == null || meetingMs in at..(at + 366L * 86_400_000)) { "앞으로 1년 이내의 만날 날짜와 시간을 선택해 주세요." }
        val a = JSONObject().put("id", UUID.randomUUID().toString()).put("driverName", "시연 운전자")
            .put("passengerName", passengerName).put("passengerContact", passengerContact).put("place", destination)
            .put("meetingPoint", meetingPoint).put("note", message).put("meetingAt", meetingAt ?: JSONObject.NULL)
            .put("inviteToken", "demo_${UUID.randomUUID()}").put("pickupMode", if (time == null) "NOW" else "SCHEDULED")
            .put("trip", JSONObject().put("state", "NOT_STARTED")).put("status", "PENDING")
            .put("createdAt", at).put("updatedAt", at).put("expiresAt", meetingMs?.plus(3_600_000) ?: (at + 86_400_000))
            .put("version", 1).put("pickupLocation", location?.toJson() ?: JSONObject.NULL)
        if (clientRequestId != null) a.put("clientRequestId", clientRequestId).put("requestFingerprint", requestFingerprint)
        db.getJSONArray("appointments").put(a)
        write(db.toString())
        view(a, at)
    }

    fun setPickupLocation(a: Appointment, location: PickupLocation): Appointment = synchronized(lock) {
        val db = database()
        val current = find(db, a.id)
        val at = now()
        require(status(current, at) in setOf("PENDING", "CONFIRMED")) { "이미 종료된 약속의 장소는 변경할 수 없습니다." }
        require(a.version == current.getInt("version")) { "약속이 변경됐어요. 최신 내용을 확인한 뒤 다시 저장해 주세요." }
        val target = normalized(location)
        val previous = current.optJSONObject("pickupLocation")?.let(PickupLocation::parse)
        if (previous == target) return@synchronized view(current, at)
        current.put("pickupLocation", target.toJson()).put("trip", JSONObject().put("state", "NOT_STARTED"))
        if (current.getString("status") == "CONFIRMED") current.put("status", "PENDING").put("respondedAt", JSONObject.NULL)
        current.put("updatedAt", at).put("version", current.getInt("version") + 1)
        write(db.toString())
        view(current, at)
    }

    fun cancel(id: String): Appointment = synchronized(lock) {
        val db = database()
        val a = find(db, id)
        val at = now()
        if (status(a, at) == "CANCELLED") return@synchronized view(a, at)
        require(status(a, at) in setOf("PENDING", "CONFIRMED")) { "이미 종료된 약속입니다." }
        a.put("status", "CANCELLED").put("updatedAt", at).put("version", a.getInt("version") + 1)
        write(db.toString())
        view(a, at)
    }

    fun updateTrip(a: Appointment, state: String, observedAt: Long, remainingSeconds: Int? = null): Appointment = synchronized(lock) {
        val db = database()
        val current = find(db, a.id)
        val at = now()
        require(status(current, at) in setOf("PENDING", "CONFIRMED")) { "종료된 픽업은 갱신할 수 없습니다." }
        val target = current.optJSONObject("pickupLocation")?.let(PickupLocation::parse)
        require(target != null && a.pickupLocation?.let(::normalized) == target) { "픽업 목적지가 변경됐어요. 최신 목적지를 다시 확인해 주세요." }
        require(state in setOf("EN_ROUTE", "UNAVAILABLE", "ARRIVED", "STOPPED")) { "이동 상태를 확인해 주세요." }
        require(observedAt in (at - 30_000)..(at + 30_000)) { "최신 내비 정보만 공유할 수 있습니다." }
        val trip = current.optJSONObject("trip") ?: JSONObject().put("state", "NOT_STARTED")
        if (trip.has("observedAt") && !trip.isNull("observedAt") && observedAt <= trip.getLong("observedAt")) return@synchronized view(current, at)
        require(state != "EN_ROUTE" || (remainingSeconds != null && remainingSeconds in 0..86400)) { "도착 예상 시간을 확인해 주세요." }
        if (state == "EN_ROUTE" && (!trip.has("departedAt") || trip.isNull("departedAt"))) trip.put("departedAt", at)
        trip.put("state", state).put("observedAt", observedAt)
            .put("arrivalAt", if (state == "EN_ROUTE") Instant.ofEpochMilli(observedAt + requireNotNull(remainingSeconds) * 1000L).toString() else JSONObject.NULL)
        current.put("trip", trip)
        write(db.toString())
        view(current, at)
    }
}
