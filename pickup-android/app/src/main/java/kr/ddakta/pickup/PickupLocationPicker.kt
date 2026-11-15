package kr.ddakta.pickup

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Explains the native handoff, then confirms the destination after the driver returns to Majung. */
@Composable
internal fun PickupLocationPicker(navigation: PickupNavigation, foreground: Boolean, query: String,
    current: PickupLocation?, saving: Boolean = false, onDismiss: () -> Unit,
    onSelect: suspend (PickupLocation) -> String?) {
    val state by navigation.state.collectAsState()
    val scope = rememberCoroutineScope()
    val contentDensity = LocalDensity.current
    var handoffApproved by rememberSaveable { mutableStateOf(false) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var openedNavigation by rememberSaveable { mutableStateOf(false) }
    var leftForNavigation by rememberSaveable { mutableStateOf(false) }
    var searchJson by rememberSaveable { mutableStateOf("") }
    val search = remember(searchJson) { runCatching { PickupPlaceSearch.parse(searchJson) }.getOrNull() }
    var error by remember { mutableStateOf("") }
    var selecting by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val busy = saving || selecting
    val candidate = if (openedNavigation && leftForNavigation) search?.candidate(state, elapsed) else null

    fun openSearch() {
        if (!handoffApproved || busy || !foreground || !readyForPlaceSearch(state)) return
        // Snapshot the old route before opening native search; returning alone is not a new selection.
        val started = PickupPlaceSearch(query.trim().take(200), state.destination?.takeIf { state.activeRoute == true }?.pickupLocationOrNull())
        attempted = true
        searchJson = started.toJson()
        leftForNavigation = false
        error = ""
        openedNavigation = navigation.openPlaceSearch(started.keyword)
        if (!openedNavigation) error = "차량 내비 검색 화면을 열지 못했어요. 다시 시도해 주세요."
    }

    LaunchedEffect(openedNavigation, foreground) {
        if (openedNavigation && !foreground) leftForNavigation = true
    }
    LaunchedEffect(openedNavigation, foreground, leftForNavigation) {
        if (openedNavigation && foreground && !leftForNavigation) {
            delay(4000)
            if (!leftForNavigation) {
                openedNavigation = false
                error = "차량 내비로 이동하지 못했어요. 다시 연결해 주세요."
            }
        }
    }
    LaunchedEffect(handoffApproved, foreground, attempted, state.connected, state.failed, state.destinationReceived, state.activeRoute, busy) {
        if (!handoffApproved || !foreground || attempted || busy) return@LaunchedEffect
        if (state.failed) { attempted = true; error = state.notice; return@LaunchedEffect }
        if (readyForPlaceSearch(state)) openSearch()
    }
    LaunchedEffect(handoffApproved, attempted, foreground) {
        if (handoffApproved && !attempted && foreground) {
            delay(10_000)
            if (!attempted) { attempted = true; error = "차량 내비 연결을 확인하지 못했어요. 다시 연결해 주세요." }
        }
    }
    LaunchedEffect(navigation, foreground, state.connected) {
        if (foreground && state.connected) {
            navigation.refresh()
            while (true) { elapsed = SystemClock.elapsedRealtime(); delay(1000) }
        }
    }

    if (foreground && !handoffApproved) {
        Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            CompositionLocalProvider(LocalDensity provides contentDensity) {
                Column(Modifier.widthIn(max = 740.dp).fillMaxWidth(.86f)
                    .clip(RoundedCornerShape(28.dp)).background(Page).liquidGlass().padding(32.dp)) {
                    Text(if (current != null) "픽업 위치 변경" else "만날 장소 선택", color = Ink,
                        fontSize = 27.sp, fontWeight = FontWeight.Medium)
                    Text(if (current != null) "내비 앱에서 변경할 픽업 위치를 선택해 주세요." else "내비 앱에서 만날 장소를 선택해 주세요.",
                        color = Ink, fontSize = 18.sp, lineHeight = 28.sp, modifier = Modifier.padding(top = 20.dp))
                    Text("목적지로 설정한 뒤 하단의 마중 아이콘을 눌러 돌아와 주세요.",
                        color = Muted, fontSize = 15.sp, lineHeight = 24.sp, modifier = Modifier.padding(top = 12.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 28.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically) {
                        TextButton(enabled = !busy, onClick = onDismiss) { Text("취소", color = Muted, fontSize = 16.sp) }
                        GlassButton(primary = true, enabled = !busy, onClick = { handoffApproved = true }, modifier = Modifier.height(54.dp)) {
                            Text("내비로 이동", fontSize = 17.sp)
                        }
                    }
                }
            }
        }
    } else if (foreground && (!openedNavigation || leftForNavigation || error.isNotBlank())) {
        Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            CompositionLocalProvider(LocalDensity provides contentDensity) {
                Column(Modifier.widthIn(max = 900.dp).fillMaxWidth(.92f).heightIn(max = 650.dp)
                    .clip(RoundedCornerShape(28.dp)).background(Page).liquidGlass().padding(32.dp)) {
                    Text(if (!attempted) "차량 내비 연결 중" else if (current != null) "픽업 위치 확인" else "만날 장소 확인",
                        color = Ink, fontSize = 27.sp, fontWeight = FontWeight.Medium)
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 20.dp)) {
                        if (candidate != null) {
                            Text("내비에서 선택한 목적지", color = Forest, fontSize = 14.sp)
                            Text(candidate.name, color = Ink, fontSize = 26.sp, lineHeight = 36.sp,
                                fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp))
                            if (candidate.address.isNotBlank()) Text(candidate.address, color = Muted, fontSize = 16.sp,
                                lineHeight = 25.sp, modifier = Modifier.padding(top = 10.dp))
                            Text(if (current != null) "이곳으로 픽업 위치를 변경할까요?" else "이곳을 만날 장소로 지정할까요?",
                                color = Forest, fontSize = 16.sp, modifier = Modifier.padding(top = 20.dp))
                        } else if (!attempted || state.connecting || (state.connected && !readyForPlaceSearch(state))) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                CircularProgressIndicator(Modifier.size(24.dp), color = Forest, strokeWidth = 2.dp)
                                Text(if (!openedNavigation) "검색 화면을 바로 열어드릴게요." else "선택한 목적지를 확인하고 있어요.",
                                    color = Muted, fontSize = 16.sp)
                            }
                        } else if (openedNavigation && leftForNavigation && error.isBlank() && !state.failed) {
                            Text("선택한 장소가 확인되지 않았어요. 내비 검색 결과에서 장소를 목적지로 정한 뒤 돌아와 주세요.",
                                color = Muted, fontSize = 16.sp, lineHeight = 26.sp)
                        }
                        if (current != null) Text("기존 약속 위치 · ${current.name}", color = Muted,
                            fontSize = 14.sp, modifier = Modifier.padding(top = 24.dp))
                        val notice = error.ifBlank { if (state.failed) state.notice else "" }
                        if (notice.isNotBlank()) Text(notice, color = Amber, fontSize = 15.sp,
                            lineHeight = 24.sp, modifier = Modifier.padding(top = 14.dp))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 28.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically) {
                        TextButton(enabled = !busy, onClick = onDismiss) { Text("취소", color = Muted, fontSize = 16.sp) }
                        if (state.failed || (attempted && !openedNavigation)) {
                            TextButton(enabled = !busy, onClick = {
                                attempted = false; openedNavigation = false; leftForNavigation = false; error = ""
                                navigation.close(); navigation.connect()
                            }) { Text("다시 연결", color = Forest, fontSize = 16.sp) }
                        } else if (leftForNavigation) {
                            TextButton(enabled = !busy && readyForPlaceSearch(state), onClick = { openSearch() }) {
                                Text("다시 검색", color = Forest, fontSize = 16.sp)
                            }
                        }
                        GlassButton(primary = true, enabled = !busy && candidate != null, onClick = {
                            val chosen = candidate ?: return@GlassButton
                            scope.launch {
                                selecting = true; error = ""
                                try {
                                    val result = onSelect(chosen)
                                    if (result == null) onDismiss() else error = result
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    error = "위치를 저장하지 못했어요. 연결을 확인하고 다시 시도해 주세요."
                                } finally { selecting = false }
                            }
                        }, modifier = Modifier.height(54.dp)) {
                            Text(if (busy) "저장하는 중…" else if (current != null) "이 위치로 변경" else "이 장소로 지정", fontSize = 17.sp)
                        }
                    }
                }
            }
        }
    }
}
