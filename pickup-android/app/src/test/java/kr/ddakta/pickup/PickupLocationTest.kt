package kr.ddakta.pickup

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PickupLocationTest {
    private val location = PickupLocation(37.265, 127.001, "테스트역 동측 승차장", "검증용 주소", poiId = "poi-test", poiSubId = "2", poiProvider = "test-provider")
    private val a = Appointment("a72ef09e-bde8-4a1c-9477-317e885862f2", "아버지", "지우", "테스트역", "동측 승차장",
        Instant.parse("2030-11-15T09:00:00Z"), "", "PENDING", "a".repeat(43), "", location, 2)

    @Test fun coordinatesAndPoiIdentityRoundTripWithoutSwappingLatitudeAndLongitude() {
        assertEquals(location, PickupLocation.parse(location.toJson()))
        val route = location.toRouteInfo()
        assertEquals(37.265, route.latitude, 0.0); assertEquals(127.001, route.longitude, 0.0)
        assertEquals("poi-test", route.poiId); assertEquals("2", route.poiSubId); assertEquals("test-provider", route.poiProvider)
        assertEquals(location.name, route.poiName); assertEquals(location.address, route.address)
    }

    @Test fun invalidOrPartialCoordinatesCannotBecomeARoute() {
        for (pair in listOf(127.001 to 37.265, Double.NaN to 127.001, 37.265 to Double.POSITIVE_INFINITY, 37.265 to -181.0))
            assertThrows(IllegalArgumentException::class.java) { location.copy(latitude = pair.first, longitude = pair.second) }
        assertThrows(IllegalArgumentException::class.java) { location.copy(displayLatitude = 37.265) }
        assertThrows(IllegalArgumentException::class.java) { PickupLocation.parse(location.toJson().put("latitude", "37.265")) }
    }

    @Test fun legacyAppointmentHasNoGuessedCoordinatesAndCannotDepart() {
        val old = JSONObject().put("id", a.id).put("driverName", "아버지").put("passengerName", "지우")
            .put("place", "테스트역").put("meetingPoint", "동측 승차장").put("meetingAt", a.meetingAt.toString()).put("status", "PENDING")
        val parsed = Appointment.parse(old)
        assertNull(parsed.pickupLocation)
        assertThrows(IllegalArgumentException::class.java) { verifiedDepartureLocation(parsed, parsed) }
    }

    @Test fun cancelledMissingOrAnotherOwnersAppointmentCannotStartFromAStaleScreen() {
        for (latest in listOf(null, a.copy(status = "CANCELLED"), a.copy(status = "EXPIRED"), a.copy(id = "other-id")))
            assertThrows(IllegalArgumentException::class.java) { verifiedDepartureLocation(a, latest) }
    }

    @Test fun departureRejectsChangedCoordinatesButAllowsAnAcceptanceVersionChange() {
        assertThrows(IllegalArgumentException::class.java) { verifiedDepartureLocation(a, a.copy(pickupLocation = location.copy(latitude = 37.266), version = 3)) }
        assertEquals(location, verifiedDepartureLocation(a, a.copy(status = "CONFIRMED", version = 3)))
    }

    @Test fun anotherDestinationOrPoiIsNotShownAsThePickupRoute() {
        assertTrue(location.matches(37.265, 127.001, "poi-test", "test-provider"))
        assertTrue(location.matches(37.26501, 127.00101, "poi-test", "test-provider"))
        assertFalse(location.matches(37.266, 127.001, "poi-test", "test-provider"))
        assertFalse(location.matches(37.265, 127.001, "different-poi", "test-provider"))
        assertFalse(location.matches(Double.NaN, 127.001))
    }

    @Test fun invitationReviewIncludesTheSelectedLocationButDoesNotExposeItsTokenInPreview() {
        val sms = InvitationSms.prepare(a, "https://pickup.example", Instant.parse("2026-11-15T00:00:00Z"))
        assertTrue(sms.body.contains("선택한 승차 위치: ${location.name}"))
        assertTrue(sms.body.contains(location.address))
        assertFalse(sms.preview.contains(a.inviteToken))
    }
}
