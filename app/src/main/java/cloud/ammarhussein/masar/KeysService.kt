package cloud.ammarhussein.masar

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Lets the home bar press the system Back and Recents keys.
 *
 * Android only allows that through an accessibility service, so this is one in
 * name only: it asks for no events and never reads the screen. It has to be
 * switched on once under Settings → Accessibility; until then the bar's Back
 * and Recents buttons open that screen instead.
 */
class KeysService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
    }

    override fun onInterrupt() {
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        /** The running service, or null while it is switched off. */
        var instance: KeysService? = null
            private set

        /** Presses Back or Recents. False if the service is switched off. */
        fun press(action: Int): Boolean = instance?.performGlobalAction(action) ?: false
    }
}
