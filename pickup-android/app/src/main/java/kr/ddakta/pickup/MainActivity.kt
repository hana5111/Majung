package kr.ddakta.pickup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import kr.ddakta.pickup.gleo.GleoRuntime
import kr.ddakta.pickup.gleo.GleoDraftScreen
import kr.ddakta.pickup.gleo.GleoActionDispatcher
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val Ink = Color(0xFF20251F)
internal val Page = Color(0xFFF0F2F5)
internal val Panel = Color.White
internal val Line = Color(0xFFE7EAE4)
internal val Mint = Color(0xFFB4ED76)
internal val Forest = Color(0xFF204B22)
internal val Muted = Color(0xFF7B8278)
internal val Amber = Color(0xFF8A622B)
internal val Soft = Color(0xFFF6F7F4)
private val Seoul = ZoneId.of("Asia/Seoul")
private val dateLabel = DateTimeFormatter.ofPattern("M월 d일 (E) · a h:mm", Locale.KOREAN).withZone(Seoul)

class MainActivity : ComponentActivity() {
    private val foreground = mutableStateOf(false)
    private val requestedPickup = mutableStateOf<Pair<String, Long>?>(null)
    private fun openRequestedPickup(intent: Intent) {
        intent.getStringExtra(PassengerDemoStore.OpenAppointmentExtra)?.takeIf { it.isNotBlank() }?.let {
            requestedPickup.value = it to System.nanoTime()
            intent.removeExtra(PassengerDemoStore.OpenAppointmentExtra)
        }
    }
    override fun onStart() { super.onStart(); foreground.value = true }
    override fun onStop() { foreground.value = false; super.onStop() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openRequestedPickup(intent)
        val repository = PickupRepository(applicationContext)
        setContent { Ddakta(repository, foreground.value, requestedPickup.value) }
        if (savedInstanceState == null) GleoActionDispatcher.dispatch(this, intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRequestedPickup(intent)
        GleoActionDispatcher.dispatch(this, intent)
    }
}

@Composable
fun Ddakta(repository: PickupRepository, foreground: Boolean, requestedPickup: Pair<String, Long>? = null) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val scale = (maxWidth.value / 1100f).coerceIn(1f, 1.55f)
        CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale)) {
            DdaktaContent(repository, foreground, requestedPickup)
        }
    }
}

@Composable
private fun DdaktaContent(repository: PickupRepository, foreground: Boolean, requestedPickup: Pair<String, Long>?) {
    val scheme = lightColorScheme(primary = Forest, onPrimary = Color.White, background = Page, surface = Panel, onSurface = Ink, onBackground = Ink, outline = Line, surfaceVariant = Soft)
    MaterialTheme(colorScheme = scheme) {
        var screen by rememberSaveable { mutableStateOf("home") }
        var selectedId by rememberSaveable { mutableStateOf("") }
        var appointments by remember { mutableStateOf(emptyList<Appointment>()) }
        var loaded by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf("") }
        var saving by remember { mutableStateOf(false) }
        var cancelDialog by remember { mutableStateOf(false) }
        var localRevision by remember { mutableIntStateOf(0) }
        var manualRequestId by rememberSaveable { mutableStateOf(java.util.UUID.randomUUID().toString()) }
        var voiceDraft by rememberSaveable(stateSaver = VoiceDraftSaver) { mutableStateOf(VoiceDraft()) }
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val appName = stringResource(R.string.app_name)
        val active = appointments.filter { it.active }
        val selected = appointments.find { it.id == selectedId }
        val tripRuntime by PickupTripRuntime.state.collectAsState()
        LaunchedEffect(tripRuntime.readyToShare, tripRuntime.appointmentId, foreground, saving) {
            if (foreground && !saving && tripRuntime.readyToShare) {
                if (!BuildConfig.DEMO_MODE) context.startActivity(Intent(context, ContactInviteActivity::class.java)
                    .putExtra("appointmentId", tripRuntime.appointmentId).putExtra("recipientName", tripRuntime.passengerName))
                PickupTripRuntime.invitationOpened(tripRuntime.appointmentId)
            }
        }
        var gleoSession by remember { mutableStateOf(GleoRuntime.latest(context)) }
        var navigationHandoff by remember { mutableStateOf(GleoRuntime.navigationHandoff(context)) }
        LaunchedEffect(Unit) {
            if (screen == "passenger-demo") screen = "home"
            val recent = gleoSession
            if (screen == "home" && recent != null && recent.expiresAt.isAfter(Instant.now()) &&
                recent.phase !in setOf(kr.ddakta.pickup.gleo.GleoPhase.CANCELLED, kr.ddakta.pickup.gleo.GleoPhase.EXPIRED)) {
                if (recent.phase == kr.ddakta.pickup.gleo.GleoPhase.CREATED && recent.appointmentId != null) {
                    selectedId = recent.appointmentId; screen = "detail"
                } else screen = "gleo"
            }
        }
        LaunchedEffect(navigationHandoff?.sessionId, navigationHandoff?.pending) {
            val request = navigationHandoff?.takeIf { it.pending } ?: return@LaunchedEffect
            if (!request.expiresAt.isAfter(Instant.now())) GleoRuntime.consumeNavigation(context, request.sessionId)
            else { selectedId = request.appointmentId; screen = "detail" }
        }
        LaunchedEffect(navigationHandoff, selected) {
            val request = navigationHandoff?.takeIf { it.pending && it.appointmentId == selected?.id } ?: return@LaunchedEffect
            if (selected?.active != true || selected.pickupLocation != null)
                GleoRuntime.consumeNavigation(context, request.sessionId)
        }
        LaunchedEffect(requestedPickup) {
            requestedPickup?.let { selectedId = it.first; screen = "detail" }
        }
        DisposableEffect(context) {
            val prefs = GleoRuntime.preferences(context)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == GleoRuntime.NAVIGATION_HANDOFF) navigationHandoff = GleoRuntime.navigationHandoff(context)
                if (key == GleoRuntime.SESSIONS) {
                    gleoSession = GleoRuntime.latest(context)
                    if (gleoSession != null && !saving) {
                        val createdId = gleoSession?.appointmentId
                        if (createdId != null && gleoSession?.phase == kr.ddakta.pickup.gleo.GleoPhase.CREATED) { selectedId = createdId; screen = "detail" }
                        else screen = "gleo"
                    }
                }
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }


        suspend fun refresh() {
            if (saving) return
            val revision = localRevision
            try {
                val latest = withContext(Dispatchers.IO) { repository.list() }
                if (saving || revision != localRevision) return
                val confirmed = latest.firstOrNull { a -> a.status == "CONFIRMED" && appointments.any { it.id == a.id && it.status == "PENDING" } }
                appointments = latest; loaded = true; error = ""
                if (confirmed != null) scope.launch { snackbar.showSnackbar("${confirmed.passengerName}님이 수락했어요. 픽업 약속이 확정됐습니다.") }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message?.takeIf { it.contains("주세요") } ?: if (BuildConfig.DEMO_MODE) "저장된 약속을 읽지 못했어요. 다시 시도해 주세요."
                    else "약속 서버에 연결할 수 없어요. 서버 실행과 네트워크를 확인해 주세요."
            }
        }

        suspend fun createAppointment(name: String, contact: String, place: String, point: String, time: Instant?, note: String, location: PickupLocation? = null): String? {
            if (saving) return "약속을 만드는 중이에요. 잠시만 기다려 주세요."
            saving = true
            return try {
                val a = withContext(Dispatchers.IO) { repository.create(name, contact, place, point, time, note, clientRequestId = manualRequestId, pickupLocation = location) }
                localRevision++
                appointments = (appointments.filterNot { it.id == a.id } + a)
                selectedId = a.id; screen = "detail"; voiceDraft = VoiceDraft()
                manualRequestId = java.util.UUID.randomUUID().toString()
                if (location != null) runCatching { PickupTripService.start(context, a) }.onFailure {
                    scope.launch { snackbar.showSnackbar("픽업은 저장됐어요. 상세 화면에서 다시 출발해 주세요.") }
                }
                scope.launch { snackbar.showSnackbar(if (BuildConfig.DEMO_MODE) "약속을 이 기기에 저장했어요. 운전자 화면에서 시연할 수 있어요."
                    else if (location != null) "내비 경로가 확인되면 도착 시간과 초대 문자를 준비해요." else "목적지를 확인한 뒤 출발해 주세요.") }
                null
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                e.message?.takeIf { it.contains("주세요") } ?: "초대 생성 결과를 확인하지 못했어요. 예정된 약속에서 생성 여부를 확인한 후 다시 시도해 주세요."
            } finally { saving = false }
        }

        LaunchedEffect(foreground) {
            if (foreground) while (true) { refresh(); delay(2500) }
        }
        BackHandler(enabled = screen != "home") { if (!saving) screen = "home" }

        Scaffold(modifier = Modifier.glassBackdrop(), containerColor = Color.Transparent, snackbarHost = { SnackbarHost(snackbar) }) { insets ->
            Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 32.dp, vertical = 20.dp)) {
                AppHeader(enabled = !saving, onHome = { screen = "home" }, onDemo = {
                    scope.launch {
                        saving = true
                        try {
                            val demo = withContext(Dispatchers.IO) { repository.demoAppointment() }
                            appointments = withContext(Dispatchers.IO) { repository.list() }
                            localRevision++; selectedId = demo.id; screen = "detail"; loaded = true; error = ""
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            snackbar.showSnackbar(e.message ?: "시연 약속을 준비하지 못했어요.")
                        } finally { saving = false }
                    }
                })
                Spacer(Modifier.height(if (screen == "home") 32.dp else 20.dp))
                if (error.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFFFFF2DD)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (loaded) "$error 마지막으로 확인한 약속을 표시합니다." else error, color = Amber, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { scope.launch { refresh() } }) { Text("다시 연결", color = Amber) }
                    }
                    Spacer(Modifier.height(14.dp))
                }
                Box(Modifier.weight(1f)) {
                    when (screen) {
                        "home" -> HomeScreen(active, loaded, error, onCreate = { voiceDraft = VoiceDraft(); screen = "create" }, onUpcoming = { screen = "upcoming" }) {
                            selectedId = it.id; screen = "detail"
                        }
                        "upcoming" -> UpcomingScreen(appointments, onCreate = { voiceDraft = VoiceDraft(); screen = "create" }) {
                            selectedId = it.id; screen = "detail"
                        }
                        "create" -> CreateAppointment(saving, foreground, voiceDraft, onBack = { screen = "home" }) { name, contact, place, point, time, note, location ->
                            scope.launch { createAppointment(name, contact, place, point, time, note, location)?.let { snackbar.showSnackbar(it) } }
                        }
                        "voice" -> VoiceCreateScreen(voiceDraft, foreground, saving,
                            onDraftChange = { voiceDraft = it }, onBack = { screen = "home" },
                            onManual = { voiceDraft = it; screen = "create" },
                            onSubmit = { d ->
                                val time: Instant? = null
                                if (d.name.isBlank() || d.place.isBlank()) "만날 내용을 모두 확인해 주세요."
                                else createAppointment(d.name, d.contact, d.place, d.point, time, d.note)
                            })
                        "gleo" -> gleoSession?.let { session ->
                            GleoDraftScreen(session, onHome = { screen = "home" }, onInvitation = { id ->
                                selectedId = id; screen = "detail"; scope.launch { refresh() }
                            })
                        }
                        "detail" -> if (selected != null) AppointmentDetail(selected, saving, foreground, onHome = { screen = "home" },
                            onSaveLocation = { location ->
                                if (saving) "저장 중이에요. 잠시 기다려 주세요."
                                else {
                                    saving = true
                                    try {
                                        val updated = withContext(Dispatchers.IO) { repository.setPickupLocation(selected, location) }
                                        localRevision++
                                        appointments = appointments.map { if (it.id == updated.id) updated else it }
                                        scope.launch { snackbar.showSnackbar(if (selected.status == "CONFIRMED" && updated.status == "PENDING")
                                            "승차 위치를 저장했어요. 탑승자가 새 위치를 다시 수락해야 해요." else "선택한 승차 위치를 약속에 저장했어요.") }
                                        null
                                    } catch (e: Exception) {
                                        if (e is kotlinx.coroutines.CancellationException) throw e
                                        e.message?.takeIf { it.contains("주세요") || it.contains("약속") } ?: "위치를 저장하지 못했어요. 연결을 확인해 주세요."
                                    } finally { saving = false }
                                }
                            },
                            onShare = { if (BuildConfig.DEMO_MODE) PassengerDemoStore.accept(context, selected)
                                else context.startActivity(Intent(context, ContactInviteActivity::class.java).putExtra("appointmentId", selected.id).putExtra("recipientName", selected.passengerName)) },
                            onCopy = {
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                    .setPrimaryClip(ClipData.newPlainText("$appName 초대", selected.inviteUrl))
                                scope.launch { snackbar.showSnackbar("초대 링크를 복사했어요. 탑승자에게 전달해 주세요.") }
                            }, onCancel = { cancelDialog = true },
                            automaticSearchKeyword = navigationHandoff?.takeIf {
                                it.pending && it.appointmentId == selected.id && it.expiresAt.isAfter(Instant.now())
                            }?.keyword,
                            previousSearch = navigationHandoff?.takeIf { it.appointmentId == selected.id }?.search,
                            onAutomaticSearchHandled = { search -> navigationHandoff?.let {
                                if (search == null) GleoRuntime.consumeNavigation(context, it.sessionId)
                                else GleoRuntime.beginNavigationSearch(context, it.sessionId, search)
                            } })
                        else Text("약속을 불러오고 있어요.", color = Muted)
                    }
                }
            }
            if (cancelDialog && selected != null) AlertDialog(
                onDismissRequest = { if (!saving) cancelDialog = false },
                title = { Text("픽업 약속을 취소할까요?") },
                text = { Text(if (BuildConfig.DEMO_MODE) "이 기기에 저장된 픽업 약속을 취소합니다." else "탑승자의 링크에도 취소 상태가 표시됩니다.") },
                confirmButton = { TextButton(enabled = !saving, onClick = {
                    scope.launch {
                        saving = true
                        try {
                            val a = withContext(Dispatchers.IO) { repository.cancel(selected.id) }
                            localRevision++
                            appointments = appointments.map { if (it.id == a.id) a else it }
                            cancelDialog = false
                            snackbar.showSnackbar("약속을 취소했어요.")
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            snackbar.showSnackbar(e.message ?: "취소하지 못했어요.")
                        }
                        finally { saving = false }
                    }
                }) { Text("약속 취소") } },
                dismissButton = { TextButton(enabled = !saving, onClick = { cancelDialog = false }) { Text("돌아가기") } }
            )
        }
    }
}

@Composable private fun AppHeader(enabled: Boolean, onHome: () -> Unit, onDemo: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.height(48.dp).widthIn(min = 48.dp).clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClickLabel = "홈으로", role = Role.Button, onClick = onHome),
            verticalAlignment = Alignment.CenterVertically) {
            MajungLogo()
        }
        if (BuildConfig.DEMO_MODE) {
            Spacer(Modifier.weight(1f))
            Text("운전자 화면 시연", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(end = 16.dp))
            TextButton(enabled = enabled, onClick = onDemo) { Text("시연 약속 열기", color = Forest, fontSize = 15.sp) }
        }
    }
}

@Composable internal fun MajungLogo() {
    Image(painter = painterResource(R.drawable.ic_majung_header), contentDescription = null, modifier = Modifier.size(40.dp))
}

@Composable internal fun LineIcon(kind: String, color: Color = Ink, sizeDp: Int = 22) {
    Canvas(Modifier.size(sizeDp.dp)) {
        val w = size.width; val h = size.height; val stroke = Stroke(width = w * .065f, cap = StrokeCap.Round)
        when (kind) {
            "pin" -> {
                val p = Path().apply {
                    moveTo(w * .5f, h * .91f)
                    cubicTo(w * .37f, h * .73f, w * .15f, h * .5f, w * .15f, h * .36f)
                    cubicTo(w * .15f, h * -.02f, w * .85f, h * -.02f, w * .85f, h * .36f)
                    cubicTo(w * .85f, h * .5f, w * .63f, h * .73f, w * .5f, h * .91f)
                    close()
                }
                drawPath(p, color, style = stroke)
                drawCircle(color, w * .105f, Offset(w * .5f, h * .34f), style = stroke)
            }
            "person" -> {
                drawCircle(color, w * .16f, Offset(w * .5f, h * .28f), style = stroke)
                drawArc(color, 180f, 180f, false, topLeft = Offset(w * .19f, h * .57f), size = androidx.compose.ui.geometry.Size(w * .62f, h * .5f), style = stroke)
            }
            "check" -> drawPath(Path().apply { moveTo(w * .2f, h * .52f); lineTo(w * .41f, h * .73f); lineTo(w * .8f, h * .27f) }, color, style = stroke)
            "plus" -> {
                drawLine(color, Offset(w * .5f, h * .2f), Offset(w * .5f, h * .8f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * .2f, h * .5f), Offset(w * .8f, h * .5f), stroke.width, StrokeCap.Round)
            }
            "mic" -> {
                drawRoundRect(color, topLeft = Offset(w * .35f, h * .1f), size = androidx.compose.ui.geometry.Size(w * .3f, h * .5f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .15f), style = stroke)
                drawArc(color, 0f, 180f, false, topLeft = Offset(w * .2f, h * .25f), size = androidx.compose.ui.geometry.Size(w * .6f, h * .52f), style = stroke)
                drawLine(color, Offset(w * .5f, h * .77f), Offset(w * .5f, h * .94f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * .35f, h * .94f), Offset(w * .65f, h * .94f), stroke.width, StrokeCap.Round)
            }
            "pause" -> {
                drawLine(color, Offset(w * .35f, h * .2f), Offset(w * .35f, h * .8f), stroke.width * 1.8f, StrokeCap.Round)
                drawLine(color, Offset(w * .65f, h * .2f), Offset(w * .65f, h * .8f), stroke.width * 1.8f, StrokeCap.Round)
            }
            "chevron" -> drawPath(Path().apply { moveTo(w * .37f, h * .22f); lineTo(w * .64f, h * .5f); lineTo(w * .37f, h * .78f) }, color, style = stroke)
            else -> {
                drawCircle(color, w * .37f, Offset(w * .5f, h * .5f), style = stroke)
                drawPath(Path().apply { moveTo(w * .5f, h * .25f); lineTo(w * .5f, h * .5f); lineTo(w * .67f, h * .6f) }, color, style = stroke)
            }
        }
    }
}

@Composable private fun Status(a: Appointment) {
    if (a.status == "PENDING") return
    val foreground = when(a.status) { "CONFIRMED" -> Forest; "PENDING" -> Amber; else -> Muted }
    val background = when(a.status) { "CONFIRMED" -> Color(0xFFEDF6E5); "PENDING" -> Color(0xFFFFF4E2); else -> Page }
    Row(Modifier.glassControl(background).padding(horizontal = 13.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(5.dp).background(foreground, CircleShape))
        Text(a.label, color = foreground, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable private fun HomeScreen(active: List<Appointment>, loaded: Boolean, error: String, onCreate: () -> Unit, onUpcoming: () -> Unit, onSelect: (Appointment) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(30.dp)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            val next = active.firstOrNull()
            Column(Modifier.weight(1.45f).heightIn(min = 306.dp).fillMaxHeight().liquidGlass(ribbon = true)
                .clickable(enabled = next != null, role = Role.Button, onClickLabel = "픽업 약속 보기") { next?.let(onSelect) }.padding(28.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(next?.let { "${it.passengerName}님을 데리러 가요" } ?: "다음 픽업",
                        fontSize = if (next != null) 26.sp else 15.sp, color = if (next != null) Ink else Muted,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    next?.let { Status(it) }
                }
                if (next != null) {
                    Text(pickupTimeLabel(next), fontSize = 44.sp, fontWeight = FontWeight.Normal, letterSpacing = (-1.5).sp, modifier = Modifier.padding(top = 20.dp))
                    Text(if (next.pickupMode == "NOW") "내비 도착 예상 시간" else next.meetingAt?.let { DateTimeFormatter.ofPattern("M월 d일 E요일", Locale.KOREAN).withZone(Seoul).format(it) }.orEmpty(), color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.weight(1f))
                    HorizontalDivider(color = Line.copy(alpha = .68f), modifier = Modifier.padding(top = 20.dp, bottom = 22.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(43.dp), contentAlignment = Alignment.Center) { LineIcon("pin", Forest) }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(next.place, fontSize = 21.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (next.meetingPoint.isNotBlank()) Text(next.meetingPoint, fontSize = 15.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                        }
                        Row(Modifier.height(46.dp).padding(horizontal = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("약속 보기", color = Ink, fontSize = 13.sp)
                            Spacer(Modifier.width(8.dp)); LineIcon("chevron", Ink, 16)
                        }
                    }
                } else {
                    Text(if (!loaded && error.isEmpty()) "약속을 불러오는 중" else "아직 예정된\n픽업이 없어요", fontSize = 32.sp, lineHeight = 43.sp, fontWeight = FontWeight.Normal, modifier = Modifier.padding(top = 30.dp))
                    Spacer(Modifier.weight(1f))
                    Text(if (BuildConfig.DEMO_MODE) "시연 약속을 열거나 새로운 픽업을 만들어 보세요." else "새 약속을 만들고 탑승자를 초대해 보세요.", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 24.dp))
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().liquidGlass(lime = true, ribbon = true)
                .clickable(role = Role.Button, onClickLabel = "새 픽업 만들기", onClick = onCreate).padding(28.dp)) {
                Text("글레오를 불러\n픽업 약속을 만들어보세요", fontSize = 26.sp, lineHeight = 34.sp, color = Forest, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth().padding(top = 16.dp).height(50.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                    Box(Modifier.size(48.dp).glassControl(Color(0xFFDFFFBA)), contentAlignment = Alignment.Center) {
                        LineIcon("chevron", Forest, 24)
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().liquidGlass()
            .clickable(role = Role.Button, onClickLabel = "예정된 약속 모두 보기", onClick = onUpcoming)
            .padding(horizontal = 26.dp, vertical = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("예정된 약속", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text("  ${active.size}", color = Muted, fontSize = 16.sp)
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) { Text("전체 보기", color = Muted, fontSize = 13.sp); Spacer(Modifier.width(5.dp)); LineIcon("chevron", Muted, 15) }
            }
            if (active.isEmpty()) {
                Text(if (BuildConfig.DEMO_MODE) "저장한 약속을 열어 운전자 화면을 확인할 수 있어요." else "탑승자가 초대를 수락하면 약속이 확정됩니다.", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 24.dp))
            } else active.take(2).forEachIndexed { i, a ->
                if (i > 0) HorizontalDivider(color = Line)
                AppointmentCard(a) { onSelect(a) }
            }
        }
    }
}

@Composable private fun UpcomingScreen(appointments: List<Appointment>, onCreate: () -> Unit, onSelect: (Appointment) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            Text("예정된 약속", fontSize = 30.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            GlassButton(onClick = onCreate, primary = true, modifier = Modifier.height(48.dp)) {
                LineIcon("plus", Forest, 17); Spacer(Modifier.width(8.dp)); Text("약속 만들기", fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(18.dp))
        val active = appointments.filter { it.active }
        Column(Modifier.fillMaxWidth().liquidGlass().padding(24.dp)) {
            if (active.isEmpty()) EmptyAppointments(onCreate)
            active.forEachIndexed { i, a -> if(i > 0) HorizontalDivider(color = Line); AppointmentCard(a) { onSelect(a) } }
        }
        val ended = appointments.filterNot { it.active }.takeLast(5)
        if (ended.isNotEmpty()) {
            Text("지난 약속", fontSize = 15.sp, color = Muted, modifier = Modifier.padding(top = 28.dp, bottom = 14.dp))
            Column(Modifier.fillMaxWidth().liquidGlass().padding(24.dp)) {
                ended.forEachIndexed { i, a -> if(i > 0) HorizontalDivider(color = Line); AppointmentCard(a) { onSelect(a) } }
            }
        }
    }
}

@Composable private fun AppointmentCard(a: Appointment, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClickLabel = "픽업 약속 보기", onClick = click).padding(vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).glassControl(Color(0xFFD8F1C4)), contentAlignment = Alignment.Center) { LineIcon("person", Forest, 20) }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text("${a.passengerName}님 · ${a.place}", fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${if (a.pickupMode == "NOW") pickupTimeLabel(a) else a.meetingAt?.let { dateLabel.format(it) }.orEmpty()}  ·  ${a.meetingPoint}", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 7.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (a.status != "PENDING") {
            Status(a)
            Spacer(Modifier.width(16.dp))
        }
        LineIcon("chevron", Muted, 18)
    }
}

@Composable private fun EmptyAppointments(onCreate: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 20.dp)) {
        Text("아직 예정된 약속이 없어요", fontSize = 20.sp, fontWeight = FontWeight.Medium)
        Text("탑승자와 목적지를 정하면 바로 출발할 수 있어요.", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
        TextButton(onClick = onCreate, contentPadding = PaddingValues(top = 18.dp)) { Text("첫 약속 만들기", color = Forest) }
    }
}

@Composable
private fun CreateAppointment(saving: Boolean, foreground: Boolean, initialDraft: VoiceDraft, onBack: () -> Unit, onCreate: (String, String, String, String, Instant?, String, PickupLocation?) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initialDraft.name) }
    var place by rememberSaveable { mutableStateOf(initialDraft.place) }
    var locationJson by rememberSaveable { mutableStateOf("") }
    var choosingLocation by rememberSaveable { mutableStateOf(false) }
    val location = remember(locationJson) { runCatching { PickupLocation.parse(org.json.JSONObject(locationJson)) }.getOrNull() }
    val context = LocalContext.current
    val navigation = remember { PickupNavigationPool.get(context) }
    DisposableEffect(navigation, foreground, choosingLocation) {
        val acquired = foreground && choosingLocation
        if (acquired) PickupNavigationPool.acquire(navigation)
        onDispose { if (acquired) PickupNavigationPool.release(navigation) }
    }
    Column(Modifier.fillMaxSize().liquidGlass().verticalScroll(rememberScrollState()).padding(28.dp)) {
        HomeNavigationButton(onClick = onBack, enabled = !saving)
        Text("지금 데리러 가기", fontSize = 30.sp, fontWeight = FontWeight.Medium)
        Text("글레오를 불러 음성으로 입력할 수도 있어요", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
        Spacer(Modifier.height(30.dp))
        Entry("탑승자", name, { name = it.take(40) }, "연락처에 저장된 이름 · 예: 지우", Modifier.fillMaxWidth(), enabled = !saving)
        Spacer(Modifier.height(28.dp))
        PickupFieldLabel("만날 장소")
        Row(Modifier.fillMaxWidth().heightIn(min = 108.dp).glassControl(Mint.copy(alpha = .55f), 18.dp)
            .clickable(enabled = foreground && !saving, role = Role.Button,
                onClickLabel = if (location == null) "내비에서 만날 장소 검색" else "만날 장소 변경",
                onClick = { choosingLocation = true }).padding(horizontal = 22.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.weight(1f)) {
                Text(location?.name ?: "내비에서 만날 장소 검색", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                val description = location?.address?.takeIf { it.isNotBlank() }
                    ?: if (location != null) "차량 내비에서 선택한 장소" else if (place.isNotBlank()) "검색할 장소 · $place" else "장소명이나 주소로 검색해 선택해 주세요."
                Text(description, color = Forest.copy(alpha = .75f), fontSize = 16.sp, lineHeight = 23.sp, modifier = Modifier.padding(top = 8.dp))
            }
            Text(if (location == null) "검색" else "변경", color = Forest, fontSize = 17.sp,
                fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 8.dp))
        }
        Spacer(Modifier.height(30.dp))
        Column(Modifier.fillMaxWidth()) {
            Text("만나는 시간은 내비가 계산해요", color = Forest, fontSize = 19.sp, fontWeight = FontWeight.Medium)
            Text(if (BuildConfig.DEMO_MODE) "내비 경로가 확인되면 운전자 화면에 도착 예상 시간을 표시해요. 약속은 이 기기에 저장합니다."
                else "출발하면 도착 예상 시간이 초대 링크에 반영돼요. 연락처에서 받는 사람을 찾아 초대 문자를 준비합니다.", color = Forest.copy(alpha = .75f), fontSize = 14.sp, lineHeight = 23.sp, modifier = Modifier.padding(top = 10.dp))
        }
        if (location != null) GlassButton(primary = true, enabled = foreground && !saving && name.isNotBlank(), onClick = {
            val selected = location ?: return@GlassButton
            onCreate(name.trim(), "", selected.name, "", null, "", selected)
        }, modifier = Modifier.fillMaxWidth().padding(top = 26.dp).height(56.dp)) {
            Text(if (saving) "픽업 준비 중…" else if (BuildConfig.DEMO_MODE) "픽업 저장하고 길 안내" else "출발하고 초대 준비", fontSize = 17.sp)
        }
    }
    if (choosingLocation) PickupLocationPicker(navigation, foreground, place, location, saving,
        onDismiss = { choosingLocation = false }, onSelect = { selected ->
            place = selected.name
            locationJson = selected.toJson().toString()
            null
        })
}

@Composable private fun Entry(label: String, value: String, change: (String) -> Unit, hint: String, modifier: Modifier, type: KeyboardType = KeyboardType.Text, enabled: Boolean = true) {
    Column(modifier) {
        PickupFieldLabel(label)
        OutlinedTextField(value = value, onValueChange = change, enabled = enabled, singleLine = true,
            modifier = Modifier.fillMaxWidth().heightIn(min = 82.dp).glassControl(Mint.copy(alpha = .55f), 18.dp), shape = RoundedCornerShape(18.dp),
            textStyle = LocalTextStyle.current.copy(fontSize = 22.sp, lineHeight = 30.sp),
            placeholder = { Text(hint, color = Forest.copy(alpha = .7f), fontSize = 18.sp) },
            keyboardOptions = KeyboardOptions(keyboardType = type),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Forest, unfocusedBorderColor = Mint.copy(alpha = .85f),
                disabledBorderColor = Mint.copy(alpha = .35f), focusedTextColor = Ink, unfocusedTextColor = Ink,
                cursorColor = Forest, focusedContainerColor = Mint.copy(alpha = .12f),
                unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent))
    }
}

@Composable private fun PickupFieldLabel(label: String) {
    Text(label, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = 12.dp))
}

@Composable private fun HomeNavigationButton(onClick: () -> Unit, enabled: Boolean) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 56.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = Forest)) {
        Text("← 홈으로", fontSize = 22.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable private fun AppointmentDetail(a: Appointment, saving: Boolean, foreground: Boolean, onHome: () -> Unit, onSaveLocation: suspend (PickupLocation) -> String?, onShare: () -> Unit, onCopy: () -> Unit, onCancel: () -> Unit,
    automaticSearchKeyword: String? = null, previousSearch: PickupPlaceSearch? = null, onAutomaticSearchHandled: (PickupPlaceSearch?) -> Unit = {}) {
    val passengerDemo = rememberPassengerDemo(a)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 16.dp)) {
        HomeNavigationButton(onClick = onHome, enabled = !saving)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 32.dp)) {
            Text("픽업 약속", fontSize = 30.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (passengerDemo != null) Text("수락 완료 · 시연", color = Forest, fontSize = 13.sp,
                modifier = Modifier.clip(CircleShape).background(Mint.copy(alpha = .45f)).padding(horizontal = 15.dp, vertical = 8.dp))
            else Status(a)
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1.35f).fillMaxHeight().liquidGlass(ribbon = true).padding(36.dp)) {
                Text(if (a.pickupMode == "NOW") "${a.passengerName}님에게 도착할 때까지" else "${a.passengerName}님과 만나는 시간", color = Muted, fontSize = 14.sp)
                Text(pickupTimeLabel(a), fontSize = 42.sp, fontWeight = FontWeight.Normal, modifier = Modifier.padding(top = 14.dp))
                if (a.pickupMode != "NOW") a.meetingAt?.let {
                    Text(DateTimeFormatter.ofPattern("M월 d일 E요일", Locale.KOREAN).withZone(Seoul).format(it), color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                }
                HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 26.dp))
                if (a.active) {
                    PickupNavigationPanel(a, foreground, saving, onSaveLocation, automaticSearchKeyword, previousSearch, onAutomaticSearchHandled)
                } else {
                    Column {
                        Text("약속 위치", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
                        Text(a.place, fontSize = 25.sp, fontWeight = FontWeight.Medium)
                        if (a.meetingPoint.isNotBlank()) Text(a.meetingPoint, color = Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 7.dp))
                    }
                }
                if (a.note.isNotBlank()) {
                    Spacer(Modifier.height(26.dp))
                    Column(Modifier.fillMaxWidth().glassControl(radius = 18.dp).padding(16.dp)) {
                        Text("전달 메모", color = Muted, fontSize = 12.sp)
                        Text(a.note, color = Ink, fontSize = 14.sp, lineHeight = 23.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().liquidGlass(lime = a.active, ribbon = a.active).padding(if (passengerDemo != null) 26.dp else 36.dp)) {
                if (passengerDemo != null) PassengerLocationPanel(a, passengerDemo)
                else {
                val confirmed = a.status == "CONFIRMED"
                val invitationLabel = when {
                    !a.active -> a.label
                    BuildConfig.DEMO_MODE -> "수락 대기 · 시연"
                    confirmed -> "약속 확정"
                    !a.canShare -> "초대 준비 중"
                    else -> "수락 대기"
                }
                val invitationDescription = when {
                    BuildConfig.DEMO_MODE && a.active -> "초대 수락 시연을 누르면\n가상 탑승자 위치를 확인할 수 있어요."
                    confirmed -> "탑승자가 초대를 수락했어요.\n만남 정보를 함께 확인할 수 있어요."
                    a.status == "DECLINED" -> "탑승자가 초대를 거절했어요."
                    !a.active -> "이 픽업 약속은 종료됐어요."
                    !a.canShare -> "출발하면 도착 예상 시간과 함께\n초대를 준비해요."
                    else -> "초대 링크에서 탑승자가 수락하면\n이곳에 바로 반영돼요."
                }
                val statusColor = if (!a.active) Muted else if (a.canShare && !confirmed) Amber else Forest
                Text("탑승자", color = Forest.copy(alpha = .72f), fontSize = 14.sp)
                Text("${a.passengerName}님", color = Ink, fontSize = 32.sp, lineHeight = 42.sp,
                    fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 10.dp))
                HorizontalDivider(color = Forest.copy(alpha = .12f), modifier = Modifier.padding(vertical = 24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(statusColor, CircleShape))
                    Text(invitationLabel, color = statusColor, fontSize = 22.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 10.dp))
                }
                Text(invitationDescription, color = Forest.copy(alpha = .72f), fontSize = 14.sp, lineHeight = 23.sp,
                    modifier = Modifier.padding(top = 12.dp))
                if (a.active) {
                    Spacer(Modifier.height(28.dp))
                    Spacer(Modifier.weight(1f))
                    GlassButton(enabled = a.pickupLocation != null && !saving, onClick = onShare, primary = true,
                        modifier = Modifier.fillMaxWidth().height(54.dp)) {
                        Text(if (BuildConfig.DEMO_MODE) "초대 수락 시연" else "초대 문자 준비", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        if (!BuildConfig.DEMO_MODE) TextButton(enabled = a.canShare && !saving, onClick = onCopy,
                            colors = ButtonDefaults.textButtonColors(contentColor = Forest, disabledContentColor = Muted.copy(alpha = .65f))) {
                            Text("초대 링크 복사", fontSize = 14.sp)
                        }
                        TextButton(enabled = !saving, onClick = onCancel,
                            colors = ButtonDefaults.textButtonColors(contentColor = Forest.copy(alpha = .65f))) {
                            Text("약속 취소", fontSize = 14.sp)
                        }
                    }
                }
                }
            }
        }
    }
}
