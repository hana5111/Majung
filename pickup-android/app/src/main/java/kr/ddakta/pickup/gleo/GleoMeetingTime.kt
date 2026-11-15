package kr.ddakta.pickup.gleo

import kr.ddakta.pickup.PickupZone
import kr.ddakta.pickup.VoiceDraft
import kr.ddakta.pickup.parseVoiceMeetingTime
import java.time.Instant
import java.time.OffsetDateTime

internal data class GleoMeetingTime(val draft: VoiceDraft, val prompt: String? = null, val reason: String = "")

/** Resolve relative speech against the app clock, rather than an LLM-inferred year. */
internal fun resolveGleoMeetingTime(request: GleoRequest, original: VoiceDraft, now: Instant): GleoMeetingTime {
    val spoken = request.meetingTimeText?.takeIf { it.isNotBlank() }
    val legacy = request.meetingAt?.takeIf { it.isNotBlank() }
    if (spoken == null && legacy == null) return GleoMeetingTime(original)
    val text = (spoken ?: legacy!!).trim()
    var draft = original
    if (spoken == null || Regex("^\\d{4}-\\d{2}-\\d{2}T").containsMatchIn(text)) {
        // Accept older installed schemas, without guessing or replacing an explicit year.
        try {
            val local = OffsetDateTime.parse(text).toInstant().atZone(PickupZone)
            draft = draft.copy(date = local.toLocalDate(), time = local.toLocalTime(), ambiguousHour = null, ambiguousMinute = 0)
        } catch (_: Exception) {
            return GleoMeetingTime(draft.copy(date = null, time = null, ambiguousHour = null, ambiguousMinute = 0),
                "날짜와 시간을 확인하지 못했어요. 내일 오후 6시처럼 다시 말씀해 주세요.",
                "The supplied date and time could not be understood.")
        }
    } else {
        val parsed = parseVoiceMeetingTime(text, draft, now.atZone(PickupZone).toLocalDate())
        draft = parsed.draft
        if (parsed.error != null) return GleoMeetingTime(draft, parsed.error, "The requested date or time needs clarification.")
        if (!parsed.changed) return GleoMeetingTime(draft.copy(date = null, time = null, ambiguousHour = null, ambiguousMinute = 0),
            "날짜와 시간을 확인하지 못했어요. 내일 오후 6시처럼 다시 말씀해 주세요.",
            "The supplied date and time could not be understood.")
    }
    val instant = draft.meetingAt()
    if (instant != null && !instant.isAfter(now)) return GleoMeetingTime(draft.copy(time = null),
        "말씀하신 시간은 이미 지났어요. 현재 한국 시간 이후로 언제 만날지 다시 말씀해 주세요.",
        "The requested time has already passed relative to currentDateTime in Asia/Seoul. Do not replace an explicit year or silently move the appointment to another day.")
    if (instant != null && instant.isAfter(now.plusSeconds(366L * 86400))) return GleoMeetingTime(draft.copy(time = null),
        "앞으로 1년 안의 만날 날짜와 시간을 말씀해 주세요.",
        "The requested time is more than 366 days after currentDateTime.")
    return GleoMeetingTime(draft)
}
