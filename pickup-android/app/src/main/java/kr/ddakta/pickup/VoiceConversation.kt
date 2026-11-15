package kr.ddakta.pickup

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val PickupZone: ZoneId = ZoneId.of("Asia/Seoul")

data class VoiceDraft(
    val name: String = "", val place: String = "", val point: String = "",
    val date: LocalDate? = null, val time: LocalTime? = null,
    val contact: String = "", val note: String = "", val ambiguousHour: Int? = null, val ambiguousMinute: Int = 0
) {
    fun meetingAt() = if (date != null && time != null) LocalDateTime.of(date, time).atZone(PickupZone).toInstant() else null
}

enum class VoiceField { NAME, PLACE, TIME, POINT, REVIEW }
enum class VoicePhase { COLLECTING, REVIEW, SUBMIT, CANCELLED }

data class VoiceConversation(
    val draft: VoiceDraft,
    val field: VoiceField,
    val phase: VoicePhase,
    val prompt: String,
    val lastHeard: String = "",
    val turn: Int = 0
) {
    companion object {
        fun begin(draft: VoiceDraft = VoiceDraft(), now: LocalDateTime = LocalDateTime.now(PickupZone)): VoiceConversation = next(draft, now)

        private fun next(d: VoiceDraft, now: LocalDateTime, heard: String = "", turn: Int = 0): VoiceConversation {
            val field: VoiceField
            val prompt: String
            when {
                d.name.isBlank() -> { field = VoiceField.NAME; prompt = "누구를 어디로 데리러 갈까요? 알고 있는 내용을 한 번에 말씀해 주세요." }
                d.place.isBlank() -> { field = VoiceField.PLACE; prompt = "${d.name}님을 어디로 데리러 갈까요?" }
                else -> { field = VoiceField.REVIEW; prompt = summary(d) + " 이 내용으로 픽업을 준비할까요?" }
            }
            return VoiceConversation(d, field, if (field == VoiceField.REVIEW) VoicePhase.REVIEW else VoicePhase.COLLECTING, prompt, heard, turn)
        }

        fun summary(d: VoiceDraft): String {
            return "지금 ${d.name}님을 ${d.place}${if (d.point.isBlank()) "" else " ${d.point}"}으로 데리러 갑니다."
        }
    }

    fun edit(target: VoiceField, now: LocalDateTime = LocalDateTime.now(PickupZone)): VoiceConversation {
        val d = when(target) {
            VoiceField.NAME -> draft.copy(name = "")
            VoiceField.PLACE -> draft.copy(place = "", point = "")
            VoiceField.TIME -> draft.copy(date = null, time = null, ambiguousHour = null, ambiguousMinute = 0)
            VoiceField.POINT -> draft.copy(point = "")
            VoiceField.REVIEW -> draft
        }
        return next(d, now, lastHeard, turn + 1)
    }

    fun hear(raw: String, now: LocalDateTime = LocalDateTime.now(PickupZone)): VoiceConversation {
        if (phase == VoicePhase.SUBMIT || phase == VoicePhase.CANCELLED) return this
        val text = normalizeSpeech(raw)
        val compact = text.replace(" ", "")
        if (compact.isBlank()) return copy(prompt = "잘 듣지 못했어요. 다시 말씀해 주세요.", turn = turn + 1)
        if (compact.matches(Regex("(?:그만|취소|중단)(?:해|해줘|할게|해주세요|할래)?")))
            return copy(phase = VoicePhase.CANCELLED, prompt = "약속 만들기를 중단했어요. 초대 링크는 만들지 않았습니다.", lastHeard = raw, turn = turn + 1)
        if (compact.matches(Regex("(?:처음부터|다시시작)(?:해|해줘|할게|해주세요)?"))) return next(VoiceDraft(), now, raw, turn + 1)
        if (compact.matches(Regex("(?:다시말해줘|다시읽어줘|다시들려줘|뭐라고)"))) return copy(turn = turn + 1, lastHeard = raw)
        // Only a final, explicit confirmation in the review step can request creation.
        val agrees = compact.matches(Regex("(?:출발|출발해|출발해줘|준비해줘|네|예|응|맞아|맞아요|좋아|좋아요|확인|만들어|만들어줘|만들어주세요|초대링크만들어|초대링크만들어줘|그렇게해줘)"))
        if (phase == VoicePhase.REVIEW && agrees) {
            val checked = next(draft, now, raw, turn + 1)
            return if (checked.phase == VoicePhase.REVIEW) checked.copy(phase = VoicePhase.SUBMIT, prompt = "픽업을 준비하고 있어요.") else checked
        }
        if (agrees || compact.matches(Regex("(?:아니|아니요|아냐|아니야)"))) return copy(
            prompt = if (phase == VoicePhase.REVIEW) "무엇을 바꿀까요? 탑승자나 목적지 중 말씀해 주세요." else prompt,
            lastHeard = raw, turn = turn + 1
        )
        val correction = when {
            Regex("(?:이름|탑승자).*(?:바꿔|변경|수정)").containsMatchIn(text) -> VoiceField.NAME
            Regex("(?:장소|목적지).*(?:바꿔|변경|수정)").containsMatchIn(text) -> VoiceField.PLACE
            Regex("(?:시간|날짜).*(?:바꿔|변경|수정)").containsMatchIn(text) -> VoiceField.TIME
            Regex("(?:승차 지점|승차지점|만날 지점).*(?:바꿔|변경|수정)").containsMatchIn(text) -> VoiceField.POINT
            else -> null
        }
        if (correction != null && compact.matches(Regex("(?:이름|탑승자|장소|목적지|시간|날짜|승차지점|만날지점)(?:을|를|은|는|만)?(?:바꿔|변경|수정)(?:줘|해줘|할게|해주세요)?"))) return edit(correction, now)
        var d = draft
        val places = Regex("([가-힣A-Za-z0-9]+(?:공항|터미널|대학교|백화점|아파트|병원|학교|센터|카페|역))").findAll(text).map { it.groupValues[1] }.distinct().toList()
        val extractedPlace = places.singleOrNull()
        val namedPerson = (Regex("(?:이름|탑승자)(?:은|는|이|을|를)?\\s*([가-힣A-Za-z0-9]+?)(?:(?:으로|로)?\\s*(?:바꿔|변경|수정)|\\s|$)").find(text)?.groupValues?.get(1)
            ?: Regex("^([가-힣A-Za-z][가-힣A-Za-z0-9 ]{0,24}?)(?:를|을)(?=\\s|오늘|내일|모레|오전|오후|[0-9])").find(text)?.groupValues?.get(1))
            ?.takeUnless { it.trim() in setOf("시간", "날짜", "장소", "목적지", "승차 지점", "만날 지점", "이름", "탑승자", "약속") }
        val point = Regex("(?:[0-9]+\\s*번?\\s*(?:출구|승차장|구역)|(?:동측|서측|남측|북측|동쪽|서쪽|정문|후문|동문|서문|남문|북문)(?:\\s*(?:승차장|출구|주차장))?(?:\\s*[0-9]+\\s*번?\\s*(?:출구|승차장|구역))?)").find(text)?.value
        if (namedPerson != null) d = d.copy(name = cleanValue(namedPerson).take(40))
        if (extractedPlace != null) {
            if (d.place != extractedPlace) d = d.copy(place = extractedPlace.take(100), point = "")
        }
        if (point != null) d = d.copy(point = point.take(120))
        if (places.size > 1) return copy(draft = d.copy(place = "", point = ""), field = VoiceField.PLACE, phase = VoicePhase.COLLECTING,
            prompt = "만날 장소가 여러 곳 들렸어요. 어느 한 곳에서 만날지 다시 말씀해 주세요.", lastHeard = raw, turn = turn + 1)
        val hasSlots = namedPerson != null || extractedPlace != null || point != null
        if (!hasSlots && !Regex("모르겠|아직|나중에").containsMatchIn(text)) {
            val value = cleanValue(text)
            when (field) {
                VoiceField.NAME -> d = d.copy(name = value.take(40))
                VoiceField.PLACE -> d = d.copy(place = value.take(100), point = "")
                VoiceField.POINT -> d = d.copy(point = value.take(120))
                else -> Unit
            }
        }
        if (d == draft) return copy(prompt = if (phase == VoicePhase.REVIEW) "바꿀 내용을 구체적으로 말씀해 주세요. 예를 들어, 목적지를 수원역으로 바꿔줘." else prompt, lastHeard = raw, turn = turn + 1)
        return next(d, now, raw, turn + 1)
    }
}

private fun cleanValue(value: String): String = value.trim().replace(Regex("^(?:이름|탑승자|장소|목적지|승차 지점|승차지점)(?:은|는|이)?\\s*"), "")
    .replace(Regex("(?:으로|에서|이야|이요|예요|이에요|야|요)$"), "").trim()

internal data class Temporal(val draft: VoiceDraft, val changed: Boolean, val error: String? = null)
internal fun parseVoiceMeetingTime(raw: String, original: VoiceDraft, today: LocalDate): Temporal =
    parseTime(normalizeSpeech(raw), original, today)

private fun parseTime(text: String, original: VoiceDraft, today: LocalDate): Temporal {
    var d = original
    var changed = false
    val explicitDate = Regex("(?:(\\d{4})년\\s*)?(\\d{1,2})월\\s*(\\d{1,2})일").find(text)
    val iso = Regex("\\b(\\d{4}-\\d{2}-\\d{2})\\b").find(text)
    try {
        val date = when {
            "모레" in text -> today.plusDays(2)
            "내일" in text -> today.plusDays(1)
            "오늘" in text -> today
            iso != null -> LocalDate.parse(iso.groupValues[1])
            explicitDate != null -> LocalDate.of(explicitDate.groupValues[1].toIntOrNull() ?: today.year, explicitDate.groupValues[2].toInt(), explicitDate.groupValues[3].toInt())
            else -> null
        }
        if (date != null) { d = d.copy(date = date); changed = true }
        else if (Regex("다음\\s*주|이번\\s*주|[월화수목금토일]요일").containsMatchIn(text)) {
            return Temporal(d.copy(date = null), true, "날짜를 확인할게요. 오늘, 내일 또는 11월 20일처럼 날짜로 말씀해 주세요.")
        }
    } catch (_: Exception) { return Temporal(d.copy(date = null), true, "존재하는 날짜인지 확인해 주세요. 몇 월 며칠에 만날까요?") }
    val hours = Regex("(\\d{1,2})\\s*시").findAll(text).map { it.groupValues[1].toInt() }.distinct().toList()
    if (hours.size > 1) return Temporal(d.copy(time = null, ambiguousHour = null, ambiguousMinute = 0), true, "시간이 여러 개 들렸어요. 정확히 몇 시에 만날지 다시 말씀해 주세요.")
    val h = hours.singleOrNull()
    val colon = Regex("\\b(\\d{1,2}):(\\d{2})\\b").find(text)
    val minuteText = Regex("(\\d{1,2})\\s*분").find(text)?.groupValues?.get(1)?.toInt()
    val pm = Regex("오후|저녁").containsMatchIn(text)
    val am = Regex("오전|아침|새벽").containsMatchIn(text)
    var hour = h ?: colon?.groupValues?.get(1)?.toInt()
    if (hour == null && (pm || am)) hour = d.ambiguousHour ?: d.time?.hour?.let { if (it % 12 == 0) 12 else it % 12 }
    if ("정오" in text) hour = 12
    if ("자정" in text) hour = 0
    if (hour != null) {
        changed = true
        val minute = minuteText ?: colon?.groupValues?.get(2)?.toInt() ?: if ("반" in text) 30 else if (h == null && colon == null) (if (d.ambiguousHour != null) d.ambiguousMinute else d.time?.minute ?: 0) else 0
        if (hour !in 0..23 || minute !in 0..59 || (am || pm) && hour !in 1..12 || am && pm)
            return Temporal(d.copy(time = null, ambiguousHour = null, ambiguousMinute = 0), true, "시간을 확인할게요. 오늘 오후 6시 30분처럼 다시 말씀해 주세요.")
        if (hour in 1..12 && !am && !pm && "정오" !in text) {
            d = d.copy(time = null, ambiguousHour = hour, ambiguousMinute = minute)
        } else {
            val resolved = if (pm && hour < 12) hour + 12 else if (am && hour == 12) 0 else hour
            d = d.copy(time = LocalTime.of(resolved, minute), ambiguousHour = null, ambiguousMinute = 0)
        }
    }
    return Temporal(d, changed)
}

private fun normalizeSpeech(raw: String): String {
    val sino = mapOf("" to 0, "일" to 1, "이" to 2, "삼" to 3, "사" to 4, "오" to 5, "육" to 6, "칠" to 7, "팔" to 8, "구" to 9)
    val numbers = mutableMapOf("한" to 1, "두" to 2, "세" to 3, "네" to 4, "다섯" to 5, "여섯" to 6, "일곱" to 7, "여덟" to 8, "아홉" to 9, "열" to 10, "열한" to 11, "열두" to 12)
    for (n in 1..59) {
        val tens = n / 10; val units = n % 10
        val a = if(tens == 0) "" else (if(tens == 1) "" else sino.entries.first { it.value == tens }.key) + "십"
        val b = if(units == 0) "" else sino.entries.first { it.value == units }.key
        numbers[a + b] = n
    }
    var result = raw.trim().replace(Regex("[.,!?，。！？]"), " ")
    val pattern = numbers.keys.sortedByDescending { it.length }.joinToString("|")
    result = Regex("($pattern)\\s*(시|분|월|일|번)").replace(result) { "${numbers[it.groupValues[1]]}${it.groupValues[2]}" }
    return result.replace(Regex("\\s+"), " ").trim()
}
