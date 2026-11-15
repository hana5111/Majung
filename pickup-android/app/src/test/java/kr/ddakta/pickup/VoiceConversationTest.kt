package kr.ddakta.pickup

import org.junit.Assert.*
import org.junit.Test

class VoiceConversationTest {
    @Test fun asksForNameAndPlaceOnly() {
        val start = VoiceConversation.begin()
        assertEquals(VoiceField.NAME, start.field)
        assertFalse(start.prompt.contains("언제"))
        val named = start.hear("지우")
        assertEquals(VoiceField.PLACE, named.field)
        assertEquals("지우", named.draft.name)
    }
    @Test fun aSingleSentenceCanReachReviewWithoutDateOrPoint() {
        val review = VoiceConversation.begin().hear("지우를 수원역으로 데리러 갈게")
        assertEquals("지우", review.draft.name)
        assertEquals("수원역", review.draft.place)
        assertEquals(VoicePhase.REVIEW, review.phase)
        assertNull(review.draft.meetingAt())
        assertEquals(VoicePhase.SUBMIT, review.hear("응").phase)
    }
    @Test fun missingDetailsCannotBeBypassedWithYes() {
        val early = VoiceConversation.begin().hear("응")
        assertEquals(VoicePhase.COLLECTING, early.phase)
        assertTrue(early.draft.name.isEmpty())
    }
    @Test fun editingDestinationRequiresASecondReview() {
        val first = VoiceConversation.begin(VoiceDraft(name = "지우", place = "수원역"))
        val edit = first.edit(VoiceField.PLACE)
        assertEquals(VoicePhase.COLLECTING, edit.phase)
        assertEquals("지우", edit.draft.name)
        val changed = edit.hear("서울역")
        assertEquals(VoicePhase.REVIEW, changed.phase)
        assertEquals("서울역", changed.draft.place)
    }
    @Test fun multiplePlacesMustBeDisambiguated() {
        val c = VoiceConversation.begin().hear("지우를 수원역 아니면 서울역에서 태울게")
        assertEquals(VoiceField.PLACE, c.field)
        assertEquals(VoicePhase.COLLECTING, c.phase)
    }
    @Test fun cancelledConversationNeverSubmits() {
        val cancelled = VoiceConversation.begin().hear("취소")
        assertEquals(VoicePhase.CANCELLED, cancelled.phase)
        assertEquals(cancelled, cancelled.hear("응"))
    }
    @Test fun timeInAnOldDraftDoesNotBecomeARequiredQuestion() {
        val c = VoiceConversation.begin(VoiceDraft(name = "지우", place = "수원역", date = java.time.LocalDate.of(2020, 1, 1)))
        assertEquals(VoicePhase.REVIEW, c.phase)
        assertFalse(c.prompt.contains("2020"))
    }
}
