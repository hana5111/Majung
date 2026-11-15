package kr.ddakta.pickup.gleo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kr.ddakta.pickup.BuildConfig
import kr.ddakta.pickup.PickupRepository
import kr.ddakta.pickup.MainActivity
import kr.ddakta.pickup.VoiceDraft
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises Android transport and the real local API; this is NOT a Gleo speech/LLM test. */
@RunWith(AndroidJUnit4::class)
class GleoIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun schemaIsPackagedAndTheDeclaredActionResolvesToTheReceiver() {
        val schema = JSONObject(context.assets.open("schema.json").bufferedReader().use { it.readText() })
        assertEquals(context.packageName, schema.getString("packageName"))
        assertEquals(2, schema.getJSONArray("actionCalls").length())
        val fn = schema.getJSONArray("actionCalls").getJSONObject(0).getJSONObject("function")
        assertEquals(CREATE_PICKUP_ACTION, fn.getString("name"))
        assertEquals("activity", fn.getString("component"))
        val fields = fn.getJSONObject("parameters").getJSONObject("properties")
        assertFalse(fields.has("meetingTimeText"))
        assertFalse(fields.has("meetingAt"))
        assertEquals(BuildConfig.VERSION_NAME, schema.getString("version"))
        for (action in listOf(CREATE_PICKUP_ACTION, QUALIFIED_CREATE_PICKUP_ACTION)) {
            @Suppress("DEPRECATION") val receivers = context.packageManager.queryBroadcastReceivers(Intent(action).setPackage(context.packageName), 0)
            assertTrue("No exported receiver resolves $action", receivers.any { it.activityInfo.name == GleoActionReceiver::class.java.name && it.activityInfo.exported })
            @Suppress("DEPRECATION") val activities = context.packageManager.queryIntentActivities(Intent(action).setPackage(context.packageName), PackageManager.MATCH_DEFAULT_ONLY)
            assertTrue("No activity resolves $action", activities.any { it.activityInfo.name == MainActivity::class.java.name && it.activityInfo.exported })
        }
    }

    @Test fun navigationHandoffSurvivesRestartButRepeatedApprovalDoesNotReopenConsumedSearch() {
        val storageName = "navigation-handoff-test-${UUID.randomUUID()}"
        val storage = context.getSharedPreferences(storageName, Context.MODE_PRIVATE)
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = storage
        }
        try {
            val session = GleoSession(UUID.randomUUID().toString(), VoiceDraft(name = "검증용 탑승자", place = "수원역"),
                GleoPhase.REVIEW, expiresAt = java.time.Instant.now().plusSeconds(600))
            GleoRuntime.queueNavigation(isolated, GleoReply("asking", "Review required.", session))
            assertNull(GleoRuntime.navigationHandoff(isolated))
            val done = GleoReply("success", "Pickup prepared.", session.copy(phase = GleoPhase.CREATED, appointmentId = UUID.randomUUID().toString()))
            GleoRuntime.queueNavigation(isolated, done)
            val queued = GleoRuntime.navigationHandoff(isolated)!!
            assertTrue(queued.pending)
            assertEquals("수원역", queued.keyword)
            GleoRuntime.consumeNavigation(isolated, "another-session")
            assertTrue(GleoRuntime.navigationHandoff(isolated)!!.pending)
            GleoRuntime.consumeNavigation(isolated, session.id)
            // Re-read disk-backed preferences through the same public path used after activity recreation.
            assertFalse(GleoRuntime.navigationHandoff(isolated)!!.pending)
            GleoRuntime.queueNavigation(isolated, done)
            assertEquals(queued.copy(pending = false), GleoRuntime.navigationHandoff(isolated))
        } finally { context.deleteSharedPreferences(storageName) }
    }

    @Test fun immediatePickupNeedsOnlyPassengerAndDestination() {
        val store = object : GleoSessionStore {
            var sessions = emptyList<GleoSession>()
            override fun read() = sessions
            override fun write(sessions: List<GleoSession>) { this.sessions = sessions }
        }
        val core = GleoPickup(store, create = { _, _ -> error("must not create before approval") })
        val result = core.handle(GleoRequest(passengerName = "지우", place = "수원역"))
        assertEquals(GleoPhase.REVIEW, result.session!!.phase)
        val callback = JSONArray(GleoJson.reply(result).getString("data")).getJSONObject(0)
        assertFalse(callback.has("meetingAt"))
        assertTrue(callback.getBoolean("requiresConfirmation"))
    }

    @Test fun actualGleoApprovalWithBlankReferencesDoesNotRepeatReviewOrDuplicateCreation() {
        val store = object : GleoSessionStore {
            var sessions = emptyList<GleoSession>()
            override fun read() = sessions
            override fun write(sessions: List<GleoSession>) { this.sessions = sessions }
        }
        var creates = 0
        val core = GleoPickup(store, create = { _, _ -> creates++; CreatedPickup("isolated-test-invitation", "PENDING") },
            now = { java.time.Instant.parse("2026-11-14T13:16:00Z") })
        val body = JSONObject().put("operation", "collect").put("sessionId", "").put("confirmationToken", "")
            .put("passengerName", "지우").put("place", "수원역").put("meetingPoint", "3번 출구").put("meetingTimeText", "내일 오후 6시")
        val review = core.handle(GleoJson.request(body.toString()))
        assertEquals(GleoPhase.REVIEW, review.session!!.phase)
        assertEquals(0, creates)
        body.put("operation", "confirm")
        val made = core.handle(GleoJson.request(body.toString()))
        assertEquals("success", made.status)
        assertEquals(GleoPhase.CREATED, made.session!!.phase)
        assertEquals("success", core.handle(GleoJson.request(body.toString())).status)
        assertEquals(1, creates)
    }

    @Test fun callbackTransportDeliversDocumentedFieldsToTheAddressedPackage() {
        val latch = CountDownLatch(1)
        var response: Intent? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { response = i; latch.countDown() }
        }
        context.registerReceiver(receiver, IntentFilter(GLEO_CALLBACK_ACTION), Context.RECEIVER_EXPORTED)
        try {
            // A private test recipient verifies transport without impersonating the Gleo package.
            GleoTransport(context, context.packageName).send(GleoJson.reply(GleoReply("asking", "More pickup information is required.")).toString())
            assertTrue(latch.await(3, TimeUnit.SECONDS))
            assertEquals(context.packageName, response!!.getStringExtra("packageName"))
            val json = JSONObject(response!!.getStringExtra("response")!!)
            assertEquals(CREATE_PICKUP_ACTION, json.getString("functionName"))
            assertEquals("asking", json.getString("status"))
            assertEquals("voice", json.getString("mode"))
        } finally { context.unregisterReceiver(receiver) }
    }

    @Test fun activityCollectsReviewsAndCreatesExactlyOneRealInvitationWithNavigationSearch() {
        val unfinished = GleoRuntime.store(context).read().any {
            it.phase in setOf(GleoPhase.DRAFT, GleoPhase.REVIEW, GleoPhase.SUBMITTING) && it.expiresAt.isAfter(java.time.Instant.now())
        }
        // Never overwrite an actual in-progress draft when rerunning the developer test.
        assumeTrue("Finish or cancel the existing Gleo draft before running integration tests.", !unfinished)
        val name = "연동테스트지우${UUID.randomUUID().toString().take(6)}"
        // Match the real Gleo payload: unknown fields can be empty and its date can be stale.
        val first = request(JSONObject().put("operation", "collect").put("passengerName", name).put("place", "").put("meetingPoint", "").put("meetingAt", "2023-10-26T18:00:00+09:00"), directExtras = true, qualified = true)
        assertEquals("asking", first.getString("status"))
        val a = JSONArray(first.getString("data")).getJSONObject(0)
        val sessionId = a.getString("sessionId")
        assertFalse(a.getJSONArray("missingFields").toString().contains("meetingTimeText"))
        assertTrue(a.getJSONArray("missingFields").toString().contains("place"))
        assertFalse(a.has("meetingAt"))
        assertFalse(a.has("confirmationToken"))
        val review = request(JSONObject().put("operation", "collect").put("sessionId", sessionId).put("place", "수원역").put("meetingTimeText", "내일 오후 6시"), encoded = true)
        assertEquals("asking", review.getString("status"))
        val r = JSONArray(review.getString("data")).getJSONObject(0)
        assertTrue(r.getBoolean("requiresConfirmation"))
        assertEquals(name, r.getString("passengerName"))
        waitUntil("Majung did not open for the activity request") { majungIsResumed() }
        val confirm = JSONObject().put("operation", "confirm").put("sessionId", sessionId).put("confirmationToken", r.getString("confirmationToken"))
        val made = request(confirm, qualified = true)
        assertEquals(made.optString("message"), "success", made.getString("status"))
        val id = JSONArray(made.getString("data")).getJSONObject(0).getString("appointmentId")
        val handoff = GleoRuntime.navigationHandoff(context)!!
        assertEquals(sessionId, handoff.sessionId)
        assertEquals(id, handoff.appointmentId)
        assertEquals("수원역", handoff.keyword)
        waitUntil("The approved pickup did not hand focus to navigation") {
            GleoRuntime.navigationHandoff(context)?.pending == false && !majungIsResumed()
        }
        val again = request(confirm, qualified = true, viaActivity = false)
        assertEquals("success", again.getString("status"))
        assertEquals(id, JSONArray(again.getString("data")).getJSONObject(0).getString("appointmentId"))
        val list = PickupRepository(context).list()
        assertEquals(1, list.count { it.id == id })
        assertEquals(1, list.count { it.passengerName == name })
        assertFalse(GleoRuntime.navigationHandoff(context)!!.pending)
        val invitation = list.single { it.id == id }
        val connection = URL(BuildConfig.API_URL + "/api/invitation").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 2000; connection.readTimeout = 2000
            connection.setRequestProperty("Authorization", "Bearer ${invitation.inviteToken}")
            assertEquals(200, connection.responseCode)
            val passengerView = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            assertEquals(name, passengerView.getString("passengerName"))
            assertEquals("PENDING", passengerView.getString("status"))
            assertFalse(passengerView.has("inviteToken"))
        } finally { connection.disconnect() }
    }

    private fun majungIsResumed(): Boolean {
        var resumed = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { it is MainActivity }
        }
        return resumed
    }

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        fail(message)
    }

    private fun request(body: JSONObject, directExtras: Boolean = false, encoded: Boolean = false, qualified: Boolean = false, viaActivity: Boolean = true): JSONObject {
        val prefs = GleoRuntime.preferences(context)
        val previous = prefs.getLong(GleoRuntime.REPLY_VERSION, 0)
        val intent = Intent(if (qualified) QUALIFIED_CREATE_PICKUP_ACTION else CREATE_PICKUP_ACTION).setPackage(context.packageName)
        if (directExtras) body.keys().forEach { intent.putExtra(it, body.getString(it)) }
        else intent.putExtra("ACTION_PARAM", if (encoded) JSONObject.quote(body.toString()) else body.toString())
        val start = SystemClock.elapsedRealtime()
        // Match the current Gleo schema: each call opens/reuses Majung through its exported activity.
        if (viaActivity) context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) else context.sendBroadcast(intent)
        while (SystemClock.elapsedRealtime() - start < 6500) {
            if (prefs.getLong(GleoRuntime.REPLY_VERSION, 0) > previous) {
                val raw = prefs.getString(GleoRuntime.LAST_REPLY, null)
                assertNotNull(raw)
                return JSONObject(raw!!)
            }
            SystemClock.sleep(25)
        }
        fail("The pickup action did not return before the Gleo deadline.")
        error("unreachable")
    }
}
