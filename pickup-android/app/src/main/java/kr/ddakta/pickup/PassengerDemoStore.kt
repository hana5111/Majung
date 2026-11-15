package kr.ddakta.pickup

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

/** A local presentation state, separate from real invitation acceptance and trip telemetry. */
internal object PassengerDemoStore {
    const val StateKey = "accepted_pickup"
    const val OpenAppointmentExtra = "kr.ddakta.pickup.OPEN_DEMO_APPOINTMENT"
    data class State(val appointmentId: String, val nearby: Boolean)
    fun preferences(context: Context) = context.getSharedPreferences("majung_passenger_demo", Context.MODE_PRIVATE)
    private fun targetKey(a: Appointment): String = MessageDigest.getInstance("SHA-256")
        .digest("${a.passengerName}|${a.pickupLocation?.toJson()}".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun read(context: Context, a: Appointment): State? {
        if (!a.active || a.pickupLocation == null) return null
        return runCatching {
            val data = JSONObject(preferences(context).getString(StateKey, null) ?: return null)
            if (data.getString("id") != a.id || data.getString("target") != targetKey(a)) return null
            State(a.id, data.optBoolean("nearby"))
        }.getOrNull()
    }
    fun accept(context: Context, a: Appointment) {
        require(a.active && a.pickupLocation != null)
        write(context, a, false)
    }
    private fun write(context: Context, a: Appointment, nearby: Boolean) {
        preferences(context).edit().putString(StateKey, JSONObject().put("id", a.id)
            .put("target", targetKey(a)).put("nearby", nearby).toString()).apply()
    }
    fun clear(context: Context, a: Appointment) {
        if (read(context, a) != null) preferences(context).edit().remove(StateKey).apply()
    }
}
