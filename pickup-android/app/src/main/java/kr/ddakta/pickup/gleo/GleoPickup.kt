package kr.ddakta.pickup.gleo

import kr.ddakta.pickup.PickupZone
import kr.ddakta.pickup.VoiceConversation
import kr.ddakta.pickup.VoiceDraft
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

const val CREATE_PICKUP_ACTION = "DDAKTA_CREATE_PICKUP"
const val QUALIFIED_CREATE_PICKUP_ACTION = "kr.ddakta.pickup.$CREATE_PICKUP_ACTION"
const val GLEO_CALLBACK_ACTION = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"

data class GleoRequest(
    val operation: String = "collect", val sessionId: String? = null,
    val confirmationToken: String? = null, val passengerName: String? = null,
    val place: String? = null, val meetingPoint: String? = null, val meetingAt: String? = null,
    val meetingTimeText: String? = null
)
enum class GleoPhase { DRAFT, REVIEW, SUBMITTING, CREATED, CANCELLED, EXPIRED }
data class GleoSession(
    val id: String, val draft: VoiceDraft = VoiceDraft(), val phase: GleoPhase = GleoPhase.DRAFT,
    val confirmationToken: String? = null, val version: Int = 1,
    val expiresAt: Instant, val appointmentId: String? = null, val invitationStatus: String? = null
)
data class GleoReply(
    val status: String, val message: String, val session: GleoSession? = null,
    val missingFields: List<String> = emptyList(), val prompt: String = "",
    val functionName: String = CREATE_PICKUP_ACTION, val mode: String = "voice"
)
data class CreatedPickup(val id: String, val status: String)
interface GleoSessionStore {
    fun read(): List<GleoSession>
    fun write(sessions: List<GleoSession>)
}

/** Gleo extracts immediate pickup fields; the app verifies the summary before creating anything. */
class GleoPickup(
    private val store: GleoSessionStore,
    private val create: (VoiceDraft, String) -> CreatedPickup,
    private val now: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {
    @Synchronized fun handle(request: GleoRequest, canStartCreation: () -> Boolean = { true }): GleoReply {
        if (request.operation !in setOf("collect", "confirm", "cancel")) return GleoReply("fail", "The requested pickup operation is not supported.")
        val validation = listOf(request.passengerName to 40, request.place to 100, request.meetingPoint to 120).any { (v, max) ->
            v != null && (v.length > max || v.any { it.isISOControl() })
        }
        if (validation || (request.meetingAt?.length ?: 0) > 60 || (request.meetingTimeText?.length ?: 0) > 160 || request.meetingTimeText?.any { it.isISOControl() } == true) return GleoReply("fail", "The pickup details have an invalid value or length. Please check the supplied fields.")
        val sessions = store.read().toMutableList()
        val withoutReferences = request.sessionId == null && request.confirmationToken == null
        val active = sessions.filter { it.phase in setOf(GleoPhase.DRAFT, GleoPhase.REVIEW, GleoPhase.SUBMITTING) && it.expiresAt.isAfter(now()) }
        if (request.operation == "confirm" && withoutReferences && active.size > 1) return GleoReply("asking",
            "There is more than one unfinished pickup draft. Identify the intended draft before confirming creation.",
            prompt = "진행 중인 약속 초안이 여러 개예요. 어떤 약속을 만들지 먼저 선택해 주세요.")
        var current = if (request.sessionId != null) sessions.find { it.id == request.sessionId }
            else active.lastOrNull()
        // Repeated spoken approval must recover the same invitation, not open another draft.
        if (current == null && request.operation == "confirm" && withoutReferences) current = sessions.lastOrNull()?.takeIf {
            it.phase == GleoPhase.CREATED && it.expiresAt.isAfter(now()) && repeatsReviewedDetails(request, it.draft)
        }
        if (request.sessionId != null && current == null) return GleoReply("fail", "The pickup draft was not found. Start a new pickup request.")
        if (current != null && !current.expiresAt.isAfter(now()) && current.phase != GleoPhase.CREATED) {
            current = current.copy(phase = GleoPhase.EXPIRED, confirmationToken = null)
            save(sessions, current)
            return GleoReply("fail", "This pickup draft has expired. Start a new pickup request.", current, prompt = "초안이 만료됐어요. 새 약속을 시작해 주세요.")
        }
        if (request.operation == "cancel") {
            if (current == null) return GleoReply("success", "There is no pending pickup draft to cancel.", prompt = "진행 중인 약속 초안이 없어요.")
            if (current.phase in setOf(GleoPhase.CREATED, GleoPhase.SUBMITTING)) return GleoReply("fail", "The invitation may already exist. Draft cancellation cannot cancel a created invitation. Check it in the app.", current, prompt = "이미 생성됐거나 생성 결과를 확인 중이에요. 앱의 약속 목록에서 확인해 주세요.")
            current = current.copy(phase = GleoPhase.CANCELLED, confirmationToken = null)
            save(sessions, current)
            return GleoReply("success", "Pickup draft creation was cancelled. No invitation was created from this draft.", current, prompt = "초안 작성을 취소했어요. 이 초안으로 초대를 만들지 않았어요.")
        }
        if (current?.phase == GleoPhase.CREATED) return completed(current, replayed = true)
        if (current?.phase in setOf(GleoPhase.CANCELLED, GleoPhase.EXPIRED)) return GleoReply("fail", "This draft has ended. Start a new pickup request.", current)
        if (request.operation == "confirm" && current == null && !hasAllDetails(request)) return GleoReply("asking",
            "There is no reviewed pickup draft to confirm. Collect the pickup details and present their summary first.",
            prompt = "먼저 만들 약속을 알려주세요. 누구를 어디로 데리러 갈까요?")
        if (current == null) current = GleoSession(newId(), expiresAt = now().plusSeconds(1800))
        if (request.sessionId == null && current.draft.name.isNotBlank() && !request.passengerName.isNullOrBlank() && request.passengerName.trim() != current.draft.name) {
            return GleoReply("asking", "A draft for a different passenger is still pending. Ask whether to finish or cancel the pending draft before starting another.", current, prompt = "다른 탑승자의 초안이 있어요. 이어서 작성할지 취소할지 확인해 주세요.")
        }
        // Some Gleo builds omit opaque references even after the driver explicitly approves.
        // Only an existing reviewed snapshot and an independently complete, identical payload qualify.
        val confirmedByDetails = request.operation == "confirm" && withoutReferences &&
            current.phase in setOf(GleoPhase.REVIEW, GleoPhase.SUBMITTING) && current.confirmationToken != null &&
            repeatsReviewedDetails(request, current.draft)
        var draft = current.draft
        request.passengerName?.takeIf { it.isNotBlank() }?.let { draft = draft.copy(name = it.trim()) }
        request.place?.takeIf { it.isNotBlank() }?.let { if (it.trim() != draft.place) draft = draft.copy(place = it.trim(), point = "") }
        request.meetingPoint?.takeIf { it.isNotBlank() }?.let { draft = draft.copy(point = it.trim()) }
        // Immediate pickup uses live navigation ETA, never a driver-entered meeting time.
        draft = draft.copy(date = null, time = null, ambiguousHour = null)
        val changed = draft != current.draft
        if (current.phase == GleoPhase.SUBMITTING && (changed || request.operation != "confirm")) return GleoReply("fail", "Invitation creation is unresolved. Retry confirmation with the same unchanged draft to recover the result before editing it.", current, prompt = "이전 생성 결과를 먼저 확인해야 해요. 같은 내용으로 다시 확인하거나 앱의 약속 목록을 확인해 주세요.")
        if (changed) current = current.copy(draft = draft, confirmationToken = null, version = current.version + 1, phase = GleoPhase.DRAFT)
        val missing = missing(current.draft)
        if (missing.isNotEmpty()) {
            current = current.copy(phase = GleoPhase.DRAFT, confirmationToken = null)
            save(sessions, current)
            val prompt = VoiceConversation.begin(current.draft).prompt
            return GleoReply("asking", "Only passengerName and place are required for immediate pickup. Ask the missing question in prompt. Never ask for date, time or boarding point; the car navigation supplies the ETA after departure.", current, missing, prompt)
        }
        // Every complete draft must first be returned for review, including an attempted first-call confirmation.
        if (current.phase != GleoPhase.REVIEW && current.phase != GleoPhase.SUBMITTING || changed) {
            current = current.copy(phase = GleoPhase.REVIEW, confirmationToken = newId())
            save(sessions, current)
            return review(current)
        }
        val confirmedByReference = request.operation == "confirm" && request.sessionId == current.id &&
            request.confirmationToken != null && request.confirmationToken == current.confirmationToken
        if (!confirmedByReference && !confirmedByDetails) {
            save(sessions, current)
            return review(current)
        }
        if (!canStartCreation()) return GleoReply("fail", "The request deadline was reached before invitation creation started. Please retry confirmation.", current, prompt = "요청 시간이 지나 생성을 시작하지 않았어요. 다시 확인해 주세요.")
        current = current.copy(phase = GleoPhase.SUBMITTING)
        save(sessions, current) // Persist the stable request id BEFORE any network request.
        return try {
            val made = create(current.draft, current.id)
            current = current.copy(phase = GleoPhase.CREATED, appointmentId = made.id, invitationStatus = made.status, confirmationToken = null)
            save(sessions, current)
            completed(current)
        } catch (_: Exception) {
            GleoReply("fail", "Invitation creation could not be verified. Check the app or retry confirmation for this unchanged draft. A retry uses the same creation request id.", current, prompt = "초대 생성 결과를 확인하지 못했어요. 앱의 약속 목록을 확인하거나 같은 내용으로 다시 확인해 주세요.")
        }
    }

    private fun save(all: MutableList<GleoSession>, session: GleoSession) {
        val found = all.indexOfFirst { it.id == session.id }
        if (found >= 0) all[found] = session else all.add(session)
        store.write(all.takeLast(8))
    }
    private fun missing(d: VoiceDraft): List<String> = buildList {
        if (d.name.isBlank()) add("passengerName")
        if (d.place.isBlank()) add("place")
    }
    private fun hasAllDetails(r: GleoRequest) = !r.passengerName.isNullOrBlank() && !r.place.isNullOrBlank()
    private fun repeatsReviewedDetails(r: GleoRequest, d: VoiceDraft): Boolean = hasAllDetails(r) &&
        r.passengerName!!.trim() == d.name && r.place!!.trim() == d.place && r.meetingPoint.orEmpty().trim() == d.point

    private fun review(s: GleoSession) = GleoReply("asking", "Ask the driver to approve the exact pickup summary in prompt. After explicit approval, call confirm with the unchanged passengerName and place (and meetingPoint only if already supplied); internal references are optional when all reviewed details are repeated unchanged.", s,
        prompt = VoiceConversation.summary(s.draft) + " 이 내용으로 픽업을 준비할까요?")
    private fun completed(s: GleoSession, replayed: Boolean = false): GleoReply = GleoReply("success",
        if (replayed) "This pickup is already prepared. Do not create a duplicate. The driver must verify the exact destination in Majung and start departure. No SMS has been sent."
        else "Immediate pickup is prepared. Majung will request vehicle navigation search for the spoken place when its screen and navigation connection are ready; search results and the exact POI are not yet confirmed. Select the destination, return to Majung and confirm departure before ETA sharing and contact SMS review. No SMS has been sent.", s,
        prompt = if (replayed) "이미 준비한 픽업이에요. 마중에서 목적지를 확인하고 출발해 주세요."
            else "픽업을 준비했어요. 말씀하신 장소로 내비 검색을 연결할게요. 검색 결과에서 목적지를 정한 뒤 마중으로 돌아와 확인해 주세요.")
}
