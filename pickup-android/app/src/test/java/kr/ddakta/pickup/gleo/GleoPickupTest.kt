package kr.ddakta.pickup.gleo

import org.junit.Assert.*
import org.junit.Test
import kr.ddakta.pickup.PickupZone
import java.time.Instant
import java.util.UUID

class GleoPickupTest {
    private var now = Instant.parse("2026-11-15T00:00:00Z")
    private val store = MemoryStore()
    private var creates = 0
    private val core = GleoPickup(store, create = { _, _ -> creates++; CreatedPickup("appointment-1", "PENDING") }, now = { now })
    private val details = GleoRequest(passengerName = "지우", place = "수원역")
    private class MemoryStore : GleoSessionStore {
        var state = emptyList<GleoSession>()
        override fun read() = state
        override fun write(sessions: List<GleoSession>) { state = sessions }
    }
    private fun review() = core.handle(details).session!!
    private fun confirm(s: GleoSession) = GleoRequest("confirm", s.id, s.confirmationToken)

    @Test fun asksOnlyForMissingPassengerOrDestination() {
        val first = core.handle(GleoRequest(passengerName = "지우"))
        assertEquals(listOf("place"), first.missingFields)
        assertFalse(first.prompt.contains("언제"))
        assertEquals(GleoPhase.REVIEW, core.handle(GleoRequest(place = "수원역")).session!!.phase)
        assertEquals(0, creates)
    }
    @Test fun nameAndDestinationAreSufficientWithoutTimeOrBoardingPoint() {
        val result = core.handle(details)
        assertEquals(GleoPhase.REVIEW, result.session!!.phase)
        assertTrue(result.missingFields.isEmpty())
        assertNull(result.session!!.draft.meetingAt())
        assertNotNull(result.session!!.confirmationToken)
        assertEquals(0, creates)
        assertEquals("success", core.handle(confirm(result.session!!)).status)
        assertEquals(1, creates)
    }
    @Test fun obsoleteGleoDateArgumentsNeverTriggerDateQuestionsOrInventMeetingTime() {
        val result = core.handle(details.copy(meetingAt = "2023-10-26T18:00:00+09:00", meetingTimeText = "알 수 없는 날짜"))
        assertEquals(GleoPhase.REVIEW, result.session!!.phase)
        assertNull(result.session!!.draft.meetingAt())
        assertFalse(result.prompt.contains("날짜"))
        assertFalse(org.json.JSONArray(GleoJson.reply(result).getString("data")).getJSONObject(0).has("meetingAt"))
    }
    @Test fun firstConfirmationStillRequiresReview() {
        assertEquals("asking", core.handle(details.copy(operation = "confirm")).status)
        assertEquals(0, creates)
    }
    @Test fun incorrectReferencesNeverCreate() {
        val s = review()
        for (r in listOf(GleoRequest("confirm", s.id, "wrong"), GleoRequest("confirm", s.id), details.copy(operation = "confirm", confirmationToken = "wrong")))
            assertEquals("asking", core.handle(r).status)
        assertEquals(0, creates)
    }
    @Test fun spokenApprovalWithMatchingDetailsCreatesOnceWithoutInternalReferences() {
        val s = review()
        val approval = details.copy(operation = "confirm")
        assertEquals(s.id, core.handle(approval).session!!.id)
        assertEquals("success", core.handle(approval).status)
        assertEquals(1, creates)
    }
    @Test fun incompleteReferenceFreeApprovalDoesNotCreate() {
        review()
        for (r in listOf(GleoRequest("confirm"), details.copy(operation = "confirm", place = null), details.copy(operation = "confirm", passengerName = null)))
            assertEquals("asking", core.handle(r).status)
        assertEquals(0, creates)
    }
    @Test fun changedDestinationInvalidatesApprovalUntilReviewedAgain() {
        val old = review()
        val next = core.handle(details.copy(operation = "confirm", place = "서울역"))
        assertEquals("asking", next.status)
        assertNotEquals(old.confirmationToken, next.session!!.confirmationToken)
        assertEquals(0, creates)
        assertEquals("success", core.handle(details.copy(operation = "confirm", place = "서울역")).status)
    }
    @Test fun ambiguousUnfinishedDraftsCannotBeConfirmedWithoutReferences() {
        val s = review()
        store.state = store.state + s.copy(id = UUID.randomUUID().toString())
        assertEquals("asking", core.handle(details.copy(operation = "confirm")).status)
        assertEquals(0, creates)
    }
    @Test fun cancellationAndExpiryCannotCreateInvitations() {
        val s = review()
        assertEquals("success", core.handle(GleoRequest("cancel", s.id)).status)
        assertEquals("fail", core.handle(confirm(s)).status)
        store.state = emptyList()
        val expiring = review(); now = now.plusSeconds(1801)
        assertEquals("fail", core.handle(confirm(expiring)).status)
        assertEquals(0, creates)
    }
    @Test fun responseLossRetriesTheSameStableRequestIdAfterRestart() {
        val ids = mutableListOf<String>()
        var failed = false
        val c = GleoPickup(store, create = { _, key ->
            ids.add(key)
            if (!failed) { failed = true; throw java.io.IOException("response lost") }
            CreatedPickup("same-server-invitation", "PENDING")
        }, now = { now })
        val s = c.handle(details).session!!
        assertEquals("fail", c.handle(confirm(s)).status)
        assertEquals(GleoPhase.SUBMITTING, store.read().single().phase)
        val changed = c.handle(GleoRequest(sessionId = s.id, place = "서울역"))
        assertEquals("fail", changed.status)
        store.state = GleoJson.decode(GleoJson.encode(store.state))
        val recovered = GleoPickup(store, create = { _, key -> ids.add(key); CreatedPickup("same-server-invitation", "PENDING") }, now = { now })
        assertEquals("success", recovered.handle(confirm(s)).status)
        assertEquals(listOf(s.id, s.id), ids)
    }
    @Test fun aDeadlineBeforeSubmissionDoesNotStartNetworkCreation() {
        val s = review()
        assertEquals("fail", core.handle(confirm(s)) { false }.status)
        assertEquals(0, creates)
        assertEquals(GleoPhase.REVIEW, store.read().single().phase)
    }
    @Test fun malformedOrOverlongFieldsAndUnknownDraftsFailWithoutCreating() {
        assertEquals("fail", core.handle(details.copy(passengerName = "a".repeat(41))).status)
        assertEquals("fail", core.handle(GleoRequest(sessionId = UUID.randomUUID().toString())).status)
        assertEquals("fail", core.handle(GleoRequest(operation = "delete_everything")).status)
        assertEquals(0, creates)
    }
    @Test fun callbackContainsStructuredContextButNoInvitationOrDriverSecrets() {
        val result = core.handle(details)
        val raw = GleoJson.reply(result, now)
        assertEquals("asking", raw.getString("status"))
        assertEquals("voice", raw.getString("mode"))
        assertTrue(raw.get("data") is String)
        val item = org.json.JSONArray(raw.getString("data")).getJSONObject(0)
        assertEquals(PickupZone.id, item.getString("timezone"))
        assertEquals(result.session!!.confirmationToken, item.getString("confirmationToken"))
        assertFalse(raw.toString().contains("inviteToken"))
        assertFalse(raw.toString().contains("accessToken"))
        val done = core.handle(confirm(result.session!!))
        val doneData = org.json.JSONArray(GleoJson.reply(done, now).getString("data")).getJSONObject(0)
        assertFalse(doneData.has("confirmationToken"))
        assertEquals("PENDING", doneData.getString("invitationStatusAtCreation"))
    }
    @Test fun jsonWireFormatSupportsObjectAndEncodedObjectButRejectsWrongTypes() {
        val raw = """{"operation":"collect","passengerName":"지우","place":"수원역"}"""
        assertEquals("지우", GleoJson.request(raw).passengerName)
        assertEquals(GleoJson.request(raw), GleoJson.request(org.json.JSONObject.quote(raw)))
        assertEquals(GleoRequest(), GleoJson.request("""{"operation":"collect","sessionId":"","confirmationToken":"","passengerName":"  ","place":"","meetingPoint":"","meetingAt":""}"""))
        assertEquals(GleoRequest(), GleoJson.request("""{"meetingTimeText":"  "}"""))
        for (bad in listOf("[]", "null", "{} {}", """{"passengerName": 123}""", """{"operation":"collect","unexpected":true}""", "x".repeat(8193))) {
            assertThrows(IllegalArgumentException::class.java) { GleoJson.request(bad) }
        }
    }
}
