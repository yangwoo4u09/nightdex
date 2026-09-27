package ai.labradorlabs.dexbrightener

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * A component that is allowed to add overlay windows on the user's behalf.
 *
 * Two exist: [DimService] (a foreground service, TYPE_APPLICATION_OVERLAY, capped at
 * 0.8 opacity by the Android 12 untrusted-touch rule) and [DimAccessibilityService]
 * (TYPE_ACCESSIBILITY_OVERLAY, a trusted window with no such cap).
 */
interface OverlayHost {
    val hostContext: Context
    val windowType: Int
    val maxAlpha: Float
    val priority: Int
    val label: String
}

/**
 * Owns the overlay windows, the display list and the ambient-light sensor.
 *
 * Single shared instance so that the UI, the tile, the foreground service and the
 * accessibility service all act on the same state.
 */
object DimEngine {

    private const val TAG = "DimEngine"
    private const val ANIM_MS = 400L

    // Auto-mode filtering, tuned for a car: the phone sits in a cradle and the light
    // sensor gets swept by oncoming headlights, streetlights and overpass shadows.
    // Reacting to those would be worse than not reacting at all.

    /**
     * The filter runs on a fixed tick rather than on sensor callbacks.
     *
     * TYPE_LIGHT is an on-change sensor: while the light is steady it delivers nothing.
     * Advancing a time-based filter only when a sample arrives would freeze it halfway
     * through a transition and leave the screen stuck at whatever a passing headlight
     * pushed it to. Resampling the last reading on a tick makes convergence independent
     * of how the sensor chooses to report.
     */
    private const val TICK_MS = 500L

    /** Median over the last [MEDIAN_WINDOW] ticks, i.e. 2.5s, which outvotes a brief flash. */
    private const val MEDIAN_WINDOW = 5

    /**
     * Time constant for getting brighter. Deliberately long: a headlight that survives the
     * median filter still moves the level by only a few percent, while a real change
     * (leaving a tunnel, sunrise) lasts long enough to be tracked in full.
     */
    private const val TAU_RISE_SEC = 25f

    /** Getting darker is allowed to be quicker: being too bright at night is the complaint. */
    private const val TAU_FALL_SEC = 5f

    private lateinit var app: Context
    lateinit var prefs: Prefs
        private set

    private val main = Handler(Looper.getMainLooper())
    private var initialized = false

    private val hosts = mutableListOf<OverlayHost>()
    private var activeHost: OverlayHost? = null
    private val overlays = HashMap<Int, OverlayWindow>()

    private val _displays = MutableStateFlow<List<DisplayInfo>>(emptyList())
    val displays: StateFlow<List<DisplayInfo>> = _displays.asStateFlow()

    /** Smoothed ambient light, i.e. what the brightness actually follows. */
    private val _lux = MutableStateFlow(Float.NaN)
    val lux: StateFlow<Float> = _lux.asStateFlow()

    /** Unfiltered sensor reading, shown in the UI so the filtering is visible. */
    private val _rawLux = MutableStateFlow(Float.NaN)
    val rawLux: StateFlow<Float> = _rawLux.asStateFlow()

    private val _autoLevel = MutableStateFlow(100)
    val autoLevel: StateFlow<Int> = _autoLevel.asStateFlow()

    /** Null when everything is fine, otherwise a human-readable reason the overlay is not showing. */
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    private val _activeHostLabel = MutableStateFlow<String?>(null)
    val activeHostLabel: StateFlow<String?> = _activeHostLabel.asStateFlow()

    private var sensorRegistered = false
    /** Live slider values that are not persisted yet, keyed by display key. */
    private val previews = HashMap<String, Int>()
    /** Most recent raw reading, resampled by the tick. NaN until the sensor reports once. */
    private var latestRawLux = Float.NaN
    private val luxWindow = ArrayDeque<Float>()

    /**
     * Smoothing happens on the brightness level, not on lux.
     *
     * The lux -> level mapping is logarithmic, so a small absolute change in a dark
     * cabin is a large change in level. Smoothing lux and then taking the log lets a
     * flash through amplified; smoothing the level keeps the time constants meaning
     * what they say in terms of what the user actually sees.
     */
    private var smoothLevel = Float.NaN

    // ---------------------------------------------------------------- lifecycle

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        app = context.applicationContext
        prefs = Prefs(app)
        displayManager.registerDisplayListener(displayListener, main)
        refreshDisplays()
    }

    private val displayManager: DisplayManager
        get() = app.getSystemService(DisplayManager::class.java)

    private val sensorManager: SensorManager
        get() = app.getSystemService(SensorManager::class.java)

    val hasLightSensor: Boolean
        get() = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT) != null

    fun canDrawOverlays(): Boolean = AndroidSettings.canDrawOverlays(app)

    // ------------------------------------------------------------------- hosts

    fun registerHost(host: OverlayHost) {
        main.post {
            if (hosts.none { it === host }) hosts += host
            chooseHost()
        }
    }

    fun unregisterHost(host: OverlayHost) {
        main.post {
            hosts.removeAll { it === host }
            chooseHost()
        }
    }

    /**
     * The accessibility host outranks the service host, but only while the user asked
     * for "deep dim". Otherwise we stay on the plain overlay so no trusted window is
     * held for longer than it is needed.
     */
    private fun chooseHost() {
        val wantDeep = prefs.current.deepDim
        val candidate = hosts
            .filter { wantDeep || it.priority == 0 }
            .maxByOrNull { it.priority }
        if (candidate === activeHost) {
            apply()
            return
        }
        tearDownAll()
        activeHost = candidate
        _activeHostLabel.value = candidate?.label
        apply()
    }

    // ------------------------------------------------------------------ inputs

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refreshDisplays()
        override fun onDisplayRemoved(displayId: Int) {
            main.post { removeOverlay(displayId) }
            refreshDisplays()
        }
        override fun onDisplayChanged(displayId: Int) = refreshDisplays()
    }

    private fun refreshDisplays() {
        main.post {
            _displays.value = displayManager.displays
                .filter { it.isValid }
                .map { DisplayInfo.from(it) }
                .sortedBy { it.id }
            apply()
        }
    }

    /** Displays we attach overlays to: everything except the phone's own panel. */
    fun targetDisplays(): List<DisplayInfo> = _displays.value.filterNot { it.isDefault }

    private val lightListener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        override fun onSensorChanged(event: SensorEvent) {
            val raw = event.values.firstOrNull() ?: return
            // Record only. The tick below decides what to do with it.
            latestRawLux = raw
            _rawLux.value = raw
        }
    }

    private val autoTick = object : Runnable {
        override fun run() {
            stepAutoLevel()
            main.postDelayed(this, TICK_MS)
        }
    }

    /** One step of the auto-mode filter: resample -> median -> asymmetric smoothing. */
    private fun stepAutoLevel() {
        val raw = latestRawLux
        if (raw.isNaN()) return

        luxWindow.addLast(raw)
        while (luxWindow.size > MEDIAN_WINDOW) luxWindow.removeFirst()
        val median = luxWindow.sorted()[luxWindow.size / 2]
        _lux.value = median

        val s = prefs.current
        val target = levelForLux(median, s.autoMinLevel, s.autoMaxLevel).toFloat()
        smoothLevel = when {
            smoothLevel.isNaN() -> target // first reading: adopt it, do not fade in from nothing
            else -> {
                val tau = if (target > smoothLevel) TAU_RISE_SEC else TAU_FALL_SEC
                val k = 1f - exp(-(TICK_MS / 1000f) / tau)
                smoothLevel + k * (target - smoothLevel)
            }
        }

        val next = smoothLevel.roundToInt().coerceIn(Levels.MIN, Levels.MAX)
        if (next != _autoLevel.value) {
            _autoLevel.value = next
            apply()
        }
    }

    /** Forgets the filter history so auto mode locks onto the current light immediately. */
    private fun resetAutoFilter() {
        luxWindow.clear()
        latestRawLux = Float.NaN
        smoothLevel = Float.NaN
        _lux.value = Float.NaN
        _rawLux.value = Float.NaN
    }

    /**
     * Maps ambient light to a brightness level on a log scale, because perceived
     * brightness of a room tracks the order of magnitude of the lux value, not the value.
     * Dark room -> [minLevel]; bright daylight -> [maxLevel].
     */
    fun levelForLux(lux: Float, minLevel: Int, maxLevel: Int): Int {
        if (lux.isNaN()) return maxLevel
        val lo = 0.5f
        val hi = 2000f
        val clamped = lux.coerceIn(lo, hi)
        val t = (log10(clamped) - log10(lo)) / (log10(hi) - log10(lo))
        return (minLevel + t * (maxLevel - minLevel)).roundToInt().coerceIn(Levels.MIN, Levels.MAX)
    }

    private fun updateSensor() {
        val s = prefs.current
        val want = s.enabled && s.mode == DimMode.AUTO && activeHost != null
        if (want && !sensorRegistered) {
            val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
            if (sensor == null) {
                _problem.value = "이 기기에 조도 센서가 없어 자동 모드를 쓸 수 없습니다."
                return
            }
            sensorManager.registerListener(lightListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            sensorRegistered = true
            main.removeCallbacks(autoTick)
            main.postDelayed(autoTick, TICK_MS)
        } else if (!want && sensorRegistered) {
            sensorManager.unregisterListener(lightListener)
            main.removeCallbacks(autoTick)
            sensorRegistered = false
            resetAutoFilter()
        }
    }

    // ------------------------------------------------------------------- apply

    /** Recomputes every overlay from the current settings. Safe to call from any thread. */
    fun apply() {
        if (!initialized) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { apply() }
            return
        }
        updateSensor()

        val s = prefs.current
        val host = activeHost

        if (!s.enabled || host == null) {
            tearDownAll()
            _problem.value = when {
                !s.enabled -> null
                !canDrawOverlays() -> "다른 앱 위에 표시 권한이 없습니다."
                else -> "오버레이를 붙일 수 있는 서비스가 실행 중이 아닙니다."
            }
            return
        }

        val targets = targetDisplays()
        if (targets.isEmpty()) {
            tearDownAll()
            _problem.value = "외부 디스플레이가 연결되어 있지 않습니다."
            return
        }

        val wanted = targets.associateBy { it.id }
        overlays.keys.filterNot { it in wanted }.forEach { removeOverlay(it) }

        var failure: String? = null
        for (target in targets) {
            val level = when {
                s.mode == DimMode.AUTO -> _autoLevel.value
                else -> previews[target.key] ?: s.levelFor(target.key)
            }
            val alpha = Levels.alphaFor(level, host.maxAlpha)
            if (!setOverlayAlpha(host, target.id, alpha)) {
                failure = "${target.name}에 오버레이를 붙이지 못했습니다 (${host.label})."
            }
        }
        _problem.value = failure
    }

    private fun setOverlayAlpha(host: OverlayHost, displayId: Int, alpha: Float): Boolean {
        if (alpha <= 0.001f) {
            removeOverlay(displayId)
            return true
        }
        val existing = overlays[displayId]
        if (existing != null) {
            existing.animateTo(alpha)
            return true
        }
        val display = displayManager.getDisplay(displayId) ?: return false
        val created = createOverlay(host, display, alpha) ?: return false
        overlays[displayId] = created
        return true
    }

    private fun createOverlay(host: OverlayHost, display: Display, alpha: Float): OverlayWindow? =
        try {
            val windowContext = host.hostContext
                .createDisplayContext(display)
                .createWindowContext(host.windowType, null)
            val wm = windowContext.getSystemService(WindowManager::class.java)
            val view = View(windowContext).apply { setBackgroundColor(Color.BLACK) }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                host.windowType,
                // NOT_TOUCHABLE is what lets clicks and taps reach the windows underneath.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                windowAnimations = 0
                this.alpha = alpha
                title = "DexBrightener:${display.displayId}"
            }
            wm.addView(view, lp)
            OverlayWindow(wm, view, lp)
        } catch (t: Throwable) {
            Log.e(TAG, "failed to add overlay on display ${display.displayId} via ${host.label}", t)
            null
        }

    private fun removeOverlay(displayId: Int) {
        overlays.remove(displayId)?.destroy()
    }

    private fun tearDownAll() {
        overlays.values.forEach { it.destroy() }
        overlays.clear()
    }

    // ------------------------------------------------------------- mutations

    fun setEnabled(enabled: Boolean) {
        prefs.setEnabled(enabled)
        apply()
    }

    fun setMode(mode: DimMode) {
        if (mode == DimMode.AUTO) resetAutoFilter()
        prefs.setMode(mode)
        apply()
    }

    fun setLevel(key: String, level: Int) {
        prefs.setLevel(key, level)
        apply()
    }

    /** Applies a level while the user drags, without writing it to disk on every frame. */
    fun preview(key: String, level: Int) {
        previews[key] = level.coerceIn(Levels.MIN, Levels.MAX)
        apply()
    }

    fun commitPreview(key: String, level: Int) {
        previews.remove(key)
        setLevel(key, level)
    }

    fun setAutoRange(min: Int, max: Int) {
        prefs.setAutoRange(min, max)
        // Re-aim at the new range without waiting for the filter to walk there.
        if (!_lux.value.isNaN()) {
            smoothLevel = levelForLux(_lux.value, min, max).toFloat()
            _autoLevel.value = smoothLevel.roundToInt()
        }
        apply()
    }

    fun setDeepDim(enabled: Boolean) {
        prefs.setDeepDim(enabled)
        main.post { chooseHost() }
    }

    private class OverlayWindow(
        private val wm: WindowManager,
        private val view: View,
        private val lp: WindowManager.LayoutParams,
    ) {
        private var animator: ValueAnimator? = null

        fun animateTo(target: Float) {
            if (abs(lp.alpha - target) < 0.002f) return
            animator?.cancel()
            // A slider drag produces a stream of tiny steps; animating each one would
            // lag behind the finger, so only real jumps are animated.
            if (abs(lp.alpha - target) < 0.06f) {
                lp.alpha = target
                runCatching { wm.updateViewLayout(view, lp) }
                return
            }
            animator = ValueAnimator.ofFloat(lp.alpha, target).apply {
                duration = ANIM_MS
                addUpdateListener { a ->
                    lp.alpha = a.animatedValue as Float
                    runCatching { wm.updateViewLayout(view, lp) }
                }
                start()
            }
        }

        fun destroy() {
            animator?.cancel()
            animator = null
            runCatching { wm.removeViewImmediate(view) }
        }
    }
}
