package kr.ddakta.pickup

import org.json.JSONObject
import java.time.Instant
import java.util.Locale
import kotlin.math.ceil

data class PickupTrip(val state: String = "NOT_STARTED", val departedAt: Long? = null,
    val updatedAt: Long? = null, val arrivalAt: Instant? = null, val etaFresh: Boolean = false) {
    fun fresh(now: Instant = Instant.now()) = etaFresh && state == "EN_ROUTE" && updatedAt != null && arrivalAt != null &&
        now.toEpochMilli() - updatedAt in -30_000..44_999
    fun label(now: Instant = Instant.now()): String = when {
        state == "ARRIVED" -> "목적지 도착"
        state == "STOPPED" -> "길 안내 종료"
        fresh(now) -> {
            val minutes = ceil(((arrivalAt!!.toEpochMilli() - now.toEpochMilli()).coerceAtLeast(0)) / 60000.0).toInt()
            String.format(Locale.KOREAN, "%d시간 %02d분 후 도착", minutes / 60, minutes % 60)
        }
        departedAt != null -> "도착 시간 확인 중"
        else -> "출발 준비"
    }
    companion object {
        fun parse(o: JSONObject?): PickupTrip {
            if (o == null) return PickupTrip()
            return PickupTrip(o.optString("state", "NOT_STARTED"),
                o.optLong("departedAt").takeIf { o.has("departedAt") && !o.isNull("departedAt") },
                o.optLong("updatedAt").takeIf { o.has("updatedAt") && !o.isNull("updatedAt") },
                o.optString("arrivalAt").takeIf { !o.isNull("arrivalAt") && it.isNotBlank() }?.let(Instant::parse), o.optBoolean("etaFresh"))
        }
    }
}

/** A remaining time belongs to this pickup only after live route identity and freshness checks. */
fun verifiedRemainingSeconds(target: PickupLocation, snapshot: NavigationSnapshot, elapsedMs: Long): Int? {
    val d = snapshot.destination ?: return null
    val updated = snapshot.updatedAtElapsedMs ?: return null
    if (snapshot.activeRoute != true || snapshot.routeRequestPending || elapsedMs - updated !in 0..30_000 ||
        !target.matches(d.latitude, d.longitude, d.poiId, d.poiProvider)) return null
    return d.remainTime.takeIf { it in 0..86400 }
}
