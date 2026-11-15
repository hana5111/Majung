package kr.ddakta.pickup

import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.constants.RouteOption
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PickupTripTest {
    private val now = Instant.parse("2026-11-15T00:00:00Z")
    private val target = PickupLocation(37.265, 127.001, "테스트역", poiId = "test", poiProvider = "test")
    private val destination = DestinationInfo(37.265, 127.001, name = "테스트역", address = "", remainDistance = 3000,
        remainTime = 720, tollFare = 0, poiId = "test", poiSubId = "", poiProvider = "test")
    @Test fun routeGuidingModeIsAcceptedEvenWhenTheEmulatorRoutingFlagIsFalse() {
        val state = RouteStateInfo(true, NaviMode.ROUTE_GUIDING, false, RouteOption.RECOMMENDED, 80, emptyList(), null)
        assertTrue(hasActivePickupRoute(state))
        assertFalse(hasActivePickupRoute(state.copy(termsAgreement = false)))
        assertFalse(hasActivePickupRoute(state.copy(naviMode = NaviMode.SAFE_GUIDING)))
        assertFalse(hasActivePickupRoute(state.copy(naviMode = NaviMode.NOT_WORKING)))
    }
    @Test fun publishesOnlyFreshConfirmedNavigationForTheExactPickup() {
        val state = NavigationSnapshot(connected = true, activeRoute = true, destination = destination, updatedAtElapsedMs = 1000)
        assertEquals(720, verifiedRemainingSeconds(target, state, 2000))
        for (bad in listOf(state.copy(activeRoute = false), state.copy(routeRequestPending = true),
            state.copy(destination = destination.copy(longitude = 128.0)), state.copy(destination = destination.copy(poiId = "another")),
            state.copy(updatedAtElapsedMs = null), state.copy(destination = destination.copy(remainTime = -1))))
            assertNull(verifiedRemainingSeconds(target, bad, 2000))
        assertNull(verifiedRemainingSeconds(target, state, 31001))
    }
    @Test fun cachedEtaExpiresEvenWithoutAnotherSuccessfulServerFetch() {
        val trip = PickupTrip("EN_ROUTE", now.toEpochMilli(), now.toEpochMilli(), now.plusSeconds(720), true)
        assertEquals("0시간 12분 후 도착", trip.label(now))
        assertEquals("도착 시간 확인 중", trip.label(now.plusSeconds(45)))
        assertEquals("목적지 도착", trip.copy(state = "ARRIVED").label(now))
        assertEquals("길 안내 종료", trip.copy(state = "STOPPED").label(now))
    }
    @Test fun immediateAppointmentParsesWithoutAnyFabricatedMeetingTime() {
        val parsed = Appointment.parse(JSONObject().put("id", "test").put("driverName", "테스트").put("passengerName", "지우")
            .put("place", "테스트역").put("meetingAt", JSONObject.NULL).put("pickupMode", "NOW").put("status", "PENDING"))
        assertNull(parsed.meetingAt); assertFalse(parsed.canShare); assertEquals("출발 준비", parsed.timeLabel(now))
    }
    @Test fun smsOnlyBecomesAvailableAfterDepartureAndDoesNotFreezeAnEtaInTheMessage() {
        val a = Appointment("test", "운전자", "지우", "테스트역", "", null, "", "PENDING", "a".repeat(43), "", target,
            pickupMode = "NOW", trip = PickupTrip())
        assertThrows(IllegalArgumentException::class.java) { InvitationSms.prepare(a, "https://pickup.example", now) }
        val moving = a.copy(trip = PickupTrip("EN_ROUTE", now.toEpochMilli(), now.toEpochMilli(), now.plusSeconds(720), true))
        val sms = InvitationSms.prepare(moving, "https://pickup.example", now)
        assertTrue(sms.body.contains("현재 도착 예상 시간")); assertTrue(sms.body.contains("/invite#"))
        assertFalse(sms.body.contains("12분")); assertFalse(sms.preview.contains(a.inviteToken))
    }
}
