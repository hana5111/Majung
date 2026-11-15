package kr.ddakta.pickup

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun PickupNavigationPanel(a: Appointment, foreground: Boolean, saving: Boolean, onSaveLocation: suspend (PickupLocation) -> String?,
    automaticSearchKeyword: String? = null, previousSearch: PickupPlaceSearch? = null, onAutomaticSearchHandled: (PickupPlaceSearch?) -> Unit = {}) {
    val context = LocalContext.current
    val navigation = remember(a.id) { PickupNavigationPool.get(context) }
    val repository = remember { PickupRepository(context) }
    val scope = rememberCoroutineScope()
    val state by navigation.state.collectAsState()
    val tripRuntime by PickupTripRuntime.state.collectAsState()
    val ownTrip = tripRuntime.appointmentId == a.id
    val currentAppointment by rememberUpdatedState(a)
    var elapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var choosing by rememberSaveable(a.id) { mutableStateOf(false) }
    var editingSearch by rememberSaveable(a.id) { mutableStateOf(false) }
    var keyword by rememberSaveable(a.id) { mutableStateOf("${a.place} ${a.meetingPoint}".trim()) }
    var searchJson by rememberSaveable(a.id) { mutableStateOf("") }
    val search = remember(searchJson, previousSearch) { runCatching { PickupPlaceSearch.parse(searchJson) }.getOrNull() ?: previousSearch }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    DisposableEffect(navigation, foreground) {
        if (foreground) PickupNavigationPool.acquire(navigation)
        onDispose { if (foreground) PickupNavigationPool.release(navigation) }
    }
    fun beginSearch(query: String) {
        if (!readyForPlaceSearch(state) || query.isBlank()) return
        val started = PickupPlaceSearch(query.trim(), state.destination?.takeIf { state.activeRoute == true }?.pickupLocationOrNull())
        searchJson = started.toJson()
        onAutomaticSearchHandled(started)
        editingSearch = false
        error = ""
        if (!navigation.searchPlace(started.keyword, "")) error = "내비 검색을 열지 못했어요. 연결을 확인하고 다시 검색해 주세요."
    }
    LaunchedEffect(automaticSearchKeyword, foreground, state.connected, state.failed, state.destinationReceived, state.activeRoute, saving) {
        if (automaticSearchKeyword == null || !foreground || saving) return@LaunchedEffect
        if (a.pickupLocation != null || !a.active) { onAutomaticSearchHandled(null); return@LaunchedEffect }
        if (state.failed) { onAutomaticSearchHandled(null); error = state.notice; return@LaunchedEffect }
        // Read the existing destination first, so an unrelated old route cannot become a search result.
        if (readyForPlaceSearch(state)) beginSearch(automaticSearchKeyword)
    }
    LaunchedEffect(navigation, foreground) {
        if (foreground) while (true) { delay(5000); elapsed = SystemClock.elapsedRealtime(); navigation.refresh() }
    }
    val ageMs = state.updatedAtElapsedMs?.let { (elapsed - it).coerceAtLeast(0L) }
    val fresh = ageMs != null && ageMs <= 30_000
    val destination = state.destination
    val target = a.pickupLocation
    val matches = target != null && destination != null && target.matches(destination.latitude, destination.longitude, destination.poiId, destination.poiProvider)
    val guiding = fresh && matches && state.activeRoute == true && !state.routeRequestPending
    val candidate = search?.candidate(state, SystemClock.elapsedRealtime())
    fun depart() {
        if (checking || saving || state.routeRequestPending || target == null) return
        checking = true; error = ""
        scope.launch {
            try {
                val latest = withContext(Dispatchers.IO) { repository.list().find { it.id == a.id } }
                val verified = verifiedDepartureLocation(currentAppointment, latest)
                if (ownTrip && tripRuntime.running && a.canShare) navigation.openNavigation()
                else PickupTripService.start(context, requireNotNull(latest))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = if (e is IllegalArgumentException) e.message.orEmpty() else "출발 전 약속을 확인하지 못했어요. 서버 연결을 확인한 뒤 다시 눌러 주세요."
            } finally { checking = false }
        }
    }
    fun confirmPlaceAndDepart(chosen: PickupLocation) {
        if (checking || saving || !foreground) return
        checking = true; error = ""
        scope.launch {
            try {
                val result = onSaveLocation(chosen)
                if (result != null) { error = result; return@launch }
                val latest = withContext(Dispatchers.IO) { repository.list().find { it.id == a.id } }
                verifiedDepartureLocation(a.copy(pickupLocation = chosen), latest)
                PickupTripService.start(context, requireNotNull(latest))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = e.message?.takeIf { it.contains("주세요") } ?: "장소 확인과 출발을 완료하지 못했어요. 저장된 장소를 확인한 뒤 다시 출발해 주세요."
            } finally { checking = false }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        val displayedPlace = target ?: candidate
        Row(Modifier.fillMaxWidth().heightIn(min = 240.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = 24.dp)) {
                Text("약속 위치", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
                Text(displayedPlace?.name ?: a.place, color = Ink, fontSize = 25.sp, fontWeight = FontWeight.Medium)
                if (displayedPlace?.address?.isNotBlank() == true)
                    Text(displayedPlace.address, color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                if (a.meetingPoint.isNotBlank() && (target != null || candidate == null))
                    Text(a.meetingPoint, color = Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 7.dp))
            }
            Column(Modifier.width(184.dp).align(Alignment.Bottom), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                GlassButton(primary = true, enabled = foreground && state.connected && !saving && !checking && !state.routeRequestPending &&
                    (target != null || (candidate != null && !editingSearch) || (readyForPlaceSearch(state) && keyword.isNotBlank())) && !(ownTrip && tripRuntime.running && !a.canShare),
                    onClick = { when {
                        target != null -> depart()
                        candidate != null && !editingSearch -> confirmPlaceAndDepart(candidate)
                        else -> beginSearch(keyword)
                    } }, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                    Text(if (checking) "픽업 확인 중…" else if (ownTrip && tripRuntime.running && !a.canShare) "출발 확인 중…"
                        else if (target == null && (candidate == null || editingSearch)) "내비에서 위치 찾기"
                        else if (target == null) "이 위치로 길 안내"
                        else if (ownTrip && tripRuntime.running && a.canShare) "길 안내 보기" else "길 안내 시작",
                        fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
                TextButton(enabled = !saving && !checking && !state.routeRequestPending && (target == null || state.connected),
                    onClick = { if (target != null) choosing = true else editingSearch = !editingSearch },
                    colors = ButtonDefaults.textButtonColors(contentColor = Forest, disabledContentColor = Muted.copy(alpha = .65f)),
                    modifier = Modifier.height(48.dp)) {
                    Text(if (target != null) "픽업 위치 변경" else if (editingSearch) "수정 닫기" else "검색어 수정",
                        fontSize = 14.sp, fontWeight = FontWeight.Normal, maxLines = 1)
                }
            }
        }
        if (target == null) {
            if (candidate != null) {
                Text("이곳에서 만날지 확인한 뒤 길 안내를 시작해 주세요.", color = Forest, fontSize = 14.sp, lineHeight = 23.sp, modifier = Modifier.padding(top = 12.dp))
            } else Text(if (state.connecting) "차량 내비를 연결하고 있어요."
                else "아직 만날 장소가 선택되지 않았어요. 내비 검색 결과에서 장소를 목적지로 정한 뒤 돌아와 주세요.",
                color = Muted, fontSize = 14.sp, lineHeight = 23.sp, modifier = Modifier.padding(top = 14.dp))
            if (editingSearch) OutlinedTextField(value = keyword, onValueChange = { keyword = it.take(200) }, singleLine = true,
                label = { Text("검색할 장소") }, colors = glassFieldColors(), modifier = Modifier.fillMaxWidth().padding(top = 14.dp))
        }
        val navigationNotice = if (!guiding && target != null) when {
            state.connecting -> "차량 내비를 연결하고 있어요."
            state.routeRequestPending -> "픽업 위치로 경로를 요청하고 있어요."
            state.failed -> state.notice
            state.activeRoute == true && !matches -> "현재 내비 목적지가 픽업 위치와 달라요. 길 안내를 시작하면 픽업 위치로 변경해요."
            !fresh && state.activeRoute == true -> "경로 정보가 갱신되지 않았어요. 차량 내비에서 확인해 주세요."
            else -> state.notice
        } else ""
        if (navigationNotice.isNotBlank()) Text(navigationNotice, color = if (state.failed) Amber else Muted,
            fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 12.dp))
        if (error.isNotBlank()) Text(error, color = Amber, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        if (ownTrip && tripRuntime.notice.isNotBlank()) Text(tripRuntime.notice, color = Forest, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.failed) TextButton(enabled = !checking, onClick = { navigation.close(); navigation.connect() }) { Text("다시 연결", color = Forest, fontSize = 12.sp) }
            if (ownTrip && tripRuntime.running) TextButton(onClick = { PickupTripService.stop(context) }) { Text("도착 시간 공유 종료", color = Muted, fontSize = 12.sp) }
        }
    }
    if (choosing) PickupLocationPicker(navigation, foreground, "${a.place} ${a.meetingPoint}", target, saving,
        onDismiss = { choosing = false }, onSelect = onSaveLocation)
}
