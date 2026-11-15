package kr.ddakta.pickup.gleo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.ddakta.pickup.*
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun GleoDraftScreen(session: GleoSession, onHome: () -> Unit, onInvitation: (String) -> Unit) {
    val created = session.phase == GleoPhase.CREATED
    val title = when(session.phase) {
        GleoPhase.DRAFT -> "약속 내용을 확인하고 있어요"
        GleoPhase.REVIEW -> if (BuildConfig.DEMO_MODE) "이 내용으로 픽업을 저장할까요?" else "이 내용으로 초대를 만들까요?"
        GleoPhase.SUBMITTING -> if (BuildConfig.DEMO_MODE) "픽업을 저장하고 있어요" else "초대 생성 결과를 확인하고 있어요"
        GleoPhase.CREATED -> "픽업이 준비됐어요"
        GleoPhase.CANCELLED -> "초안 작성을 취소했어요"
        GleoPhase.EXPIRED -> "약속 초안이 만료됐어요"
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TextButton(onClick = onHome, contentPadding = PaddingValues(0.dp)) { Text("← 홈으로", color = Muted) }
        Text("글레오 약속 작성", fontSize = 30.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 22.dp))
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.weight(1.35f).fillMaxHeight().liquidGlass().padding(28.dp)) {
                Text("받은 약속 내용", color = Muted, fontSize = 13.sp)
                Field("탑승자", session.draft.name)
                Field("만날 장소", session.draft.place)

            }
            Column(Modifier.weight(1f).fillMaxHeight().liquidGlass(lime = true, ribbon = true).padding(28.dp)) {
                Box(Modifier.size(40.dp).glassControl(Mint, 14.dp), contentAlignment = Alignment.Center) { LineIcon(if(created) "check" else "mic", Forest, 21) }
                Text(title, fontSize = 26.sp, lineHeight = 37.sp, color = Forest, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 24.dp))
                val explanation = when(session.phase) {
                    GleoPhase.DRAFT -> "글레오가 빠진 내용을 물어보면 답해 주세요."
                    GleoPhase.REVIEW -> if (BuildConfig.DEMO_MODE) "탑승자와 목적지를 확인하고 글레오에게 답해 주세요. 확인한 약속을 이 기기에 저장합니다."
                        else "탑승자와 목적지를 확인하고 글레오에게 답해 주세요. 확인 후에만 초대를 생성합니다."
                    GleoPhase.SUBMITTING -> "결과를 확인하지 못했다면 앱의 약속 목록을 확인하거나 같은 내용으로 다시 요청해 주세요."
                    GleoPhase.CREATED -> "말씀하신 장소로 내비 검색을 연결해요. 검색 결과에서 목적지를 정한 뒤 마중에서 장소를 확인하고 출발해 주세요."
                    else -> "새 약속은 홈에서 시작할 수 있어요."
                }
                Text(explanation, color = Forest.copy(alpha = .75f), fontSize = 14.sp, lineHeight = 24.sp, modifier = Modifier.padding(top = 15.dp))
                if (created && session.appointmentId != null) GlassButton(onClick = { onInvitation(session.appointmentId) }, primary = true, modifier = Modifier.padding(top = 28.dp).height(52.dp)) { Text("목적지 확인하고 출발", fontSize = 15.sp) }
            }
        }
    }
}

@Composable private fun Field(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(top = 23.dp)) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value.ifBlank { "확인 중" }, color = if(value.isBlank()) Muted else Ink, fontSize = 20.sp, modifier = Modifier.padding(top = 8.dp, bottom = 18.dp))
        HorizontalDivider(color = Line)
    }
}
