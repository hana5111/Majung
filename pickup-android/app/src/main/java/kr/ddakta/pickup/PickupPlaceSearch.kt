package kr.ddakta.pickup

import org.json.JSONObject
import java.time.Instant

/** Keeps the destination that existed before search separate from the driver's new choice. */
data class PickupPlaceSearch(val keyword: String, val previous: PickupLocation?, val startedAt: Instant = Instant.now()) {
    fun candidate(snapshot: NavigationSnapshot, elapsedMs: Long, now: Instant = Instant.now()): PickupLocation? {
        if (!snapshot.connected || snapshot.activeRoute != true || snapshot.routeRequestPending) return null
        val observed = snapshot.updatedAtElapsedMs ?: return null
        if (elapsedMs - observed !in 0..30_000 || now.isBefore(startedAt) || now.isAfter(startedAt.plusSeconds(1800))) return null
        val location = snapshot.destination?.pickupLocationOrNull() ?: return null
        val sameAsBefore = previous?.matches(location.latitude, location.longitude, location.poiId, location.poiProvider) == true
        // A previous Suwon route must not look like the result of a new Pohang search.
        if (sameAsBefore && !matchesKeyword(location)) return null
        return location
    }

    fun matchesKeyword(location: PickupLocation): Boolean {
        fun normalized(s: String) = s.lowercase().replace(Regex("[^가-힣a-z0-9]"), "")
        val query = normalized(keyword)
        return query.length >= 2 && normalized(location.name + location.address).contains(query)
    }

    fun toJson(): String = JSONObject().put("keyword", keyword).put("startedAt", startedAt.toString())
        .put("previous", previous?.toJson() ?: JSONObject.NULL).toString()

    companion object {
        fun parse(raw: String) = JSONObject(raw).let {
            PickupPlaceSearch(it.getString("keyword"), it.optJSONObject("previous")?.let(PickupLocation::parse), Instant.parse(it.getString("startedAt")))
        }
    }
}

internal fun readyForPlaceSearch(snapshot: NavigationSnapshot) = snapshot.connected && snapshot.destinationReceived && snapshot.activeRoute != null
