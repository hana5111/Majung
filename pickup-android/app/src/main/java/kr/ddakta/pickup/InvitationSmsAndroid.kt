package kr.ddakta.pickup

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone

object InvitationSmsAndroid {
    fun contactPicker() = Intent(Intent.ACTION_PICK).setType(Phone.CONTENT_TYPE)

    fun readSelectedContact(resolver: ContentResolver, uri: Uri): SmsRecipient {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "연락처를 다시 선택해 주세요." }
        // Read only the single phone row the user selected and temporarily granted access to.
        return resolver.query(uri, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER), null, null, null)?.use { cursor ->
            require(cursor.moveToFirst()) { "선택한 연락처의 전화번호를 찾지 못했어요." }
            SmsRecipient.from(cursor.getString(cursor.getColumnIndexOrThrow(Phone.DISPLAY_NAME)).orEmpty(),
                cursor.getString(cursor.getColumnIndexOrThrow(Phone.NUMBER)).orEmpty())
        } ?: throw IllegalArgumentException("선택한 연락처를 읽지 못했어요.")
    }

    /** Caller must obtain READ_CONTACTS. Query only the spoken name, not the full phonebook. */
    fun findByName(resolver: ContentResolver, name: String): ContactSearchResult {
        val term = name.trim()
        require(term.isNotEmpty() && term.length <= 80 && term.none { it.isISOControl() })
        val uri = Phone.CONTENT_FILTER_URI.buildUpon().appendPath(term)
            .appendQueryParameter(ContactsContract.DIRECTORY_PARAM_KEY, "0")
            .appendQueryParameter(Phone.SEARCH_DISPLAY_NAME_KEY, "1")
            .appendQueryParameter(Phone.SEARCH_PHONE_NUMBER_KEY, "0").build()
        val candidates = mutableListOf<ContactCandidate>()
        var incomplete = false
        resolver.query(uri, arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.LABEL), null, null, null)?.use { cursor ->
            var scanned = 0
            while (cursor.moveToNext()) {
                if (scanned++ >= 30) { incomplete = true; break }
                val recipient = runCatching { SmsRecipient.from(
                    cursor.getString(cursor.getColumnIndexOrThrow(Phone.DISPLAY_NAME)).orEmpty(),
                    cursor.getString(cursor.getColumnIndexOrThrow(Phone.NUMBER)).orEmpty()) }.getOrNull() ?: continue
                val label = when (cursor.getInt(cursor.getColumnIndexOrThrow(Phone.TYPE))) {
                    Phone.TYPE_MOBILE -> "휴대전화"
                    Phone.TYPE_HOME -> "집"
                    Phone.TYPE_WORK -> "직장"
                    Phone.TYPE_CUSTOM -> cursor.getString(cursor.getColumnIndexOrThrow(Phone.LABEL)).orEmpty()
                        .filterNot { it.isISOControl() }.take(30).ifBlank { "전화번호" }
                    else -> "전화번호"
                }
                candidates += ContactCandidate(cursor.getLong(cursor.getColumnIndexOrThrow(Phone.CONTACT_ID)), recipient, label)
            }
        } ?: throw IllegalStateException("연락처 검색 결과를 읽지 못했어요.")
        return ContactSearchResult.from(candidates, incomplete)
    }

    fun composer(recipient: SmsRecipient, sms: PreparedInvitationSms): Intent {
        val checked = SmsRecipient.from(recipient.name, recipient.number)
        return Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", checked.number, null))
            .putExtra("sms_body", sms.body)
    }
}
