package kr.ddakta.pickup

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LocalPickupStoreTest {
    private var stored: String? = null
    private var writes = 0
    private var clock = Instant.parse("2026-11-15T00:00:00Z").toEpochMilli()
    private val sharedLock = Any()
    private val location = PickupLocation(37.265, 127.001, "테스트역 동측 승차장", "검증용 주소", poiId = "test", poiProvider = "test")
    private fun store() = LocalPickupStore({ stored }, { stored = it; writes++ }, { clock }, sharedLock)
    private fun create(store: LocalPickupStore = store(), requestId: String = UUID.randomUUID().toString(), target: PickupLocation? = location) =
        store.create("지우", "", "테스트역", "", null, "기능 검증용", requestId, target)

    @Test fun creationAndTripRemainAvailableAfterReopeningWithoutANetworkRepository() {
        val first = store()
        val a = create(first)
        assertNull(a.meetingAt)
        assertEquals("NOW", a.pickupMode)
        assertEquals("PENDING", a.status)
        assertEquals("테스트역 동측 승차장", a.place)
        assertTrue(a.inviteToken.startsWith("demo_"))
        val moving = first.updateTrip(a, "EN_ROUTE", clock, 720)
        val reopened = store().list().single()
        assertEquals(a.id, reopened.id)
        assertEquals(a.inviteToken, reopened.inviteToken)
        assertEquals(location, reopened.pickupLocation)
        assertEquals(moving.trip, reopened.trip)
        assertEquals(clock, reopened.trip.departedAt)
        assertEquals(Instant.ofEpochMilli(clock + 720_000), reopened.trip.arrivalAt)
    }

    @Test fun aCreationKeyReplaysTheSameRecordAfterReopeningAndExpiryButRejectsChangedDetails() {
        val requestId = UUID.randomUUID().toString()
        val a = create(requestId = requestId)
        val writesAfterCreation = writes
        assertEquals(a.id, create(store(), requestId).id)
        assertEquals(writesAfterCreation, writes)
        assertThrows(IllegalArgumentException::class.java) {
            store().create("다른 사람", "", "테스트역", "", null, "기능 검증용", requestId, location)
        }
        assertThrows(IllegalArgumentException::class.java) { create(requestId = requestId, target = location.copy(longitude = 127.002)) }
        clock += 86_400_001
        val expiredReplay = create(store(), requestId)
        assertEquals(a.id, expiredReplay.id)
        assertEquals(a.inviteToken, expiredReplay.inviteToken)
        assertEquals("EXPIRED", expiredReplay.status)
        assertEquals(1, store().list().size)
        assertEquals(writesAfterCreation, writes)
    }

    @Test fun scheduledTimesExpireAfterAnHourAndReadOnlyViewsDoNotPersistDerivedState() {
        val meetingAt = Instant.ofEpochMilli(clock + 3_600_000)
        val a = store().create("지우", "", "테스트역", "동측 승차장", meetingAt, "")
        assertEquals(meetingAt, a.meetingAt)
        assertEquals("SCHEDULED", a.pickupMode)
        val before = stored
        clock += 7_200_000
        assertEquals("EXPIRED", store().list().single().status)
        assertEquals(before, stored)
        assertThrows(IllegalArgumentException::class.java) { store().cancel(a.id) }
    }

    @Test fun changedLocationsRequireTheCurrentVersionAndResetApprovalAndTrip() {
        val a = create()
        store().updateTrip(a, "EN_ROUTE", clock, 720)
        // A persisted confirmed appointment must retain the server's re-review semantics too.
        val db = JSONObject(stored!!)
        db.getJSONArray("appointments").getJSONObject(0).put("status", "CONFIRMED").put("version", 2).put("respondedAt", clock)
        stored = db.toString()
        val accepted = store().list().single()
        val newLocation = location.copy(longitude = 127.002)
        assertThrows(IllegalArgumentException::class.java) { store().setPickupLocation(a, newLocation) }
        val changed = store().setPickupLocation(accepted, newLocation)
        assertEquals(3, changed.version)
        assertEquals("PENDING", changed.status)
        assertEquals("NOT_STARTED", changed.trip.state)
        assertNull(changed.trip.departedAt)
        assertTrue(JSONObject(stored!!).getJSONArray("appointments").getJSONObject(0).isNull("respondedAt"))
        assertThrows(IllegalArgumentException::class.java) { store().updateTrip(a, "EN_ROUTE", clock, 600) }
        val before = writes
        assertEquals(changed, store().setPickupLocation(changed, newLocation))
        assertEquals(before, writes)
    }

    @Test fun onlyFreshNewerTelemetryForTheSameDestinationChangesTheStoredEta() {
        val a = create()
        val started = store().updateTrip(a, "EN_ROUTE", clock, 720)
        clock += 10_000
        val latest = store().updateTrip(a, "EN_ROUTE", clock, 600)
        assertEquals(a.version, latest.version)
        assertEquals(started.trip.departedAt, latest.trip.departedAt)
        val before = writes
        assertEquals(latest.trip, store().updateTrip(a, "EN_ROUTE", clock - 5000, 999).trip)
        assertEquals(before, writes)
        assertThrows(IllegalArgumentException::class.java) { store().updateTrip(a, "EN_ROUTE", clock - 30_001, 600) }
        assertThrows(IllegalArgumentException::class.java) { store().updateTrip(a, "EN_ROUTE", clock + 30_001, 600) }
        assertThrows(IllegalArgumentException::class.java) { store().updateTrip(a, "FAKE", clock + 1) }
        assertThrows(IllegalArgumentException::class.java) { store().updateTrip(a, "EN_ROUTE", clock + 1, -1) }
        assertThrows(IllegalArgumentException::class.java) { store().updateTrip(a, "EN_ROUTE", clock + 1, 86401) }
        clock += 45_000
        val stale = store().list().single()
        assertFalse(stale.trip.etaFresh)
        assertNull(stale.trip.arrivalAt)
        assertEquals("도착 시간 확인 중", stale.timeLabel(Instant.ofEpochMilli(clock)))
    }

    @Test fun stoppingAndCancellationArePersistentAndRepeatedCancellationIsANoOp() {
        val a = create()
        val started = store().updateTrip(a, "EN_ROUTE", clock, 720)
        clock += 1000
        val stopped = store().updateTrip(a, "STOPPED", clock)
        assertEquals("STOPPED", stopped.trip.state)
        assertNull(stopped.trip.arrivalAt)
        assertEquals(started.trip.departedAt, stopped.trip.departedAt)
        val cancelled = store().cancel(a.id)
        assertEquals("CANCELLED", cancelled.status)
        assertEquals(a.version + 1, cancelled.version)
        val before = writes
        assertEquals(cancelled, store().cancel(a.id))
        assertEquals(before, writes)
        assertEquals(cancelled, store().list().single())
        assertThrows(IllegalArgumentException::class.java) { store().updateTrip(a, "EN_ROUTE", clock + 1, 600) }
        assertThrows(IllegalArgumentException::class.java) { store().setPickupLocation(cancelled, location.copy(longitude = 127.002)) }
    }

    @Test fun validationFailuresDoNotCreatePartialAppointments() {
        val store = store()
        assertThrows(IllegalArgumentException::class.java) { store.create(" ", "", "테스트역", "", null, "") }
        assertThrows(IllegalArgumentException::class.java) { store.create("지우", "<script>", "테스트역", "", null, "") }
        assertThrows(IllegalArgumentException::class.java) { store.create("지우", "", "테스트역", "", Instant.ofEpochMilli(clock + 1000), "") }
        assertThrows(IllegalArgumentException::class.java) { store.create("지우", "", "테스트역", "동측", Instant.ofEpochMilli(clock - 1), "") }
        assertThrows(IllegalArgumentException::class.java) { store.create("지우", "", "테스트역", "동측", Instant.ofEpochMilli(clock + 367L * 86_400_000), "") }
        assertThrows(IllegalArgumentException::class.java) { create(store, "not-a-uuid") }
        assertNull(stored)
        assertTrue(store.list().isEmpty())
    }

    @Test fun separateRepositoryInstancesUsingTheSameLockDoNotLoseConcurrentCreations() {
        val stores = List(4) { store() }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0 until 24).map { index -> Callable { create(stores[index % stores.size]).id } }
            val ids = pool.invokeAll(tasks).map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(24, ids.toSet().size)
            assertEquals(ids.toSet(), store().list().map { it.id }.toSet())
        } finally { pool.shutdownNow() }
    }

    @Test fun concurrentRetriesOfOneCreationKeyReturnOnePersistedAppointment() {
        val stores = List(4) { store() }
        val requestId = UUID.randomUUID().toString()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0 until 12).map { index -> Callable { create(stores[index % stores.size], requestId).id } }
            val ids = pool.invokeAll(tasks).map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, ids.toSet().size)
            assertEquals(1, store().list().size)
            assertEquals(1, writes)
        } finally { pool.shutdownNow() }
    }

    @Test fun failedPersistenceIsReportedAndNeverBecomesASuccessfulInMemoryCreation() {
        val failing = LocalPickupStore({ stored }, { throw IllegalStateException("저장하지 못했어요.") }, { clock }, sharedLock)
        assertThrows(IllegalStateException::class.java) { create(failing) }
        assertNull(stored)
        assertTrue(store().list().isEmpty())
    }
}
