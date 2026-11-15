package kr.ddakta.pickup

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun rememberPassengerDemo(a: Appointment): PassengerDemoStore.State? {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val prefs = PassengerDemoStore.preferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == PassengerDemoStore.StateKey) revision++
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return remember(a.id, a.passengerName, a.pickupLocation, a.active, revision) { PassengerDemoStore.read(context, a) }
}

/** Replaces the invitation card in the same appointment after the local acceptance demonstration. */
@Composable
internal fun PassengerLocationPanel(a: Appointment, demo: PassengerDemoStore.State) {
    val context = LocalContext.current
    val scene = remember(a.pickupLocation) { PassengerDemoScene.around(requireNotNull(a.pickupLocation)) }
    val distance = scene.distanceMeters(demo.nearby)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("${a.passengerName}님 위치", color = Ink, fontSize = 23.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("공유 중 · 시연", color = Forest, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
    }
    Spacer(Modifier.height(14.dp))
    PassengerDemoMap(scene, a.passengerName, accepted = true, nearby = demo.nearby, height = 200.dp)
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("약 ${distance}m", color = Ink, fontSize = 28.sp, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(if (demo.nearby) "약속 장소 부근" else "약속 장소까지", color = Forest, fontSize = 13.sp)
            Text("가상 위치 · 직선거리", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
    Text(if (demo.nearby) "글레오 안내 예시 · ${a.passengerName}님의 공유 위치가 약속 장소 부근에 있어요."
        else "글레오 안내 예시 · ${a.passengerName}님의 공유 위치는 약속 장소에서 약 ${distance}m 떨어져 있어요.",
        color = Forest, fontSize = 13.sp, lineHeight = 21.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 12.dp))
    TextButton(onClick = { PassengerDemoStore.clear(context, a) }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(38.dp)) {
        Text("시연 종료 · 초대 화면으로", color = Muted, fontSize = 12.sp)
    }
}
