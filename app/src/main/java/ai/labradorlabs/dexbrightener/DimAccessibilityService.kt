package ai.labradorlabs.dexbrightener

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Optional "deep dim" backend.
 *
 * Android 12+ blocks touches that pass through an untrusted overlay once its combined
 * opacity goes above 0.8. TYPE_ACCESSIBILITY_OVERLAY is a trusted window and is exempt,
 * so enabling this service is the only way to go darker while keeping the screen usable.
 *
 * The service reads nothing: canRetrieveWindowContent is false and events are ignored.
 */
class DimAccessibilityService : AccessibilityService(), OverlayHost {

    override val hostContext: Context get() = this
    override val windowType: Int = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
    override val maxAlpha: Float = Levels.TRUSTED_MAX_ALPHA
    override val priority: Int = 1
    override val label: String = "접근성 오버레이"

    override fun onServiceConnected() {
        super.onServiceConnected()
        DimEngine.init(this)
        connected = true
        DimEngine.registerHost(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        connected = false
        DimEngine.unregisterHost(this)
        return super.onUnbind(intent)
    }

    companion object {
        /** Whether the user has this service turned on in system settings right now. */
        @Volatile
        var connected: Boolean = false
            private set
    }
}
