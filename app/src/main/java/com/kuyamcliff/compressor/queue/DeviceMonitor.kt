package com.kuyamcliff.compressor.queue

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.kuyamcliff.compressor.data.prefs.PerformanceProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DeviceState(
    /** PowerManager.THERMAL_STATUS_* (0 none … 6 shutdown). */
    val thermalStatus: Int = 0,
    val thermalHeadroom: Float = Float.NaN,
    val charging: Boolean = true,
    val batteryPercent: Int = 100,
    val powerSave: Boolean = false,
    val lowMemory: Boolean = false,
    val availableMemBytes: Long = 0,
)

/** What the scheduler should do to running jobs right now. */
data class ResourcePolicy(
    val throttlePercent: Int = 0,
    val pause: Boolean = false,
    val reason: String? = null,
    val thermal: Boolean = false,
    val maxConcurrent: Int = Int.MAX_VALUE,
)

/**
 * Watches thermal state, battery/charging and memory pressure (PRD §48, §49,
 * §74) and turns them into a [ResourcePolicy] according to the user's
 * performance profile. High job priority never overrides thermal safety.
 */
class DeviceMonitor(private val context: Context) {
    private val pm = context.getSystemService(PowerManager::class.java)
    private val am = context.getSystemService(ActivityManager::class.java)
    private val _state = MutableStateFlow(DeviceState())
    val state: StateFlow<DeviceState> = _state.asStateFlow()
    private var started = false

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status -> refresh(thermal = status) }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            refresh(batteryIntent = intent?.takeIf { it.action == Intent.ACTION_BATTERY_CHANGED })
        }
    }

    fun start() {
        if (started) return
        started = true
        pm.addThermalStatusListener(ContextCompat.getMainExecutor(context), thermalListener)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        val sticky = ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh(thermal = pm.currentThermalStatus, batteryIntent = sticky)
    }

    fun refresh(thermal: Int? = null, batteryIntent: Intent? = null) {
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val prev = _state.value
        var charging = prev.charging
        var pct = prev.batteryPercent
        if (batteryIntent != null) {
            val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            charging = plugged != 0 || status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) pct = level * 100 / scale
        }
        val headroom = if (Build.VERSION.SDK_INT >= 30) runCatching { pm.getThermalHeadroom(10) }.getOrDefault(Float.NaN) else Float.NaN
        _state.value = DeviceState(
            thermalStatus = thermal ?: prev.thermalStatus,
            thermalHeadroom = headroom,
            charging = charging,
            batteryPercent = pct,
            powerSave = pm.isPowerSaveMode,
            lowMemory = mem.lowMemory,
            availableMemBytes = mem.availMem,
        )
    }

    companion object {
        /** Pure policy function (unit-tested). */
        fun policy(profile: PerformanceProfile, s: DeviceState, chargingOnly: Boolean): ResourcePolicy {
            if (chargingOnly && !s.charging) return ResourcePolicy(pause = true, reason = "Waiting for the charger (\"Only run when charging\" is on).")
            if (!s.charging && s.batteryPercent in 0..10) return ResourcePolicy(pause = true, reason = "Battery is low (${s.batteryPercent}%). Encoding paused.")
            val t = s.thermalStatus
            var throttle = 0
            var pause = false
            var reason: String? = null
            var thermal = false
            when (profile) {
                PerformanceProfile.PERFORMANCE -> when {
                    t >= PowerManager.THERMAL_STATUS_CRITICAL -> { pause = true; thermal = true }
                    t >= PowerManager.THERMAL_STATUS_SEVERE -> { throttle = 30; thermal = true }
                }
                PerformanceProfile.BALANCED, PerformanceProfile.BATTERY -> when {
                    t >= PowerManager.THERMAL_STATUS_CRITICAL -> { pause = true; thermal = true }
                    t >= PowerManager.THERMAL_STATUS_SEVERE -> { throttle = 50; thermal = true }
                    t >= PowerManager.THERMAL_STATUS_MODERATE -> { throttle = 20; thermal = true }
                }
                PerformanceProfile.THERMAL -> when {
                    t >= PowerManager.THERMAL_STATUS_SEVERE -> { pause = true; thermal = true }
                    t >= PowerManager.THERMAL_STATUS_MODERATE -> { throttle = 40; thermal = true }
                    t >= PowerManager.THERMAL_STATUS_LIGHT -> { throttle = 15; thermal = true }
                }
            }
            if (thermal) reason = if (pause) "Device is too hot. Encoding is paused until it cools down."
            else "Device is becoming hot. Encoding is temporarily slowed to protect the device."
            if (profile == PerformanceProfile.BATTERY && !s.charging) {
                throttle = maxOf(throttle, if (s.powerSave) 50 else 35)
                if (reason == null) reason = "Battery saver profile: encoding runs slower while unplugged."
            }
            val maxConcurrent = if (thermal || s.lowMemory) 1 else Int.MAX_VALUE
            return ResourcePolicy(throttle.coerceIn(0, 90), pause, reason, thermal, maxConcurrent)
        }
    }
}
