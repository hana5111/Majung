package kr.ddakta.pickup

import org.junit.Assert.*
import org.junit.Test

class ContactNameLookupTest {
    private val jiwoo = ContactCandidate(1, SmsRecipient.from("지우 (테스트)", "+12025550101"), "휴대전화")

    @Test fun selectsExactlyOneUnambiguousNumber() {
        assertEquals(jiwoo, ContactSearchResult.from(listOf(jiwoo)).unique)
    }

    @Test fun duplicateRowsForTheSamePersonAndNumberDoNotRequireAnotherSelection() {
        val result = ContactSearchResult.from(listOf(jiwoo, jiwoo.copy(numberLabel = "다른 라벨")))
        assertEquals(jiwoo, result.unique)
        assertEquals(1, result.candidates.size)
    }

    @Test fun onePersonWithMultipleNumbersRequiresAChoice() {
        val otherNumber = jiwoo.copy(recipient = jiwoo.recipient.copy(number = "+12025550102"))
        assertNull(ContactSearchResult.from(listOf(jiwoo, otherNumber)).unique)
    }

    @Test fun differentPeopleAreNotMergedEvenIfTheirNamesAndNumbersMatch() {
        assertNull(ContactSearchResult.from(listOf(jiwoo, jiwoo.copy(contactId = 2))).unique)
    }

    @Test fun truncatedResultsNeverAutomaticallySelectTheOnlyReturnedNumber() {
        assertNull(ContactSearchResult.from(listOf(jiwoo), incomplete = true).unique)
    }

    @Test fun missingContactDoesNotInventANumber() {
        assertNull(ContactSearchResult.from(emptyList()).unique)
    }
}
