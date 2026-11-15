package kr.ddakta.pickup

import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.constants.NaviMode

internal fun hasActivePickupRoute(info: RouteStateInfo) = info.termsAgreement &&
    (info.isRouting || info.naviMode == NaviMode.ROUTE_GUIDING)

internal fun DestinationInfo.pickupLocationOrNull(): PickupLocation? = runCatching {
    PickupLocation(latitude, longitude, name, address, displayLatitude, displayLongitude, poiId, poiSubId.orEmpty(), poiProvider)
}.getOrNull()

internal fun RecentDestinationInfo.pickupLocationOrNull(): PickupLocation? = runCatching {
    PickupLocation(latitude, longitude, name, address, displayLatitude, displayLongitude, poiId, poiSubId.orEmpty(), poiProvider)
}.getOrNull()

internal fun PickupLocation.toRouteInfo() = RouteInfo(latitude = latitude, longitude = longitude,
    displayLatitude = displayLatitude, displayLongitude = displayLongitude, poiName = name,
    poiId = poiId, address = address, poiSubId = poiSubId.ifBlank { "0" }, poiProvider = poiProvider)
