package kr.ddakta.pickup.gleo

import kr.ddakta.pickup.PickupZone
import kr.ddakta.pickup.VoiceDraft
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

object GleoJson {
    internal val parameterNames = setOf("operation", "sessionId", "confirmationToken", "passengerName", "place", "meetingPoint", "meetingAt", "meetingTimeText")
    fun request(raw: String): GleoRequest {
        require(raw.length <= 8192) { "Action parameters are too large." }
        // The official examples show both a JSON object and a JSON-encoded object string.
        fun parseValue(text: String): Any? {
            val tokenizer = JSONTokener(text)
            val result = tokenizer.nextValue()
            require(tokenizer.nextClean() == '\u0000') { "Trailing action parameters are not allowed." }
            return result
        }
        var parsed: Any? = parseValue(raw)
        if (parsed is String) parsed = parseValue(parsed)
        require(parsed is JSONObject) { "Action parameters must be a JSON object." }
        val o = parsed
        require(o.keys().asSequence().all { it in parameterNames }) { "Unknown pickup parameter." }
        fun value(name: String): String? {
            if (!o.has(name)) return null
            require(o.get(name) is String) { "Pickup parameters must be strings." }
            // Gleo can include an unknown optional value as an empty string.
            return o.getString(name).takeIf { it.isNotBlank() }
        }
        return GleoRequest(value("operation") ?: "collect", value("sessionId"), value("confirmationToken"), value("passengerName"), value("place"), value("meetingPoint"), value("meetingAt"), value("meetingTimeText"))
    }
    fun reply(r: GleoReply, now: Instant = Instant.now()): JSONObject {
        val o = JSONObject().put("functionName", r.functionName).put("status", r.status).put("message", r.message).put("mode", r.mode)
        r.session?.let { s ->
            val detail = JSONObject().put("sessionId", s.id).put("draftVersion", s.version).put("phase", s.phase.name)
                .put("passengerName", s.draft.name).put("place", s.draft.place).put("meetingPoint", s.draft.point)
                .put("timezone", PickupZone.id).put("currentDateTime", now.atZone(PickupZone).toOffsetDateTime().toString())
                .put("missingFields", JSONArray(r.missingFields)).put("requiresConfirmation", s.phase == GleoPhase.REVIEW)
                .put("prompt", r.prompt).put("expiresAt", s.expiresAt.toString())
            s.draft.meetingAt()?.let { detail.put("meetingAt", it.toString()) }
            s.draft.date?.let { detail.put("meetingDate", it.toString()) }
            s.draft.time?.let { detail.put("meetingTime", it.toString()) }
            s.draft.ambiguousHour?.let { detail.put("hourAwaitingMeridiem", it) }
            if (s.phase == GleoPhase.REVIEW || s.phase == GleoPhase.SUBMITTING) s.confirmationToken?.let { detail.put("confirmationToken", it) }
            s.appointmentId?.let { detail.put("appointmentId", it) }
            s.invitationStatus?.let { detail.put("invitationStatusAtCreation", it) }
            if (s.phase == GleoPhase.SUBMITTING) detail.put("creationState", "unknown")
            o.put("data", JSONArray().put(detail).toString())
        }
        return o
    }
    fun encode(sessions: List<GleoSession>): String = JSONArray().apply {
        sessions.forEach { s -> put(JSONObject().put("id", s.id).put("phase", s.phase.name).put("version", s.version)
            .put("expiresAt", s.expiresAt.toString()).put("name", s.draft.name).put("place", s.draft.place).put("point", s.draft.point)
            .put("date", s.draft.date?.toString()).put("time", s.draft.time?.toString())
            .put("ambiguousHour", s.draft.ambiguousHour).put("ambiguousMinute", s.draft.ambiguousMinute)
            .put("confirmationToken", s.confirmationToken).put("appointmentId", s.appointmentId).put("invitationStatus", s.invitationStatus)) }
    }.toString()
    fun decode(raw: String): List<GleoSession> {
        val a = JSONArray(raw)
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            fun value(k: String) = if(o.has(k) && !o.isNull(k)) o.getString(k) else null
            GleoSession(o.getString("id"), VoiceDraft(o.getString("name"), o.getString("place"), o.getString("point"), value("date")?.let(LocalDate::parse), value("time")?.let(LocalTime::parse),
                ambiguousHour = if (o.has("ambiguousHour") && !o.isNull("ambiguousHour")) o.getInt("ambiguousHour") else null, ambiguousMinute = o.optInt("ambiguousMinute", 0)),
                GleoPhase.valueOf(o.getString("phase")), value("confirmationToken"), o.getInt("version"), Instant.parse(o.getString("expiresAt")), value("appointmentId"), value("invitationStatus"))
        }
    }
}
