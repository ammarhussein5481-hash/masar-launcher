package cloud.ammarhussein.masar

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The home handle: a thin strip along the bottom edge of the screen, drawn over
 * other apps, with a faint pill in the middle. Swiping up on it brings Masar back.
 *
 * It exists for full-screen apps that hide the navigation bar, mainly CarPlay in
 * the AutoKit app. A plain tap does nothing, so the strip can't press whatever
 * sits underneath it by accident.
 *
 * The strip is only on screen while Masar is not: [MainActivity] sets
 * [homeVisible] as it starts and stops. It runs as a foreground service so the
 * system keeps it alive while Masar sits in the background for a whole drive.
 * It needs the "display over other apps" permission; without it the service
 * stops itself.
 */
class HandleService : Service() {

    private lateinit var wm: WindowManager
    private var strip: FrameLayout? = null
    private var shown = false

    /** One percent of the screen height, in pixels. All sizes are multiples of it. */
    private var u = 7.2f

    private var downX = 0f
    private var downY = 0f
    private var fired = false

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        goForeground()
        current = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!allowed(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        update()
        // If the system kills the process, bring the handle back when it can.
        return START_STICKY
    }

    override fun onDestroy() {
        if (current === this) current = null
        hide()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (shown) {
            hide()
            update()
        }
    }

    private fun goForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL, getString(R.string.handle_channel), NotificationManager.IMPORTANCE_MIN)
        channel.setShowBadge(false)
        nm.createNotificationChannel(channel)

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_apps)
            .setContentTitle(getString(R.string.handle_running))
            .setContentText(getString(R.string.handle_running_detail))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---------------------------------------------------------------- the strip

    /** Puts the strip on screen or takes it off, to match whether Masar is in front. */
    private fun update() {
        if (homeVisible || !allowed(this)) hide() else show()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show() {
        if (shown) return
        val screen = screenSize()
        u = screen.second / 100f

        val pillView = View(this)
        val shape = GradientDrawable()
        shape.cornerRadius = u * PILL_THICK
        shape.setColor(PILL_COLOR)
        shape.setStroke(max(1, (u * 0.15f).roundToInt()), PILL_EDGE)
        pillView.background = shape

        val stripView = FrameLayout(this)
        stripView.addView(
            pillView,
            FrameLayout.LayoutParams((u * PILL_LONG).roundToInt(), max(2, (u * PILL_THICK).roundToInt()), Gravity.CENTER)
        )
        stripView.setOnTouchListener { view, event -> onStripTouch(view, event) }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            max(4, (u * STRIP_TALL).roundToInt()),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        params.title = "Masar home handle"

        try {
            wm.addView(stripView, params)
            strip = stripView
            shown = true
        } catch (ex: Exception) {
            // Permission revoked between the check and now: nothing to draw.
            strip = null
            shown = false
        }
    }

    private fun hide() {
        val view = strip
        strip = null
        shown = false
        if (view != null) {
            try {
                wm.removeView(view)
            } catch (ex: Exception) {
                // Already gone
            }
        }
    }

    /**
     * Swipe up: bring Masar to the front, once per gesture. The finger leaves the
     * thin strip almost at once, but the window keeps getting the moves (above
     * its top edge, y is negative). Anything else, a tap included, does nothing.
     */
    private fun onStripTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                fired = false
            }
            MotionEvent.ACTION_MOVE -> if (!fired) {
                val dy = downY - event.rawY
                val dx = abs(event.rawX - downX)
                if (dy > u * SWIPE_UNITS && dy > dx) {
                    fired = true
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    goHome()
                }
            }
        }
        return true
    }

    private fun goHome() {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (ex: Exception) {
            // Nothing sensible to do from an overlay; the floating dot still works.
        }
    }

    /** Real screen width and height in pixels, including any hidden system bars. */
    private fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = wm.maximumWindowMetrics.bounds
            return Pair(bounds.width(), bounds.height())
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return Pair(metrics.widthPixels, metrics.heightPixels)
    }

    companion object {
        private const val CHANNEL = "handle"
        private const val NOTIFICATION_ID = 1

        /** Touch strip height, pill thickness and pill length, in percent of screen height. */
        private const val STRIP_TALL = 2f
        private const val PILL_THICK = 0.7f
        private const val PILL_LONG = 14f

        /** How far up, in percent of screen height, a swipe has to travel. */
        private const val SWIPE_UNITS = 5f

        /** Faint white with a faint dark edge, so it shows on light and dark screens alike. */
        private const val PILL_COLOR = 0x4DFFFFFF
        private const val PILL_EDGE = 0x33000000

        const val PREF_ENABLED = "handle_on"

        private var current: HandleService? = null

        /** True while Masar's own window is on screen; the strip hides then. */
        var homeVisible = false
            set(value) {
                field = value
                current?.update()
            }

        fun enabled(context: Context): Boolean =
            context.getSharedPreferences("masar", Context.MODE_PRIVATE).getBoolean(PREF_ENABLED, true)

        /** Turned on in Masar, and the system lets us draw over other apps. */
        fun allowed(context: Context): Boolean = enabled(context) && Settings.canDrawOverlays(context)
    }
}
