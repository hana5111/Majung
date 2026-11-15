package kr.ddakta.pickup.gleo

import org.json.JSONObject
import java.time.Instant
import kr.ddakta.pickup.PickupPlaceSearch

/** A confirmed voice pickup requests a search, never an inferred POI or departure. */
data class GleoNavigationHandoff(
    val sessionId: String, val appointmentId: String, val keyword: String,
    val expiresAt: Instant, val pending: Boolean = true, val search: PickupPlaceSearch? = null
) {
    fun toJson(): String = JSONObject().put("sessionId", sessionId).put("appointmentId", appointmentId)
        .put("keyword", keyword).put("expiresAt", expiresAt.toString()).put("pending", pending)
        .put("search", search?.toJson() ?: JSONObject.NULL).toString()

    companion object {
        fun from(reply: GleoReply, now: Instant = Instant.now()): GleoNavigationHandoff? {
            val s = reply.session ?: return null
            if (reply.status != "success" || s.phase != GleoPhase.CREATED || !s.expiresAt.isAfter(now)) return null
            val id = s.appointmentId?.takeIf { it.isNotBlank() } ?: return null
            val keyword = listOf(s.draft.place.trim(), s.draft.point.trim()).filter { it.isNotEmpty() }.joinToString(" ")
            if (keyword.isBlank() || keyword.length > 200 || keyword.any { it.isISOControl() }) return null
            return GleoNavigationHandoff(s.id, id, keyword, s.expiresAt)
        }

        fun parse(raw: String): GleoNavigationHandoff = JSONObject(raw).let {
            GleoNavigationHandoff(it.getString("sessionId"), it.getString("appointmentId"), it.getString("keyword"),
                Instant.parse(it.getString("expiresAt")), it.getBoolean("pending"),
                it.optString("search").takeIf { raw -> !it.isNull("search") && raw.isNotBlank() }?.let(PickupPlaceSearch::parse))
        }
    }
}
