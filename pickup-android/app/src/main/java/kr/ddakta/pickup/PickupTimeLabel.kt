package kr.ddakta.pickup

import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import java.time.Instant

/** The last response must also expire on screen when the network stops returning new data. */
@Composable
internal fun pickupTimeLabel(a: Appointment): String {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(a.pickupMode) {
        if (a.pickupMode == "NOW") while (true) { now = Instant.now(); delay(1000) }
    }
    return a.timeLabel(now)
}
