package kr.ddakta.pickup

import ai.pleos.playground.navi.data.DestinationInfo
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PickupPlaceSearchTest {
    private val now = Instant.parse("2026-11-15T00:00:00Z")
    private val suwon = PickupLocation(37.265, 127.001, "수원역 KTX", "수원시", poiId = "suwon")
    private val pohang = PickupLocation(36.01, 129.32, "포항공과대학교 C5", "포항시", poiId = "pohang-c5")
    private fun snapshot(place: PickupLocation) = NavigationSnapshot(connected = true, activeRoute = true,
        destinationReceived = true, updatedAtElapsedMs = 1000,
        destination = DestinationInfo(latitude = place.latitude, longitude = place.longitude, name = place.name,
            address = place.address, remainDistance = 100, remainTime = 60, tollFare = 0, poiId = place.poiId, poiSubId = "0"))

    @Test fun oldSuwonRouteIsNeverShownAsTheResultOfAPohangSearch() {
        val search = PickupPlaceSearch("포항공과대학교 c5", suwon, now)
        assertNull(search.candidate(snapshot(suwon), 2000, now))
        assertEquals(pohang, search.candidate(snapshot(pohang), 2000, now)?.copy(poiSubId = ""))
    }

    @Test fun typingAQueryOrARecentDestinationDoesNotCountAsChoosingAPlace() {
        val search = PickupPlaceSearch("포항공과대학교 c5", null, now)
        assertNull(search.candidate(NavigationSnapshot(connected = true, activeRoute = false, recentPlaces = listOf(suwon)), 2000, now))
        assertNull(search.candidate(snapshot(pohang).copy(activeRoute = false), 2000, now))
    }

    @Test fun disconnectedOldOrUnconfirmedRoutesAreNotPresentedForDeparture() {
        val search = PickupPlaceSearch("포항공과대학교 c5", suwon, now)
        for (state in listOf(snapshot(pohang).copy(connected = false), snapshot(pohang).copy(routeRequestPending = true), snapshot(pohang).copy(updatedAtElapsedMs = null)))
            assertNull(search.candidate(state, 2000, now))
        assertNull(search.candidate(snapshot(pohang), 32000, now))
        assertNull(search.candidate(snapshot(pohang), 2000, now.plusSeconds(1801)))
    }

    @Test fun anExistingRouteMatchingTheQueryCanBeExplicitlyConfirmedButNotReplacedWithAnUnrelatedOne() {
        assertNotNull(PickupPlaceSearch("수원역", suwon, now).candidate(snapshot(suwon), 2000, now))
        assertNull(PickupPlaceSearch("포항", suwon, now).candidate(snapshot(suwon), 2000, now))
    }

    @Test fun searchBaselineSurvivesRecreationAndMustBeReadBeforeOpeningSearch() {
        val search = PickupPlaceSearch("포항공과대학교 c5", suwon, now)
        assertEquals(search, PickupPlaceSearch.parse(search.toJson()))
        assertFalse(readyForPlaceSearch(snapshot(suwon).copy(destinationReceived = false)))
        assertTrue(readyForPlaceSearch(snapshot(suwon)))
    }
}
