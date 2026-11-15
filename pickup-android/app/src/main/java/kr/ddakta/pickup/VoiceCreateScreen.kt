package kr.ddakta.pickup

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

val VoiceDraftSaver = listSaver<VoiceDraft, String>(
    save = { listOf(it.name, it.place, it.point, it.date?.toString().orEmpty(), it.time?.toString().orEmpty(), it.contact, it.note, it.ambiguousHour?.toString().orEmpty(), it.ambiguousMinute.toString()) },
    restore = { VoiceDraft(it[0], it[1], it[2], it[3].takeIf(String::isNotBlank)?.let(LocalDate::parse), it[4].takeIf(String::isNotBlank)?.let(LocalTime::parse), it[5], it[6], it[7].toIntOrNull(), it.getOrNull(8)?.toIntOrNull() ?: 0) }
)

@Composable
fun VoiceCreateScreen(
    initialDraft: VoiceDraft, foreground: Boolean, saving: Boolean,
    onDraftChange: (VoiceDraft) -> Unit, onBack: () -> Unit, onManual: (VoiceDraft) -> Unit,
    onSubmit: suspend (VoiceDraft) -> String?
) {
    val context = LocalContext.current
    var conversation by remember { mutableStateOf(VoiceConversation.begin(initialDraft)) }
    var audio by remember { mutableStateOf<VoiceAudio?>(null) }
    var audioActive by remember { mutableStateOf(false) }
    var began by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("대화 준비") }
    var issue by remember { mutableStateOf("") }
    var textMode by rememberSaveable { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }
    var restart by remember { mutableIntStateOf(0) }
    var submittedTurn by remember { mutableIntStateOf(-1) }

    fun receive(text: String) {
        if (saving || conversation.phase == VoicePhase.SUBMIT || conversation.phase == VoicePhase.CANCELLED) return
        audio?.stop()
        issue = ""
        conversation = conversation.hear(text)
        onDraftChange(conversation.draft)
    }
    val receiveCurrent by rememberUpdatedState<(String) -> Unit> { receive(it) }
    DisposableEffect(context) {
        val engine = VoiceAudio(context,
            onStatus = { status = it },
            onHeard = { receiveCurrent(it) },
            onError = { audioActive = false; status = "대화 일시 정지"; issue = it }
        )
        audio = engine
        onDispose { engine.close() }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { issue = ""; audioActive = true; restart++ }
        else { audioActive = false; issue = "마이크를 허용하지 않아 대화를 멈췄어요. 직접 입력하거나 다시 시작할 수 있어요." }
    }
    fun resume() {
        if (!foreground || saving || conversation.phase == VoicePhase.SUBMIT || conversation.phase == VoicePhase.CANCELLED) return
        if (audio?.available != true) { issue = "이 기기에 연결된 음성 인식 서비스를 사용할 수 없어요. 대화 내용을 직접 입력하거나 입력 화면으로 전환해 주세요."; return }
        textMode = false; issue = ""
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            audioActive = true; restart++
        } else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(audio, foreground) {
        if (!foreground) { audioActive = false; audio?.stop(); status = "대화 일시 정지" }
        else if (audio != null && !began) { began = true; resume() }
    }
    LaunchedEffect(audioActive, conversation.turn, audio, foreground, restart) {
        if (audioActive && foreground && !saving && conversation.phase in listOf(VoicePhase.COLLECTING, VoicePhase.REVIEW)) audio?.ask(conversation.prompt)
    }
    LaunchedEffect(conversation.phase, conversation.turn) {
        when (conversation.phase) {
            VoicePhase.SUBMIT -> {
                audioActive = false; audio?.stop()
                if (submittedTurn != conversation.turn) {
                    submittedTurn = conversation.turn
                    try {
                        val error = onSubmit(conversation.draft)
                        if (error != null) {
                            conversation = conversation.copy(phase = VoicePhase.REVIEW, prompt = "초대 생성 결과를 확인해 주세요. 다시 시도하려면 내용을 확인한 후 생성해 주세요.", turn = conversation.turn + 1)
                            issue = error
                        }
                    } catch (e: CancellationException) { throw e }
                }
            }
            VoicePhase.CANCELLED -> { audioActive = false; audio?.stop(); status = "대화 종료" }
            else -> Unit
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
            TextButton(onClick = onBack, enabled = !saving, contentPadding = PaddingValues(0.dp)) { Text("← 홈으로", color = Muted) }
            Spacer(Modifier.weight(1f))
            Text("음성으로 약속 만들기", fontSize = 15.sp, color = Muted)
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.weight(1.35f).fillMaxHeight().liquidGlass().padding(28.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp).glassControl(Mint, 14.dp), contentAlignment = Alignment.Center) { LineIcon("mic", Forest, 20) }
                    Text("${stringResource(R.string.app_name)}에게 말해 보세요", fontSize = 16.sp, modifier = Modifier.padding(start = 10.dp))
                }
                Text(conversation.prompt, fontSize = 25.sp, lineHeight = 37.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 25.dp))
                if (conversation.lastHeard.isNotBlank()) {
                    Text("인식한 말씀", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 24.dp))
                    Text(conversation.lastHeard, fontSize = 15.sp, lineHeight = 23.sp, modifier = Modifier.padding(top = 7.dp))
                }
                Spacer(Modifier.height(25.dp))
                if (issue.isNotBlank()) Text(issue, color = Amber, fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.padding(bottom = 16.dp))
                if (conversation.phase == VoicePhase.CANCELLED) {
                    GlassButton(onClick = { conversation = VoiceConversation.begin(); onDraftChange(conversation.draft); resume() }) { Text("새 대화 시작") }
                } else if (saving || conversation.phase == VoicePhase.SUBMIT) {
                    Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Text("픽업 준비 중", color = Muted, modifier = Modifier.padding(start = 12.dp)) }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GlassButton(primary = true, onClick = {
                            if (audioActive) { audioActive = false; audio?.stop(); status = "대화 일시 정지" }
                            else resume()
                        }, modifier = Modifier.height(50.dp)) {
                            LineIcon(if (audioActive) "pause" else "mic", Forest, 18)
                            Text(if (audioActive) "잠시 멈추기" else "대화 이어가기", modifier = Modifier.padding(start = 9.dp), fontSize = 14.sp)
                        }
                        Text(if (audioActive) status else "마이크 꺼짐", fontSize = 12.sp, color = Muted)
                    }
                    if (conversation.phase == VoicePhase.REVIEW) {
                        GlassButton(onClick = { receive("초대 링크 만들어줘") }, primary = true, modifier = Modifier.padding(top = 12.dp).height(48.dp)) { Text("확인하고 픽업 준비") }
                    }
                    TextButton(onClick = { audioActive = false; audio?.stop(); textMode = !textMode }, contentPadding = PaddingValues(top = 14.dp)) { Text(if (textMode) "대화 입력 닫기" else "말하는 대신 입력하기", fontSize = 12.sp, color = Muted) }
                    if (textMode) {
                        OutlinedTextField(value = typed, onValueChange = { typed = it.take(300) }, label = { Text("답변 또는 바꿀 내용") }, placeholder = { Text("예: 지우를 수원역으로 데리러 갈게") }, modifier = Modifier.fillMaxWidth().glassControl(radius = 14.dp), shape = RoundedCornerShape(14.dp), maxLines = 2, colors = glassFieldColors())
                        TextButton(enabled = typed.isNotBlank(), onClick = { val input = typed; typed = ""; receive(input) }) { Text("내용 전달") }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().liquidGlass(lime = true).padding(25.dp)) {
                Text("말씀하신 약속", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text("빠진 내용만 여쭤볼게요.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 7.dp))
                Spacer(Modifier.height(22.dp))
                val d = conversation.draft
                VoiceSummary("탑승자", d.name, saving) { audio?.stop(); conversation = conversation.edit(VoiceField.NAME); onDraftChange(conversation.draft) }
                VoiceSummary("만날 장소", d.place, saving) { audio?.stop(); conversation = conversation.edit(VoiceField.PLACE); onDraftChange(conversation.draft) }
                Spacer(Modifier.height(20.dp))
                Text("목적지를 확인하고 출발하면\n내비 도착 시간이 초대 링크에 반영돼요.", color = Muted, fontSize = 12.sp, lineHeight = 21.sp)
                TextButton(enabled = !saving, onClick = { audio?.stop(); onManual(d) }, contentPadding = PaddingValues(top = 16.dp)) { Text("입력 화면에서 이어서 작성", fontSize = 13.sp) }
            }
        }
    }
}

@Composable private fun VoiceSummary(label: String, value: String, disabled: Boolean, onChange: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(enabled = !disabled, onClick = onChange, contentPadding = PaddingValues(horizontal = 7.dp), modifier = Modifier.height(32.dp)) { Text("수정", fontSize = 11.sp) }
        }
        Text(value.ifBlank { "아직 정하지 않았어요" }, color = if(value.isBlank()) Muted else Ink, fontSize = 18.sp, lineHeight = 25.sp, modifier = Modifier.padding(bottom = 13.dp))
        HorizontalDivider(color = Line)
    }
}
