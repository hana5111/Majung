package kr.ddakta.pickup

import android.app.Activity
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kr.ddakta.pickup.gleo.GleoTransport
import org.json.JSONArray
import org.json.JSONObject
import java.time.format.DateTimeFormatter
import java.util.Locale

const val SHARE_INVITATION_ACTION = "MAJUNG_SHARE_INVITATION"
const val QUALIFIED_SHARE_INVITATION_ACTION = "kr.ddakta.pickup.$SHARE_INVITATION_ACTION"

object InvitationShareVoice {
    fun response(opened: Boolean, lookupRequested: Boolean = false): JSONObject = JSONObject()
        .put("functionName", SHARE_INVITATION_ACTION).put("status", if (opened) "success" else "fail").put("mode", "voice")
        .put("message", if (opened && lookupRequested) "The invitation-sharing screen is looking up the named recipient when contacts permission is allowed. A single unambiguous number will be selected automatically; multiple matches require user selection. Review and final sending are still required; no message has been sent."
            else if (opened) "The invitation-sharing screen was opened. Select a contact and review the SMS before opening the messaging app; no message has been sent."
            else "The invitation-sharing screen could not process the request. No message has been sent.")
        .put("data", JSONArray().put(JSONObject().put("screenOpened", opened).put("messageSent", false)
            .put("contactLookupRequested", lookupRequested).put("requiresContactReview", true)
            .put("requiresFinalSendInMessagingApp", true)).toString())
}

class ContactInviteActivity : ComponentActivity() {
    private var voiceReplied = false
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("voiceReplied", voiceReplied); super.onSaveInstanceState(outState) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voiceReplied = savedInstanceState?.getBoolean("voiceReplied") ?: false
        val fromVoice = intent.action in setOf(SHARE_INVITATION_ACTION, QUALIFIED_SHARE_INVITATION_ACTION)
        val request = runCatching {
            val raw = if (intent.hasExtra("ACTION_PARAM")) requireNotNull(intent.getStringExtra("ACTION_PARAM")) else JSONObject().apply {
                InvitationShareRequest.parameters.forEach { key -> if (intent.hasExtra(key)) put(key, requireNotNull(intent.getStringExtra(key))) }
            }.toString()
            InvitationShareRequest.parse(raw)
        }
        if (fromVoice && !voiceReplied) {
            GleoTransport(this, BuildConfig.GLEO_PACKAGE).send(InvitationShareVoice.response(request.isSuccess, !request.getOrNull()?.recipientName.isNullOrBlank()).toString())
            voiceReplied = true
        }
        setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                val scale = (maxWidth.value / 1100f).coerceIn(1f, 1.55f)
                CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale)) {
                    MaterialTheme(colorScheme = lightColorScheme(primary = Forest, onPrimary = Color.White, background = Page,
                        surface = Panel, onSurface = Ink, onBackground = Ink, outline = Line, surfaceVariant = Soft)) {
                        ShareInvitationScreen(request.getOrNull(), onClose = { finish() })
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareInvitationScreen(request: InvitationShareRequest?, onClose: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { PickupRepository(context, connectTimeoutMs = 2000, readTimeoutMs = 2500) }
    val scope = rememberCoroutineScope()
    var appointments by remember { mutableStateOf<List<Appointment>?>(null) }
    var selectedId by rememberSaveable { mutableStateOf(request?.appointmentId.orEmpty()) }
    var recipientName by rememberSaveable { mutableStateOf("") }
    var recipientNumber by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf(if (request == null) "문자 공유 요청을 확인하지 못했어요. 약속 화면에서 다시 시작해 주세요." else "") }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var autoPickerOpened by rememberSaveable { mutableStateOf(false) }
    var composerOpened by rememberSaveable { mutableStateOf(false) }
    val lookupName = request?.recipientName
    var contactsAllowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) }
    var permissionPrompted by rememberSaveable { mutableStateOf(false) }
    var lookupAttempted by remember { mutableStateOf(false) }
    var lookupRetry by remember { mutableIntStateOf(0) }
    var lookupBusy by remember { mutableStateOf(false) }
    var lookupResult by remember { mutableStateOf<ContactSearchResult?>(null) }
    var manualContact by rememberSaveable { mutableStateOf(false) }
    var lookupNotice by remember { mutableStateOf("") }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        contactsAllowed = granted
        if (granted) { lookupAttempted = false; lookupRetry++ }
        else lookupNotice = "이름으로 찾으려면 연락처 접근을 허용해 주세요. 직접 연락처를 선택할 수도 있어요."
    }
    val selected = appointments?.find { it.id == selectedId }
    val prepared = selected?.let { runCatching { InvitationSms.prepare(it) }.getOrNull() }
    val contactPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri == null) error = "연락처를 확인하지 못했어요. 다시 선택해 주세요."
            else scope.launch {
                busy = true
                try {
                    val recipient = withContext(Dispatchers.IO) { InvitationSmsAndroid.readSelectedContact(context.contentResolver, uri) }
                    recipientName = recipient.name; recipientNumber = recipient.number; composerOpened = false; error = ""
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    error = "선택한 연락처의 전화번호를 가져오지 못했어요. 연락처 공유 설정을 확인하거나 다시 선택해 주세요."
                } finally { busy = false }
            }
        }
    }
    fun chooseContact() {
        if (busy) return
        manualContact = true
        error = ""
        try {
            val picker = InvitationSmsAndroid.contactPicker()
            if (picker.resolveActivity(context.packageManager) == null) error = "이 기기에서는 연락처 선택 화면을 열 수 없어요."
            else contactPicker.launch(picker)
        } catch (_: ActivityNotFoundException) { error = "연락처 선택 화면을 열지 못했어요." }
        catch (_: SecurityException) { error = "연락처 선택을 허용하지 않았어요. 기기의 연락처 공유 설정을 확인해 주세요." }
    }
    LaunchedEffect(lookupName, lookupRetry) {
        if (lookupName.isNullOrBlank() || lookupAttempted || manualContact || recipientNumber.isNotBlank()) return@LaunchedEffect
        lookupAttempted = true
        contactsAllowed = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        if (!contactsAllowed) {
            lookupNotice = "‘${lookupName}’님의 번호를 이름으로 찾으려면 연락처 접근이 필요해요. 이름과 번호는 서버나 글레오로 전송하지 않아요."
            if (!permissionPrompted) { permissionPrompted = true; permissionLauncher.launch(Manifest.permission.READ_CONTACTS) }
            return@LaunchedEffect
        }
        lookupBusy = true; lookupNotice = ""
        try {
            val result = withContext(Dispatchers.IO) { InvitationSmsAndroid.findByName(context.contentResolver, lookupName) }
            if (manualContact) return@LaunchedEffect
            lookupResult = result
            result.unique?.let {
                if (!manualContact) { recipientName = it.recipient.name; recipientNumber = it.recipient.number; composerOpened = false }
            }
            lookupNotice = when {
                result.unique != null -> "‘${lookupName}’에 해당하는 번호 하나를 찾아 자동으로 선택했어요. 받는 사람을 확인해 주세요."
                result.incomplete -> "검색 결과가 많아요. 연락처에서 정확한 사람과 번호를 선택해 주세요."
                result.candidates.isEmpty() -> "‘${lookupName}’에 해당하는 번호를 찾지 못했어요. 연락처 공유 상태를 확인하거나 직접 선택해 주세요."
                else -> "‘${lookupName}’에 해당하는 번호가 여러 개예요. 받는 번호를 확인해 주세요."
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            contactsAllowed = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
            lookupNotice = "연락처를 검색하지 못했어요. 연락처 권한과 휴대폰 공유 설정을 확인하거나 직접 선택해 주세요."
        } finally { lookupBusy = false }
    }
    LaunchedEffect(reload) {
        if (request != null) while (true) {
            try {
                val available = withContext(Dispatchers.IO) { repository.list().filter { it.active } }
                appointments = available
                if (selectedId.isEmpty() && available.size == 1) selectedId = available.single().id
                if (selectedId.isNotEmpty() && available.none { it.id == selectedId }) error = "선택한 픽업이 종료됐어요. 공유할 픽업을 다시 선택해 주세요."
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = "픽업을 불러오지 못했어요. 연결을 확인한 뒤 다시 시도해 주세요."
            }
            delay(3000)
        }
    }
    LaunchedEffect(selected?.id) {
        if (selected != null && prepared != null && !autoPickerOpened && recipientNumber.isEmpty() && lookupName.isNullOrBlank()) {
            autoPickerOpened = true
            chooseContact()
        }
    }
    fun openComposer() {
        val appointment = selected ?: return
        val oldBody = prepared?.body ?: return
        if (busy || recipientNumber.isBlank()) return
        val recipient = runCatching { SmsRecipient.from(recipientName, recipientNumber) }.getOrNull() ?: return
        scope.launch {
            busy = true; error = ""
            try {
                // A cancelled, expired or changed invitation must not be composed from a stale preview.
                val fresh = withContext(Dispatchers.IO) { repository.list().find { it.id == appointment.id } }
                    ?: throw IllegalArgumentException("약속을 찾지 못했어요.")
                val sms = InvitationSms.prepare(fresh)
                if (sms.body != oldBody) {
                    appointments = appointments?.map { if (it.id == fresh.id) fresh else it }
                    error = "약속 내용이 바뀌었어요. 새 내용을 확인한 뒤 다시 눌러 주세요."
                } else {
                    val draft = InvitationSmsAndroid.composer(recipient, sms)
                    if (draft.resolveActivity(context.packageManager) == null) error = "이 기기에서 문자 작성 앱을 찾지 못했어요."
                    else { context.startActivity(draft); composerOpened = true }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = if (e is IllegalArgumentException) e.message.orEmpty() else "문자 작성 화면을 열지 못했어요. 휴대폰 연결과 문자 공유 설정을 확인해 주세요."
            } finally { busy = false }
        }
    }

    Scaffold(modifier = Modifier.glassBackdrop(), containerColor = Color.Transparent) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 32.dp, vertical = 20.dp).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                MajungLogo()
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("돌아가기", color = Muted) }
            }
            Text("초대 문자 준비", fontSize = 30.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 20.dp, bottom = 20.dp))
            if (!lookupName.isNullOrBlank()) {
                Column(Modifier.fillMaxWidth().glassControl(Mint, 18.dp).padding(16.dp)) {
                    Text(if (lookupBusy) "‘${lookupName}’님의 연락처를 찾고 있어요" else lookupNotice.ifBlank { "‘${lookupName}’님의 연락처를 확인해 주세요" }, color = Forest, fontSize = 14.sp, lineHeight = 22.sp)
                    if (!contactsAllowed) Row {
                        TextButton(onClick = { permissionPrompted = true; permissionLauncher.launch(Manifest.permission.READ_CONTACTS) }) { Text("검색 권한 허용", color = Forest) }
                        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) }) { Text("앱 권한 설정", color = Muted) }
                    } else if (lookupResult?.unique == null && !lookupBusy) TextButton(onClick = { manualContact = false; lookupAttempted = false; lookupRetry++ }) { Text("다시 찾기", color = Forest) }
                    if (recipientNumber.isBlank() && lookupResult?.unique == null) lookupResult?.candidates?.forEach { candidate ->
                        TextButton(enabled = !lookupBusy && !lookupResult!!.incomplete, onClick = {
                            recipientName = candidate.recipient.name; recipientNumber = candidate.recipient.number; composerOpened = false
                            manualContact = true; lookupNotice = "선택한 받는 사람과 초대 내용을 확인해 주세요."
                        }) { Text("${candidate.recipient.name} · ${candidate.numberLabel} · 끝자리 ${candidate.recipient.number.takeLast(4)}", color = Forest, fontSize = 15.sp) }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            if (error.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().glassControl(Color(0xFFFFE4B5), 16.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(error, color = Amber, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    if (appointments == null && request != null) TextButton(onClick = { error = ""; reload++ }) { Text("다시 불러오기", color = Amber) }
                }
                Spacer(Modifier.height(16.dp))
            }
            if (selected == null) {
                Column(Modifier.fillMaxWidth().liquidGlass().padding(28.dp)) {
                    Text(if (request == null) "약속 화면에서 다시 시작해 주세요" else if (appointments == null && error.isEmpty()) "약속을 불러오고 있어요" else "공유할 픽업 약속을 선택해 주세요", fontSize = 23.sp, fontWeight = FontWeight.Medium)
                    if (appointments?.isEmpty() == true) Text("진행 중인 약속이 없어요. 약속을 만든 뒤 다시 시작해 주세요.", color = Muted, modifier = Modifier.padding(top = 18.dp))
                    appointments?.forEach { a ->
                        Column(Modifier.fillMaxWidth().clickable {
                            selectedId = a.id; error = ""; composerOpened = false
                        }.padding(vertical = 20.dp)) {
                            Text("${a.passengerName}님 · ${a.place}", fontSize = 20.sp)
                            Text("${pickupTimeLabel(a)} · ${a.meetingPoint}", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 7.dp))
                        }
                        HorizontalDivider(color = Line)
                    }
                }
            } else Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(Modifier.weight(1.1f).fillMaxHeight().liquidGlass().padding(28.dp)) {
                    Text("픽업 약속", color = Muted, fontSize = 13.sp)
                    Text(selected.place, fontSize = 28.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 16.dp))
                    Text(selected.meetingPoint, color = Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
                    Text(pickupTimeLabel(selected), fontSize = 18.sp, modifier = Modifier.padding(top = 18.dp))
                    Text("${selected.passengerName}님에게 보내는 초대", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                    HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 24.dp))
                    Text("받는 사람", color = Muted, fontSize = 13.sp)
                    Text(recipientName.ifBlank { "연락처에서 선택해 주세요" }, fontSize = 21.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp))
                    if (recipientNumber.isNotBlank()) {
                        Text(recipientNumber, color = Forest, fontSize = 18.sp, modifier = Modifier.padding(top = 8.dp))
                        TextButton(enabled = !busy, onClick = { chooseContact() }, contentPadding = PaddingValues(0.dp)) { Text("다른 연락처 선택", fontSize = 13.sp) }
                    } else if (lookupBusy) Text("이름으로 연락처를 찾고 있어요.", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
                }
                Column(Modifier.weight(1f).fillMaxHeight().liquidGlass(lime = true, ribbon = true).padding(28.dp)) {
                    GlassButton(enabled = !busy && selected.active && selected.pickupLocation != null, primary = true,
                        modifier = Modifier.fillMaxWidth().height(54.dp), onClick = {
                            PassengerDemoStore.accept(context, selected)
                            PickupTripRuntime.invitationOpened(selected.id)
                            context.startActivity(Intent(context, MainActivity::class.java)
                                .putExtra(PassengerDemoStore.OpenAppointmentExtra, selected.id)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                            onClose()
                        }) {
                        Text("초대 수락 시연", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    }
                    Text(if (selected.pickupLocation == null) "만날 장소를 선택하면 위치 공유를 시연할 수 있어요."
                        else "문자 발송 없이 ${selected.passengerName}님의 수락과 위치 공유를 체험해요.",
                        color = Forest.copy(alpha = .72f), fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 10.dp))
                    HorizontalDivider(color = Forest.copy(alpha = .12f), modifier = Modifier.padding(vertical = 20.dp))
                    Text("문자 미리보기", color = Forest, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    Text(prepared?.preview ?: "이 약속은 현재 공유할 수 없어요.", color = Ink, fontSize = 14.sp, lineHeight = 23.sp, modifier = Modifier.padding(top = 18.dp))
                    if (prepared?.localOnly == true) Text("개발용 링크라 다른 휴대폰에서는 아직 열리지 않아요.", color = Amber, fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 18.dp))
                    if (composerOpened) Text("문자 앱을 열었어요. 휴대폰 연결 상태와 작성·발송 결과는 문자 앱에서 확인해 주세요.", color = Forest, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 18.dp))
                    Spacer(Modifier.height(24.dp))
                    GlassButton(enabled = !busy && !lookupBusy && prepared != null, modifier = Modifier.fillMaxWidth().height(52.dp), onClick = {
                        if (recipientNumber.isBlank()) chooseContact() else openComposer()
                    }) {
                        Text(if (busy) "내용 확인 중…" else if (recipientNumber.isBlank()) "연락처에서 선택" else if (prepared?.localOnly == true) "테스트 문자 작성" else "문자앱에서 확인", fontSize = 15.sp)
                    }
                    Text("받는 사람과 내용을 확인한 뒤 문자 앱에서 최종 발송해 주세요.", color = Forest.copy(alpha = .72f), fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 12.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
