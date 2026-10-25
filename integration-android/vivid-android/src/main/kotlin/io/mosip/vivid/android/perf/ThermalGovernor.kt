package io.mosip.vivid.android.perf

import android.content.Context
import android.os.Build
import android.os.PowerManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/**
 * Watches the device thermal state and translates it into a frame budget.
 *
 * - API 29+: [PowerManager.addThermalStatusListener] drives [level].
 * - API 30+: [PowerManager.getThermalHeadroom] refines it between status changes.
 * - Below API 29 the governor is a no-op pinned at [Level.NORMAL].
 *
 * Consumers ([io.mosip.vivid.android.camera.CameraXFrameSource], the engine) read [targetFps]
 * and [rppgEnabled]; PLAN 8.1 asks for 10 fps and no rPPG under throttling.
 */
class ThermalGovernor(context: Context, private val nominalFps: Int = 15) {

    enum class Level(val fps: Int, val rppg: Boolean, val textureEveryN: Int) {
        /** No throttling: full fps, every signal on. */
        NORMAL(15, true, 1),
        /** Light/moderate throttling: 10 fps, rPPG off, CNN every 2nd frame. */
        REDUCED(10, false, 2),
        /** Severe or worse: 6 fps, CNN every 3rd frame. Still enough for blink/turn challenges. */
        MINIMAL(6, false, 3)
    }

    fun interface Listener { fun onLevelChanged(level: Level) }

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile var level: Level = Level.NORMAL
        private set

    val targetFps: Int get() = minOf(nominalFps, level.fps)
    val rppgEnabled: Boolean get() = level.rppg
    val textureEveryN: Int get() = level.textureEveryN

    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    /** Starts listening. Safe to call more than once. [executor] receives status callbacks. */
    fun start(executor: Executor) {
        val pm = powerManager ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || thermalListener != null) return
        val l = PowerManager.OnThermalStatusChangedListener { status -> applyStatus(status) }
        thermalListener = l
        pm.addThermalStatusListener(executor, l)
        applyStatus(pm.currentThermalStatus)
    }

    fun stop() {
        val pm = powerManager ?: return
        val l = thermalListener ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) pm.removeThermalStatusListener(l)
        thermalListener = null
    }

    fun addListener(listener: Listener) { listeners += listener }
    fun removeListener(listener: Listener) { listeners -= listener }

    /**
     * Call once per second or so from the analysis thread. Uses the forecast headroom (API 30+)
     * to step down *before* the OS reports MODERATE, which is what keeps tablets from stuttering.
     */
    fun pollHeadroom() {
        val pm = powerManager ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val headroom = pm.getThermalHeadroom(10)
        if (headroom.isNaN()) return
        val forecast = when {
            headroom >= 1.0f -> Level.MINIMAL
            headroom >= 0.85f -> Level.REDUCED
            else -> Level.NORMAL
        }
        // Only ever tighten based on headroom; relaxing waits for the status listener.
        if (forecast.ordinal > level.ordinal) set(forecast)
    }

    private fun applyStatus(status: Int) {
        val next = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> Level.NORMAL
            status >= PowerManager.THERMAL_STATUS_SEVERE -> Level.MINIMAL
            status >= PowerManager.THERMAL_STATUS_LIGHT -> Level.REDUCED
            else -> Level.NORMAL
        }
        set(next)
    }

    private fun set(next: Level) {
        if (next == level) return
        level = next
        for (l in listeners) l.onLevelChanged(next)
    }
}
