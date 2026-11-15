package kr.ddakta.pickup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class TripRuntimeState(val appointmentId: String = "", val running: Boolean = false,
    val readyToShare: Boolean = false, val passengerName: String = "", val notice: String = "")

object PickupTripRuntime {
    internal val mutable = MutableStateFlow(TripRuntimeState())
    val state = mutable.asStateFlow()
    fun invitationOpened(id: String) { if (mutable.value.appointmentId == id) mutable.value = mutable.value.copy(readyToShare = false) }
}

/** Started by the driver's departure action; only the selected destination and ETA reach the pickup server. */
class PickupTripService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var navigation: PickupNavigation? = null
    private var appointment: Appointment? = null
    private var worker: Job? = null
    private var stopping = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "픽업 도착 시간 공유", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION, notification("픽업 경로를 확인하고 있어요"))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSharing(); return START_NOT_STICKY }
        val id = intent?.getStringExtra("appointmentId") ?: run { stopSelf(); return START_NOT_STICKY }
        if (stopping || worker?.isActive == true) return START_NOT_STICKY
        val expected = runCatching { PickupLocation.parse(JSONObject(intent.getStringExtra("target").orEmpty())) }.getOrNull()
        if (expected == null) { stopSelf(); return START_NOT_STICKY }
        stopping = false
        PickupTripRuntime.mutable.value = TripRuntimeState(id, true, notice = "목적지와 내비 경로를 확인하고 있어요.")
        worker = scope.launch {
            val repo = PickupRepository(applicationContext)
            try {
                val current = withContext(Dispatchers.IO) { repo.list().find { it.id == id } }
                require(current != null && current.active && current.pickupLocation == expected) { "픽업 목적지가 변경됐어요. 다시 확인해 주세요." }
                appointment = current
                val nav = PickupNavigationPool.get(applicationContext).also { navigation = it; PickupNavigationPool.acquire(it) }
                withTimeout(15_000) { while (!nav.state.value.connected) { require(!nav.state.value.failed) { nav.state.value.notice }; delay(200) } }
                withTimeoutOrNull(2000) { while (nav.state.value.updatedAtElapsedMs == null || nav.state.value.activeRoute == null) delay(100) }
                if (!nav.isGuiding(expected)) nav.startRoute(expected)
                var shared = false
                var lastPublish = 0L
                val deadline = SystemClock.elapsedRealtime() + 30_000
                while (isActive && !stopping) {
                    nav.refresh()
                    delay(1000)
                    val snapshot = nav.state.value
                    val seconds = verifiedRemainingSeconds(expected, snapshot, SystemClock.elapsedRealtime())
                    val elapsed = SystemClock.elapsedRealtime()
                    if (seconds != null && (elapsed - lastPublish > 9000 || !shared)) {
                        val updated = withContext(Dispatchers.IO) { repo.updateTrip(current, "EN_ROUTE", System.currentTimeMillis(), seconds) }
                        val first = !shared; shared = true; lastPublish = elapsed
                        PickupTripRuntime.mutable.value = PickupTripRuntime.mutable.value.copy(running = true,
                            readyToShare = first || PickupTripRuntime.mutable.value.readyToShare, passengerName = current.passengerName,
                            notice = "${updated.timeLabel()} · 초대 링크에도 반영됩니다.")
                        if (android.os.Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(updated.timeLabel()))
                    } else if (shared && seconds == null && elapsed - lastPublish > 9000) {
                        val ended = snapshot.endedState
                        withContext(Dispatchers.IO) { repo.updateTrip(current, ended ?: "UNAVAILABLE", System.currentTimeMillis()) }
                        lastPublish = elapsed
                        PickupTripRuntime.mutable.value = PickupTripRuntime.mutable.value.copy(notice = if (ended == "ARRIVED") "픽업 목적지에 도착했어요." else "내비 도착 시간을 다시 확인하고 있어요.")
                        if (ended != null) break
                    }
                    if (!shared && (snapshot.failed || elapsed > deadline)) throw IllegalStateException(snapshot.notice.ifBlank { "내비 경로를 확인하지 못했어요. 목적지를 확인하고 다시 출발해 주세요." })
                    delay(2000)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                PickupTripRuntime.mutable.value = PickupTripRuntime.mutable.value.copy(notice = e.message?.takeIf { it.contains("요") || it.contains("주세요") }
                    ?: "도착 시간 공유가 중단됐어요. 서버와 내비 연결을 확인한 뒤 다시 출발해 주세요.")
            } finally {
                releaseNavigation()
                if (!stopping) {
                    PickupTripRuntime.mutable.value = PickupTripRuntime.mutable.value.copy(running = false)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }
    private fun stopSharing() {
        if (stopping) return
        stopping = true
        val a = appointment
        scope.launch {
            try {
                // Wait for any in-flight ETA request before publishing the final state.
                worker?.cancelAndJoin()
                if (a != null) runCatching { withContext(Dispatchers.IO) { PickupRepository(applicationContext).updateTrip(a, "STOPPED", System.currentTimeMillis()) } }
            } finally { stopSelf() }
        }
    }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_ddakta).setContentTitle("마중 · 도착 시간 공유")
            .setContentText(text).setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "공유 종료", stop).build()).build()
    }
    override fun onDestroy() {
        releaseNavigation(); scope.cancel()
        PickupTripRuntime.mutable.value = PickupTripRuntime.mutable.value.copy(running = false)
        super.onDestroy()
    }
    private fun releaseNavigation() { val nav = navigation; navigation = null; if (nav != null) PickupNavigationPool.release(nav) }
    companion object {
        private const val CHANNEL = "pickup_eta"
        private const val NOTIFICATION = 101
        private const val STOP = "kr.ddakta.pickup.STOP_ETA"
        fun start(context: Context, a: Appointment) {
            val active = PickupTripRuntime.state.value
            require(!active.running || active.appointmentId == a.id) { "진행 중인 픽업의 공유를 종료한 뒤 새 픽업을 시작해 주세요." }
            ContextCompat.startForegroundService(context, Intent(context, PickupTripService::class.java)
                .putExtra("appointmentId", a.id).putExtra("target", requireNotNull(a.pickupLocation).toJson().toString()))
        }
        fun stop(context: Context) { context.startService(Intent(context, PickupTripService::class.java).setAction(STOP)) }
    }
}
