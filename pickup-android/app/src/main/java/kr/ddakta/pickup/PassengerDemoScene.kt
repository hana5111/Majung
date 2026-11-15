package kr.ddakta.pickup

import org.json.JSONObject
import kotlin.math.*

/** Fictional person/car coordinates around the appointment's selected pickup point. */
internal object PassengerDemoScene {
    data class Point(val latitude: Double, val longitude: Double) {
        fun json() = JSONObject().put("latitude", latitude).put("longitude", longitude)
    }
    data class Scene(val pickup: Point, val car: Point, val moving: Point, val nearby: Point) {
        fun distanceMeters(near: Boolean): Int {
            val person = if (near) nearby else moving
            val lat = Math.toRadians(person.latitude - pickup.latitude)
            val lon = Math.toRadians(person.longitude - pickup.longitude)
            val h = sin(lat / 2).pow(2) + cos(Math.toRadians(pickup.latitude)) *
                cos(Math.toRadians(person.latitude)) * sin(lon / 2).pow(2)
            val meters = 6_371_000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
            return (meters / 5).roundToInt() * 5
        }

        fun json(accepted: Boolean, near: Boolean, passengerName: String) = JSONObject()
            .put("accepted", accepted).put("nearby", near).put("passengerName", passengerName)
            .put("pickup", pickup.json()).put("car", car.json())
            .put("moving", moving.json()).put("near", nearby.json()).toString()
    }

    fun around(location: PickupLocation): Scene {
        val latitude = (location.displayLatitude ?: location.latitude).coerceIn(-85.0, 85.0)
        val longitude = location.displayLongitude ?: location.longitude
        val metersPerDegree = Math.PI * 6_371_000 / 180
        fun offset(north: Double, east: Double = 0.0) = Point(
            (latitude + north / metersPerDegree).coerceIn(-85.0, 85.0),
            ((longitude + east / (metersPerDegree * cos(Math.toRadians(latitude))) + 540) % 360) - 180)
        return Scene(Point(latitude, longitude), offset(-90.0, 70.0), offset(120.0), offset(25.0))
    }
}
