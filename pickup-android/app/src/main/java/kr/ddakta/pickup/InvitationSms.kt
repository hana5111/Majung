package kr.ddakta.pickup

import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

data class SmsRecipient(val name: String, val number: String) {
    companion object {
        fun from(name: String, number: String): SmsRecipient {
            val trimmed = number.trim()
            require(trimmed.none { it.isISOControl() } && trimmed.matches(Regex("[+0-9() .\\-]+"))) { "문자를 보낼 수 있는 전화번호를 선택해 주세요." }
            val normalized = trimmed.replace(Regex("[()\\s.\\-]"), "")
            require(normalized.matches(Regex("\\+?[0-9]{5,15}"))) { "전화번호를 확인하고 다른 연락처를 선택해 주세요." }
            return SmsRecipient(name.filterNot { it.isISOControl() }.trim().take(80).ifBlank { "선택한 연락처" }, normalized)
        }
    }
}

data class PreparedInvitationSms(val body: String, val preview: String, val localOnly: Boolean)

object InvitationSms {
    fun prepare(a: Appointment, origin: String = BuildConfig.INVITE_ORIGIN, now: Instant = Instant.now()): PreparedInvitationSms {
        require(a.canShare && (a.pickupMode == "NOW" || a.meetingAt?.isAfter(now) == true)) { "출발 전이거나 종료된 픽업은 공유할 수 없어요." }
        require(a.inviteToken.matches(Regex("[A-Za-z0-9_-]{40,100}"))) { "초대 링크를 확인하지 못했어요. 약속을 다시 불러와 주세요." }
        val uri = runCatching { URI(origin) }.getOrNull()
        require(uri != null && uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "초대 링크의 공유 주소를 확인해 주세요."
        }
        val host = uri.host.lowercase().removePrefix("[").removeSuffix("]")
        val local = host == "localhost" || host.endsWith(".localhost") || host.startsWith("127.") || host in setOf("::1", "0.0.0.0", "10.0.2.2")
        val url = origin.trimEnd('/') + "/invite#" + a.inviteToken
        // The ETA changes while driving; the URL is the authoritative current value.
        val time = if (a.pickupMode == "NOW") "차량이 출발했어요. 링크에서 현재 도착 예상 시간을 확인해 주세요."
            else DateTimeFormatter.ofPattern("M월 d일 (E) a h:mm", Locale.KOREAN).withZone(PickupZone).format(a.meetingAt)
        val lines = listOf(if (local) "[마중 개발 테스트] 픽업 초대" else "[마중] 픽업 초대",
            "${a.passengerName}님, 아래 약속을 확인해 주세요.", time, "${a.place} ${a.meetingPoint}",
            *(a.pickupLocation?.let { listOf("선택한 승차 위치: ${it.name}", it.address).filter { line -> line.isNotBlank() } } ?: emptyList()).toTypedArray(),
            "링크에서 약속을 확인하고 수락할 수 있어요.", url) +
            if (local) listOf("개발용 링크로, 현재는 다른 휴대폰에서 열리지 않습니다.") else emptyList()
        val body = lines.joinToString("\n")
        return PreparedInvitationSms(body, body.replace(url, "[픽업 초대 링크]"), local)
    }
}

/** The public voice action accepts references, never arbitrary message text or a phone number. */
data class InvitationShareRequest(val appointmentId: String? = null, val recipientName: String? = null) {
    companion object {
        val parameters = setOf("appointmentId", "recipientName")
        fun parse(raw: String): InvitationShareRequest {
            require(raw.length <= 2048)
            fun decode(text: String): Any? {
                val tokenizer = JSONTokener(text)
                val value = tokenizer.nextValue()
                require(tokenizer.nextClean() == '\u0000')
                return value
            }
            var value = decode(raw)
            if (value is String) value = decode(value)
            val fields = value as? JSONObject ?: throw IllegalArgumentException("Invalid sharing request")
            require(fields.keys().asSequence().all { it in parameters })
            fun field(k: String): String? {
                if (!fields.has(k)) return null
                require(fields.get(k) is String)
                return fields.getString(k).trim().takeIf { it.isNotEmpty() }
            }
            val id = field("appointmentId")
            require(id == null || id.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")))
            val name = field("recipientName")
            require(name == null || (name.length <= 80 && name.none { it.isISOControl() }))
            return InvitationShareRequest(id, name)
        }
    }
}
