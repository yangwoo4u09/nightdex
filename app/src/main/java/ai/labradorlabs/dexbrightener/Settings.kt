package ai.labradorlabs.dexbrightener

import android.content.Context
import android.view.Display
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DimMode { FIXED, AUTO }

/**
 * A brightness "level" is 0..100 where 100 means "no dimming at all" and 0 means
 * "as dark as the active overlay backend is allowed to go".
 */
object Levels {
    const val MIN = 0
    const val MAX = 100

    /** Android 12+ blocks pass-through touches once a SYSTEM_ALERT_WINDOW overlay exceeds this. */
    const val UNTRUSTED_MAX_ALPHA = 0.8f

    /** An accessibility overlay is a trusted window, so it is not subject to the cap. */
    const val TRUSTED_MAX_ALPHA = 0.95f

    fun alphaFor(level: Int, maxAlpha: Float): Float {
        val clamped = level.coerceIn(MIN, MAX)
        return (MAX - clamped) / MAX.toFloat() * maxAlpha
    }
}

data class Settings(
    val enabled: Boolean = false,
    val mode: DimMode = DimMode.FIXED,
    val defaultLevel: Int = 70,
    val autoMinLevel: Int = 25,
    val autoMaxLevel: Int = 100,
    val deepDim: Boolean = false,
    val levels: Map<String, Int> = emptyMap(),
) {
    fun levelFor(key: String): Int = levels[key] ?: defaultLevel
}

/** A display we can attach an overlay to. */
data class DisplayInfo(
    val id: Int,
    val name: String,
    val widthPx: Int,
    val heightPx: Int,
    val isDefault: Boolean,
) {
    /** Stable-ish key used to remember a per-monitor level across reconnects. */
    val key: String = name.trim().ifEmpty { "display-$id" }.replace(Regex("[^A-Za-z0-9._-]"), "_")

    companion object {
        fun from(display: Display): DisplayInfo {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            return DisplayInfo(
                id = display.displayId,
                name = display.name ?: "Display ${display.displayId}",
                widthPx = metrics.widthPixels,
                heightPx = metrics.heightPixels,
                isDefault = display.displayId == Display.DEFAULT_DISPLAY,
            )
        }
    }
}

class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state.asStateFlow()

    val current: Settings get() = _state.value

    private fun read(): Settings {
        val levels = sp.all.entries
            .filter { it.key.startsWith(LEVEL_PREFIX) && it.value is Int }
            .associate { it.key.removePrefix(LEVEL_PREFIX) to (it.value as Int) }
        return Settings(
            enabled = sp.getBoolean(KEY_ENABLED, false),
            mode = runCatching { DimMode.valueOf(sp.getString(KEY_MODE, null) ?: "FIXED") }
                .getOrDefault(DimMode.FIXED),
            defaultLevel = sp.getInt(KEY_DEFAULT_LEVEL, 70),
            autoMinLevel = sp.getInt(KEY_AUTO_MIN, 25),
            autoMaxLevel = sp.getInt(KEY_AUTO_MAX, 100),
            deepDim = sp.getBoolean(KEY_DEEP_DIM, false),
            levels = levels,
        )
    }

    private fun commit(block: android.content.SharedPreferences.Editor.() -> Unit) {
        sp.edit().apply(block).apply()
        _state.value = read()
    }

    fun setEnabled(value: Boolean) = commit { putBoolean(KEY_ENABLED, value) }

    fun setMode(value: DimMode) = commit { putString(KEY_MODE, value.name) }

    fun setDeepDim(value: Boolean) = commit { putBoolean(KEY_DEEP_DIM, value) }

    /** Remembers the level for one monitor and also as the default for monitors we see later. */
    fun setLevel(key: String, level: Int) = commit {
        val clamped = level.coerceIn(Levels.MIN, Levels.MAX)
        putInt(LEVEL_PREFIX + key, clamped)
        putInt(KEY_DEFAULT_LEVEL, clamped)
    }

    fun setAutoRange(min: Int, max: Int) = commit {
        val lo = min.coerceIn(Levels.MIN, Levels.MAX)
        val hi = max.coerceIn(lo, Levels.MAX)
        putInt(KEY_AUTO_MIN, lo)
        putInt(KEY_AUTO_MAX, hi)
    }

    private companion object {
        const val FILE = "dex_brightener"
        const val KEY_ENABLED = "enabled"
        const val KEY_MODE = "mode"
        const val KEY_DEFAULT_LEVEL = "default_level"
        const val KEY_AUTO_MIN = "auto_min"
        const val KEY_AUTO_MAX = "auto_max"
        const val KEY_DEEP_DIM = "deep_dim"
        const val LEVEL_PREFIX = "level:"
    }
}
