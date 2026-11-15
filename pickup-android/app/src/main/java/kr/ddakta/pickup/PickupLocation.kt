package kr.ddakta.pickup

import org.json.JSONObject
import kotlin.math.*

data class PickupLocation(
    val latitude: Double, val longitude: Double, val name: String, val address: String = "",
    val displayLatitude: Double? = null, val displayLongitude: Double? = null,
    val poiId: String = "", val poiSubId: String = "", val poiProvider: String = ""
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0)
        require((displayLatitude == null && displayLongitude == null) ||
            (displayLatitude != null && displayLatitude.isFinite() && displayLatitude in -90.0..90.0 &&
                displayLongitude != null && displayLongitude.isFinite() && displayLongitude in -180.0..180.0))
        require(name.isNotBlank() && name.length <= 100 && address.length <= 240 && poiId.length <= 160 && poiSubId.length <= 80 && poiProvider.length <= 80)
        require(listOf(name, address, poiId, poiSubId, poiProvider).none { text -> text.any { it.isISOControl() } })
    }

    fun toJson(): JSONObject = JSONObject().put("latitude", latitude).put("longitude", longitude).put("name", name).put("address", address)
        .put("displayLatitude", displayLatitude ?: JSONObject.NULL).put("displayLongitude", displayLongitude ?: JSONObject.NULL)
        .put("poiId", poiId).put("poiSubId", poiSubId).put("poiProvider", poiProvider)

    fun matches(latitude: Double, longitude: Double, poiId: String = "", poiProvider: String = ""): Boolean {
        if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return false
        if (this.poiId.isNotBlank() && poiId.isNotBlank() && this.poiId != poiId) return false
        if (this.poiProvider.isNotBlank() && poiProvider.isNotBlank() && this.poiProvider != poiProvider) return false
        val latDelta = Math.toRadians(latitude - this.latitude)
        val lonDelta = Math.toRadians(longitude - this.longitude)
        val h = sin(latDelta / 2).pow(2) + cos(Math.toRadians(this.latitude)) * cos(Math.toRadians(latitude)) * sin(lonDelta / 2).pow(2)
        return 6_371_000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0))) <= 15
    }

    companion object {
        fun parse(o: JSONObject): PickupLocation {
            fun coordinate(key: String, optional: Boolean = false): Double? {
                if (optional && (!o.has(key) || o.isNull(key))) return null
                require(o.get(key) is Number)
                return o.getDouble(key)
            }
            return PickupLocation(requireNotNull(coordinate("latitude")), requireNotNull(coordinate("longitude")), o.getString("name"),
                o.optString("address"), coordinate("displayLatitude", true), coordinate("displayLongitude", true),
                o.optString("poiId"), o.optString("poiSubId"), o.optString("poiProvider"))
        }
    }
}

internal fun verifiedDepartureLocation(displayed: Appointment, latest: Appointment?): PickupLocation {
    require(latest != null && latest.id == displayed.id && latest.active) { "이미 종료됐거나 찾을 수 없는 약속이에요. 최신 약속을 확인해 주세요." }
    val target = latest.pickupLocation
    require(target != null) { "정확한 승차 위치를 먼저 선택해 주세요." }
    require(target == displayed.pickupLocation) { "승차 위치가 변경됐어요. 새 위치를 확인한 뒤 다시 출발해 주세요." }
    return target
}
