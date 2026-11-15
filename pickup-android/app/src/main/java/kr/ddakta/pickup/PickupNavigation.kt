package kr.ddakta.pickup

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NavigationSnapshot(
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val activeRoute: Boolean? = null,
    val destination: DestinationInfo? = null,
    val updatedAtElapsedMs: Long? = null,
    val destinationReceived: Boolean = false,
    val notice: String = "",
    val failed: Boolean = false,
    val recentPlaces: List<PickupLocation> = emptyList(),
    val routeRequestPending: Boolean = false,
    val endedState: String? = null
)

/** Owns a foreground-only NaviHelper session; sending a chosen place is a separate user action. */
class PickupNavigation(context: Context) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(NavigationSnapshot())
    val state = mutableState.asStateFlow()
    private var helper: NaviHelper? = null
    private var listener: NaviHelperEventListener? = null
    private var generation = 0
    private var requestedTarget: PickupLocation? = null
    private var routeReadyEvent = false
    private var routeAttempt = 0

    private fun dispatch(expected: Int, block: () -> Unit) {
        main.post { if (expected == generation) block() }
    }

    fun connect() {
        if (helper != null) return
        val current = ++generation
        mutableState.value = NavigationSnapshot(connecting = true)
        try {
            val sdk = NaviHelper(appContext)
            helper = sdk
            val events = object : NaviHelperEventListener {
                override fun onDestinationInfo(destinationInfo: DestinationInfo) = dispatch(current) {
                    val valid = destinationInfo.latitude.isFinite() && destinationInfo.longitude.isFinite() &&
                        destinationInfo.latitude in -90.0..90.0 && destinationInfo.longitude in -180.0..180.0 &&
                        destinationInfo.name.isNotBlank()
                    if (BuildConfig.DEBUG) android.util.Log.d("MajungNavigation", "destinationValid=$valid remainingSeconds=${destinationInfo.remainTime} requestedMatch=${requestedTarget?.matches(destinationInfo.latitude, destinationInfo.longitude, destinationInfo.poiId, destinationInfo.poiProvider)}")
                    mutableState.value = mutableState.value.copy(destination = destinationInfo.takeIf { valid },
                        updatedAtElapsedMs = SystemClock.elapsedRealtime().takeIf { valid }, destinationReceived = true)
                    confirmRequestedRoute()
                }
                override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = dispatch(current) {
                    val active = hasActivePickupRoute(routeStateInfo)
                    if (BuildConfig.DEBUG) android.util.Log.d("MajungNavigation", "routing=${routeStateInfo.isRouting} consent=${routeStateInfo.termsAgreement} mode=${routeStateInfo.naviMode}")
                    if (active) routeReadyEvent = true
                    mutableState.value = mutableState.value.copy(activeRoute = active,
                        destination = mutableState.value.destination.takeIf { active },
                        updatedAtElapsedMs = mutableState.value.updatedAtElapsedMs.takeIf { active },
                        notice = if (mutableState.value.routeRequestPending || mutableState.value.failed) mutableState.value.notice
                            else if (!routeStateInfo.termsAgreement) "차량 내비에서 이용 동의를 확인해 주세요."
                            else "")
                    confirmRequestedRoute()
                }
                override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) = dispatch(current) {
                    mutableState.value = mutableState.value.copy(recentPlaces = recentDestinationInfoList.take(30).mapNotNull { it.pickupLocationOrNull() }
                        .distinctBy { Triple(it.latitude, it.longitude, it.poiId) })
                }
                override fun onDestinationChanged() = dispatch(current) { invalidateRoute(); refresh() }
                override fun onRoutingCompleted() = dispatch(current) { routeReadyEvent = true; refresh() }
                override fun onReRouteEnded() = dispatch(current) { routeReadyEvent = true; refresh() }
                override fun onRouteStarted(routeStartInfo: RouteStartInfo) = dispatch(current) { routeReadyEvent = false; invalidateRoute("경로를 확인하고 있어요.") }
                override fun onRouteCancelled() = dispatch(current) { endRoute("차량 내비의 길 안내가 종료됐어요.") }
                override fun onRouteEnded() = dispatch(current) { endRoute("차량 내비의 길 안내가 종료됐어요.") }
                override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) = dispatch(current) { endRoute("차량 내비 목적지에 도착했어요.", "ARRIVED") }
                override fun onNavigationStatus(status: NavigationStatus) = dispatch(current) {
                    if (BuildConfig.DEBUG) android.util.Log.d("MajungNavigation", "navigationStatus=$status")
                    if (status == NavigationStatus.ROUTE_DRIVE) { routeReadyEvent = true; mutableState.value = mutableState.value.copy(activeRoute = true); refresh() }
                    else invalidateRoute(if (status == NavigationStatus.INITIALIZING) "경로를 확인하고 있어요."
                        else "", false)
                }
                override fun onError(errorCode: NaviErrorCode) = dispatch(current) { fail("내비 정보를 확인하지 못했어요. 차량 내비의 연결과 이용 상태를 확인해 주세요.") }
            }
            listener = events
            sdk.initialize(onConnected = {
                dispatch(current) {
                    try {
                        sdk.addListener(events)
                        mutableState.value = NavigationSnapshot(connected = true)
                        refresh()
                    } catch (_: Exception) { fail("차량 내비 연동 권한과 연결 상태를 확인해 주세요.") }
                }
            }, onFailed = {
                dispatch(current) {
                    close()
                    mutableState.value = NavigationSnapshot(failed = true, notice = "차량 내비에 연결하지 못했어요. Pleos 내비게이션의 실행 상태를 확인해 주세요.")
                }
            })
        } catch (_: Exception) {
            close()
            mutableState.value = NavigationSnapshot(failed = true, notice = "이 환경에서는 차량 내비를 연결하지 못했어요.")
        }
    }

    private fun invalidateRoute(notice: String = "", active: Boolean? = null) {
        mutableState.value = mutableState.value.copy(activeRoute = active, destination = null, updatedAtElapsedMs = null, destinationReceived = false, notice = notice)
    }

    private fun fail(notice: String) {
        requestedTarget = null
        routeReadyEvent = false
        invalidateRoute(notice)
        mutableState.value = mutableState.value.copy(connecting = false, failed = true, routeRequestPending = false)
    }

    private fun endRoute(notice: String, endedState: String = "STOPPED") {
        requestedTarget = null
        routeReadyEvent = false
        invalidateRoute(notice, false)
        mutableState.value = mutableState.value.copy(routeRequestPending = false, endedState = endedState)
    }

    fun refresh() {
        val sdk = helper ?: return
        if (!mutableState.value.connected) return
        try { sdk.getRouteStateInfo(); sdk.getDestinationInfo() }
        catch (_: Exception) { fail("차량 내비의 목적지 정보를 가져오지 못했어요.") }
    }

    fun searchPlace(place: String, meetingPoint: String): Boolean {
        val keyword = "$place $meetingPoint".trim()
        if (keyword.isBlank()) { fail("검색할 약속 장소를 확인해 주세요."); return false }
        return openPlaceSearch(keyword)
    }

    /** Native search rejects empty keywords; its recent-places screen also provides search entry. */
    fun openPlaceSearch(keyword: String = ""): Boolean {
        val query = keyword.trim()
        if (query.length > 200 || query.any { it.isISOControl() }) { fail("검색할 약속 장소를 확인해 주세요."); return false }
        return requestScreen(if (query.isBlank()) NavigationScreenRequest.RecentDestinations else NavigationScreenRequest.SearchPlace(query))
    }

    fun openNavigation() = requestScreen(NavigationScreenRequest.Foreground)

    fun isGuiding(target: PickupLocation): Boolean {
        val current = mutableState.value
        val destination = current.destination ?: return false
        val updated = current.updatedAtElapsedMs ?: return false
        return current.activeRoute == true && !current.routeRequestPending && SystemClock.elapsedRealtime() - updated in 0..30_000 &&
            target.matches(destination.latitude, destination.longitude, destination.poiId, destination.poiProvider)
    }

    fun loadPlaces() {
        if (!mutableState.value.connected) return
        try { helper?.getRecentDestinationInfo(); refresh() }
        catch (_: Exception) { fail("내비의 장소 목록을 가져오지 못했어요.") }
    }

    fun startRoute(target: PickupLocation) {
        val sdk = helper
        if (sdk == null || !mutableState.value.connected) { fail("차량 내비 연결을 확인한 뒤 다시 출발해 주세요."); return }
        if (mutableState.value.routeRequestPending) return
        requestedTarget = target
        routeReadyEvent = false
        val attempt = ++routeAttempt
        val current = generation
        invalidateRoute("저장한 승차 위치로 경로를 요청하고 있어요.")
        mutableState.value = mutableState.value.copy(routeRequestPending = true, failed = false, endedState = null)
        try {
            sdk.requestRoute(target.toRouteInfo())
            // The call returning is not proof of guidance. A matching live destination and route callback are required.
            main.postDelayed({
                if (current == generation && attempt == routeAttempt && mutableState.value.routeRequestPending)
                    fail("길 안내 시작 여부를 아직 확인하지 못했어요. 차량 내비에서 목적지와 경로를 확인해 주세요.")
            }, 25_000)
        } catch (_: Exception) { fail("저장한 위치로 길 안내를 요청하지 못했어요. 내비 연동 권한과 연결을 확인해 주세요.") }
    }

    private fun confirmRequestedRoute() {
        val target = requestedTarget ?: return
        val destination = mutableState.value.destination ?: return
        if (!routeReadyEvent || mutableState.value.activeRoute != true ||
            !target.matches(destination.latitude, destination.longitude, destination.poiId, destination.poiProvider)) return
        requestedTarget = null
        mutableState.value = mutableState.value.copy(routeRequestPending = false, failed = false, notice = "저장한 승차 위치로 길 안내 중이에요.")
        // Keep Majung available for invitation review. The foreground service retains this SDK session.
    }

    private fun requestScreen(request: NavigationScreenRequest): Boolean {
        val sdk = helper ?: return false
        if (!mutableState.value.connected) return false
        return try {
            sdk.requestNavigationScreen(request)
            mutableState.value = mutableState.value.copy(notice = "차량 내비에서 장소와 경로를 확인해 주세요.", failed = false)
            true
        } catch (_: Exception) { fail("내비 화면 요청을 처리하지 못했어요. 연동 권한과 차량 내비 상태를 확인해 주세요."); false }
    }

    fun close() {
        generation++
        routeAttempt++
        requestedTarget = null
        routeReadyEvent = false
        val sdk = helper
        helper = null
        listener?.let { runCatching { sdk?.removeListener(it) } }
        listener = null
        runCatching { sdk?.release() }
        mutableState.value = NavigationSnapshot()
    }
}

/** Navi callbacks are app-scoped. Screens and the ETA service share one connection and independent leases. */
object PickupNavigationPool {
    private var instance: PickupNavigation? = null
    private var leases = 0
    fun get(context: Context): PickupNavigation = instance ?: PickupNavigation(context.applicationContext).also { instance = it }
    fun acquire(navigation: PickupNavigation) { check(navigation === instance); leases++; navigation.connect() }
    fun release(navigation: PickupNavigation) { check(navigation === instance && leases > 0); leases--; if (leases == 0) navigation.close() }
}
