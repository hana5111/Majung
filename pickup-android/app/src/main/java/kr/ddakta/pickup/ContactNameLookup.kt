package kr.ddakta.pickup

data class ContactCandidate(val contactId: Long, val recipient: SmsRecipient, val numberLabel: String)

class ContactSearchResult private constructor(val candidates: List<ContactCandidate>, val incomplete: Boolean) {
    val unique: ContactCandidate? get() = candidates.singleOrNull()?.takeUnless { incomplete }

    companion object {
        fun from(candidates: List<ContactCandidate>, incomplete: Boolean = false) = ContactSearchResult(
            // Repeated rows for the same person's same number are not separate choices.
            // Different people or different numbers must never be silently merged.
            candidates.distinctBy { it.contactId to it.recipient.number }, incomplete)
    }
}
