package kr.ddakta.pickup.gleo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kr.ddakta.pickup.BuildConfig
import kr.ddakta.pickup.PickupRepository
import kr.ddakta.pickup.PickupPlaceSearch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** The on-device adapter. Callbacks are addressed only to the configured Gleo package. */
object GleoRuntime {
    const val SESSIONS = "sessions"
    const val LAST_REPLY = "last_reply"
    const val REPLY_VERSION = "reply_version"
    const val NAVIGATION_HANDOFF = "navigation_handoff"
    private const val NAVIGATION_SESSIONS = "navigation_sessions"
    @Volatile private var coordinator: GleoPickup? = null
    private val profile = java.security.MessageDigest.getInstance("SHA-256").digest(BuildConfig.API_URL.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    fun preferences(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences("ddakta_gleo_$profile", Context.MODE_PRIVATE)
    fun store(context: Context): GleoSessionStore = object : GleoSessionStore {
        private val prefs = preferences(context)
        override fun read(): List<GleoSession> = GleoJson.decode(prefs.getString(SESSIONS, "[]") ?: "[]")
        override fun write(sessions: List<GleoSession>) {
            check(prefs.edit().putString(SESSIONS, GleoJson.encode(sessions)).commit()) { "Could not persist the pickup draft." }
        }
    }
    fun latest(context: Context): GleoSession? = runCatching { store(context).read().lastOrNull() }.getOrNull()
    fun navigationHandoff(context: Context): GleoNavigationHandoff? = runCatching {
        preferences(context).getString(NAVIGATION_HANDOFF, null)?.let(GleoNavigationHandoff::parse)
    }.getOrNull()

    @Synchronized internal fun queueNavigation(context: Context, reply: GleoReply) {
        val request = GleoNavigationHandoff.from(reply) ?: return
        val prefs = preferences(context)
        val handled = prefs.getStringSet(NAVIGATION_SESSIONS, emptySet()).orEmpty()
        if (request.sessionId in handled) return
        val retained = store(context).read().map { it.id }.toSet() + request.sessionId
        check(prefs.edit().putString(NAVIGATION_HANDOFF, request.toJson())
            .putStringSet(NAVIGATION_SESSIONS, (handled + request.sessionId).intersect(retained)).commit())
    }

    @Synchronized fun consumeNavigation(context: Context, sessionId: String) {
        val request = navigationHandoff(context) ?: return
        if (request.sessionId != sessionId || !request.pending) return
        check(preferences(context).edit().putString(NAVIGATION_HANDOFF, request.copy(pending = false).toJson()).commit())
    }
    @Synchronized fun beginNavigationSearch(context: Context, sessionId: String, search: PickupPlaceSearch) {
        val request = navigationHandoff(context) ?: return
        if (request.sessionId != sessionId) return
        check(preferences(context).edit().putString(NAVIGATION_HANDOFF, request.copy(pending = false, search = search).toJson()).commit())
    }
    fun coordinator(context: Context): GleoPickup = coordinator ?: synchronized(this) {
        coordinator ?: run {
            val repository = PickupRepository(context.applicationContext, connectTimeoutMs = 800, readTimeoutMs = 1500)
            GleoPickup(store(context), create = { d, requestId ->
                val a = repository.create(d.name, d.contact, d.place, d.point, null, d.note, requestId)
                CreatedPickup(a.id, a.status)
            }).also { coordinator = it }
        }
    }
    @Synchronized fun respond(context: Context, reply: GleoReply) {
        queueNavigation(context, reply)
        val raw = GleoJson.reply(reply).toString()
        // No implicit broadcast: session and confirmation references must not reach unrelated apps.
        GleoTransport(context, BuildConfig.GLEO_PACKAGE).send(raw)
        // Private local receipt, useful to the app and integration tests; never log arguments or links.
        val prefs = preferences(context)
        prefs.edit().putString(LAST_REPLY, raw).putLong(REPLY_VERSION, prefs.getLong(REPLY_VERSION, 0) + 1).apply()
    }
}

internal class GleoTransport(private val context: Context, private val targetPackage: String) {
    fun send(rawReply: String) {
        context.sendBroadcast(Intent(GLEO_CALLBACK_ACTION).setPackage(targetPackage)
            .putExtra("packageName", context.packageName).putExtra("response", rawReply))
    }
}

class GleoActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!GleoActionDispatcher.accepts(intent)) return
        val pending = goAsync()
        GleoActionDispatcher.dispatch(context, intent) { pending.finish() }
    }
}

/** Activity calls and older broadcast calls use the same deadline and idempotent coordinator. */
object GleoActionDispatcher {
    private val workers = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    fun accepts(intent: Intent) = intent.action in setOf(CREATE_PICKUP_ACTION, QUALIFIED_CREATE_PICKUP_ACTION)

    fun dispatch(context: Context, intent: Intent, onComplete: () -> Unit = {}) {
        if (!accepts(intent)) return
        val app = context.applicationContext
        val finished = AtomicBoolean(false)
        val started = SystemClock.elapsedRealtime()
        fun complete(reply: GleoReply) {
            if (finished.compareAndSet(false, true)) {
                try { GleoRuntime.respond(app, reply) } finally { onComplete() }
            }
        }
        // The documented Gleo deadline is 7 s. Return once, within 6 s, even if a service stalls.
        val timeout = Runnable { complete(GleoReply("fail", "The app could not verify completion before the response deadline. Check the app before retrying invitation creation.", prompt = "처리 결과를 확인하지 못했어요. 앱에서 확인한 후 다시 시도해 주세요.")) }
        main.postDelayed(timeout, 6000)
        try {
            workers.execute {
                val reply = try {
                    val raw = if (intent.hasExtra("ACTION_PARAM")) {
                        intent.getStringExtra("ACTION_PARAM") ?: throw IllegalArgumentException("Invalid action parameters.")
                    } else {
                        // Some Gleo builds send each documented parameter as a separate extra.
                        val json = org.json.JSONObject()
                        intent.extras?.keySet()?.filter { it in GleoJson.parameterNames }?.forEach { k ->
                            @Suppress("DEPRECATION") val value = intent.extras?.get(k)
                            require(value is String) { "Pickup parameters must be strings." }
                            json.put(k, value)
                        }
                        json.toString()
                    }
                    val request = GleoJson.request(raw)
                    if (SystemClock.elapsedRealtime() - started >= 5000) GleoReply("fail", "The action expired before processing began. Please retry.")
                    else GleoRuntime.coordinator(app).handle(request) { SystemClock.elapsedRealtime() - started < 5000 }
                } catch (_: Exception) { GleoReply("fail", "The pickup request could not be processed. Check the supplied parameters and the app connection.") }
                main.removeCallbacks(timeout)
                complete(reply)
            }
        } catch (_: Exception) {
            main.removeCallbacks(timeout)
            complete(GleoReply("fail", "The app is busy. Please retry the pickup request."))
        }
    }
}
