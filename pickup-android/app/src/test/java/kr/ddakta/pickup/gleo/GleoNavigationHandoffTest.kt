package kr.ddakta.pickup.gleo

import kr.ddakta.pickup.VoiceDraft
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class GleoNavigationHandoffTest {
    private val now = Instant.parse("2026-11-15T00:00:00Z")
    private val created = GleoSession("voice-session", VoiceDraft(name = "지우", place = "수원역", point = "2번 출구"),
        GleoPhase.CREATED, expiresAt = now.plusSeconds(600), appointmentId = "prepared-pickup")

    @Test fun unapprovedIncompleteCancelledOrFailedRequestsNeverOpenNavigation() {
        for (phase in GleoPhase.entries.filter { it != GleoPhase.CREATED })
            assertNull(GleoNavigationHandoff.from(GleoReply("success", "", created.copy(phase = phase)), now))
        for (status in listOf("asking", "fail"))
            assertNull(GleoNavigationHandoff.from(GleoReply(status, "", created), now))
        assertNull(GleoNavigationHandoff.from(GleoReply("success", "", created.copy(appointmentId = null)), now))
        assertNull(GleoNavigationHandoff.from(GleoReply("success", "", created.copy(expiresAt = now)), now))
    }

    @Test fun approvedPickupPreservesTheSpokenQueryWithoutInventingCoordinates() {
        val handoff = GleoNavigationHandoff.from(GleoReply("success", "", created), now)!!
        assertEquals("수원역 2번 출구", handoff.keyword)
        assertEquals("prepared-pickup", handoff.appointmentId)
        assertTrue(handoff.pending)
        assertEquals(handoff, GleoNavigationHandoff.parse(handoff.toJson()))
        val consumed = handoff.copy(pending = false)
        assertEquals(consumed, GleoNavigationHandoff.parse(consumed.toJson()))
        val searching = consumed.copy(search = kr.ddakta.pickup.PickupPlaceSearch(handoff.keyword, null, now))
        assertEquals(searching, GleoNavigationHandoff.parse(searching.toJson()))
        assertFalse(handoff.toJson().contains("latitude"))
    }
}
