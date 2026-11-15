package kr.ddakta.pickup

import android.Manifest
import android.content.Intent
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** Verifies device integration without reading the phonebook, launching a composer or sending SMS. */
@RunWith(AndroidJUnit4::class)
class InvitationSharingIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun selectedPhonePickerAndSmsComposerResolveOnPleos() {
        val picker = InvitationSmsAndroid.contactPicker()
        assertEquals(Intent.ACTION_PICK, picker.action)
        assertEquals(Phone.CONTENT_TYPE, picker.type)
        assertNotNull("No phone-number picker is available", picker.resolveActivity(context.packageManager))
        val appointment = Appointment("a72ef09e-bde8-4a1c-9477-317e885862f2", "아버지", "테스트", "수원역", "2번 출구",
            Instant.parse("2030-11-15T09:00:00Z"), "", "PENDING", "a".repeat(43), "")
        val sms = InvitationSms.prepare(appointment, "http://127.0.0.1:8787", Instant.parse("2026-11-14T10:00:00Z"))
        val composer = InvitationSmsAndroid.composer(SmsRecipient.from("마중 문자연동 테스트", "+1 (202) 555-0147"), sms)
        assertEquals(Intent.ACTION_SENDTO, composer.action)
        assertEquals("smsto", composer.data!!.scheme)
        assertEquals("+12025550147", composer.data!!.schemeSpecificPart)
        assertEquals(sms.body, composer.getStringExtra("sms_body"))
        assertNotNull("No messaging app accepts an SMS draft", composer.resolveActivity(context.packageManager))
    }

    @Test fun nameLookupRequestsReadPermissionButNeverContactWriteOrSmsSend() {
        @Suppress("DEPRECATION") val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        assertTrue(permissions.contains(Manifest.permission.READ_CONTACTS))
        assertFalse(permissions.contains(Manifest.permission.WRITE_CONTACTS))
        assertFalse(permissions.contains(Manifest.permission.SEND_SMS))
    }

    @Test fun gleoSchemaOpensAnActivityAndDoesNotOfferAnAutomaticSendFunction() {
        val schema = JSONObject(context.assets.open("schema.json").bufferedReader().use { it.readText() })
        val actions = schema.getJSONArray("actionCalls")
        val fn = (0 until actions.length()).map { actions.getJSONObject(it).getJSONObject("function") }.single { it.getString("name") == SHARE_INVITATION_ACTION }
        assertEquals("activity", fn.getString("component"))
        assertEquals(InvitationShareRequest.parameters, fn.getJSONObject("parameters").getJSONObject("properties").keys().asSequence().toSet())
        for (action in listOf(SHARE_INVITATION_ACTION, QUALIFIED_SHARE_INVITATION_ACTION)) {
            @Suppress("DEPRECATION") val activities = context.packageManager.queryIntentActivities(Intent(action).setPackage(context.packageName), PackageManager.MATCH_DEFAULT_ONLY)
            assertTrue("Sharing action did not resolve: $action", activities.any { it.activityInfo.exported && it.activityInfo.name == ContactInviteActivity::class.java.name })
        }
    }

    @Test fun voiceCallbackConfirmsOnlyTheScreenAndContainsNoRecipientOrInvitationSecrets() {
        for (opened in listOf(true, false)) for (lookup in listOf(true, false)) {
            val json = InvitationShareVoice.response(opened, lookup)
            assertEquals(SHARE_INVITATION_ACTION, json.getString("functionName"))
            assertEquals(if (opened) "success" else "fail", json.getString("status"))
            val fields = JSONArray(json.getString("data")).getJSONObject(0)
            assertEquals(opened, fields.getBoolean("screenOpened"))
            assertFalse(fields.getBoolean("messageSent"))
            assertEquals(lookup, fields.getBoolean("contactLookupRequested"))
            assertTrue(fields.getBoolean("requiresContactReview"))
            assertTrue(fields.getBoolean("requiresFinalSendInMessagingApp"))
            assertEquals(setOf("screenOpened", "messageSent", "contactLookupRequested", "requiresContactReview", "requiresFinalSendInMessagingApp"), fields.keys().asSequence().toSet())
        }
    }

    @Test fun nameLookupQueriesOnlyTheEncodedNameInTheLocalDirectory() {
        val fixture = FixtureContacts(listOf(arrayOf<Any?>(1L, "지우 (테스트)", "+12025550101", Phone.TYPE_MOBILE, null)))
        fixture.attachInfo(context, ProviderInfo().apply { authority = "kr.ddakta.fixture.contacts" })
        val result = InvitationSmsAndroid.findByName(ContentResolver.wrap(fixture), "지우 /? (테스트)")
        assertEquals("지우 /? (테스트)", fixture.lastUri!!.pathSegments.last())
        assertEquals("0", fixture.lastUri!!.getQueryParameter("directory"))
        assertEquals("1", fixture.lastUri!!.getQueryParameter(Phone.SEARCH_DISPLAY_NAME_KEY))
        assertEquals("0", fixture.lastUri!!.getQueryParameter(Phone.SEARCH_PHONE_NUMBER_KEY))
        assertEquals("+12025550101", result.unique!!.recipient.number)
        assertEquals("휴대전화", result.unique!!.numberLabel)
    }

    @Test fun invalidNumbersAreNotSelectedAndTooManyMatchesRequireReview() {
        val fixture = FixtureContacts(listOf(arrayOf<Any?>(1L, "지우", "+12025550101", Phone.TYPE_MOBILE, null)) +
            (2L..32L).map { arrayOf<Any?>(it, "지우", "invalid;number", Phone.TYPE_MOBILE, null) })
        fixture.attachInfo(context, ProviderInfo().apply { authority = "kr.ddakta.fixture.contacts" })
        val result = InvitationSmsAndroid.findByName(ContentResolver.wrap(fixture), "지우")
        assertTrue(result.incomplete)
        assertNull(result.unique)
        assertEquals(1, result.candidates.size)
    }

    private class FixtureContacts(private val rows: List<Array<out Any?>>) : ContentProvider() {
        var lastUri: Uri? = null
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            check(selection == null && selectionArgs == null)
            lastUri = uri
            return MatrixCursor(requireNotNull(projection)).apply { rows.forEach { addRow(it) } }
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("must not write contacts")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("must not delete contacts")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("must not update contacts")
    }
}
