package kr.ddakta.pickup

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PassengerDemoSceneTest {
    @Test fun displayedDistanceIsCalculatedFromTheCoordinatesSentToTheMap() {
        val model = PassengerDemoScene.around(PickupLocation(37.26620, 127.00130, "테스트역"))
        assertEquals(120, model.distanceMeters(false))
        assertEquals(25, model.distanceMeters(true))
        val scene = JSONObject(model.json(accepted = true, near = true, passengerName = "민수"))
        assertTrue(scene.getBoolean("accepted"))
        assertTrue(scene.getBoolean("nearby"))
        assertEquals("민수", scene.getString("passengerName"))
        assertEquals(model.pickup.latitude, scene.getJSONObject("pickup").getDouble("latitude"), 0.0)
        assertEquals(model.nearby.longitude, scene.getJSONObject("near").getDouble("longitude"), 0.0)
    }
    @Test fun mapUsesTheSelectedAppointmentRatherThanTheOldFixedStationScenario() {
        val selected = PickupLocation(36.04, 129.38, "테스트 해변", displayLatitude = 36.05, displayLongitude = 129.39)
        val model = PassengerDemoScene.around(selected)
        assertEquals(36.05, model.pickup.latitude, 0.0)
        assertEquals(129.39, model.pickup.longitude, 0.0)
        assertEquals(120, model.distanceMeters(false))
        assertEquals(25, model.distanceMeters(true))
    }
}
