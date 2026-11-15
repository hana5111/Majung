package kr.ddakta.pickup

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class InvitationSmsTest {
    private val now = Instant.parse("2026-11-14T10:00:00Z")
    private val appointment = Appointment("a72ef09e-bde8-4a1c-9477-317e885862f2", "아버지", "지우", "수원역", "2번 출구",
        Instant.parse("2026-11-15T09:00:00Z"), "", "PENDING", "a".repeat(43), "")

    @Test fun formatsTheActualInvitationLinkAndKoreanMeetingTime() {
        val sms = InvitationSms.prepare(appointment, "https://pickup.example/", now)
        assertFalse(sms.localOnly)
        assertTrue(sms.body.contains("11월 15일 (일) 오후 6:00"))
        assertTrue(sms.body.contains("수원역 2번 출구"))
        assertTrue(sms.body.contains("https://pickup.example/invite#${appointment.inviteToken}"))
        assertFalse(sms.preview.contains(appointment.inviteToken))
        assertTrue(sms.preview.contains("[픽업 초대 링크]"))
    }

    @Test fun developmentOriginsAreExplicitlyLabelledInBothPreviewAndBody() {
        for (origin in listOf("http://127.0.0.1:8787", "http://localhost:8787", "http://10.0.2.2:8787", "http://[::1]:8787")) {
            val sms = InvitationSms.prepare(appointment, origin, now)
            assertTrue(origin, sms.localOnly)
            assertTrue(sms.body.contains("개발 테스트"))
            assertTrue(sms.preview.contains("다른 휴대폰에서 열리지 않습니다"))
        }
    }

    @Test fun cannotComposeCancelledExpiredOrMissingInvitations() {
        for (a in listOf(appointment.copy(status = "CANCELLED"), appointment.copy(status = "DECLINED"),
            appointment.copy(meetingAt = now), appointment.copy(inviteToken = ""), appointment.copy(inviteToken = "x#recipient"))) {
            assertThrows(IllegalArgumentException::class.java) { InvitationSms.prepare(a, "https://pickup.example", now) }
        }
    }

    @Test fun rejectsNonWebOrAmbiguousOrigins() {
        for (origin in listOf("javascript:alert(1)", "https://user:secret@pickup.example", "https://pickup.example?to=other", "https://pickup.example#other", "not-a-url")) {
            assertThrows(origin, IllegalArgumentException::class.java) { InvitationSms.prepare(appointment, origin, now) }
        }
    }

    @Test fun normalizesOneSelectedPhoneNumber() {
        assertEquals(SmsRecipient("지우", "01012345678"), SmsRecipient.from(" 지우 ", "010-1234-5678"))
        assertEquals("+12025550147", SmsRecipient.from("테스트", "+1 (202) 555-0147").number)
        assertEquals("선택한 연락처", SmsRecipient.from("", "01012345678").name)
    }

    @Test fun rejectsMultipleRecipientsAndUriOrControlInjection() {
        for (number in listOf("01012345678;01087654321", "01012345678,01087654321", "01012345678?body=other", "010\n12345678", "*123#", "123", "a01012345678", "++821012345678")) {
            assertThrows(number, IllegalArgumentException::class.java) { SmsRecipient.from("지우", number) }
        }
    }

    @Test fun sharingRequestAcceptsOnlyOptionalAppointmentReferenceAndNameHint() {
        val json = """{"appointmentId":"${appointment.id}","recipientName":"지우"}"""
        assertEquals(InvitationShareRequest(appointment.id, "지우"), InvitationShareRequest.parse(json))
        assertEquals(InvitationShareRequest(appointment.id, "지우"), InvitationShareRequest.parse(JSONObject.quote(json)))
        assertEquals(InvitationShareRequest(), InvitationShareRequest.parse("""{"appointmentId":"","recipientName":" "}"""))
    }

    @Test fun voiceCannotSupplyArbitraryRecipientMessageOrLink() {
        for (raw in listOf("""{"phone":"01012345678"}""", """{"body":"send this"}""", """{"url":"https://other.example"}""",
            """{"appointmentId":"made-up"}""", """{"recipientName":123}""", """{"recipientName":"a\nb"}""", "{} {}", "[]")) {
            assertThrows(raw, IllegalArgumentException::class.java) { InvitationShareRequest.parse(raw) }
        }
    }
}
