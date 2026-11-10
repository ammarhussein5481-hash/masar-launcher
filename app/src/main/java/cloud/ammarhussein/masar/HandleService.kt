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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
 * The home handle: a way back to Masar from apps that hide the navigation bar,
 * mainly CarPlay in the AutoKit app.
 *
 * Touch the screen anywhere and a white pill appears at the bottom centre for a
 * few seconds. Tap it, or swipe up from it, and Masar comes to the front.
 *
 * It is made of two overlay windows:
 *  - a one-pixel "watcher" that hears every touch on the screen without taking
 *    it (the system sends it an outside-touch notice), and
 *  - the pill, which is only on screen for those few seconds. While it is gone
 *    nothing of ours covers the app underneath, so no tap is ever swallowed.
 *
 * Both exist only while Masar is not in front: [MainActivity] sets
 * [homeVisible] as it starts and stops. The service runs in the foreground so
 * the system keeps it alive for a whole drive. It needs the "display over other
 * apps" permission; without it the service stops itself.
 */
class HandleService : Service() {

    private lateinit var wm: WindowManager
    private var watcher: View? = null
    private var pill: View? = null
    private val ui = Handler(Looper.getMainLooper())
    private val hidePill = Runnable { removePill() }

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
        removePill()
        removeWatcher()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Sizes come from the screen; drop the pill so the next one is measured afresh.
        removePill()
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

    // ---------------------------------------------------------------- windows

    /** Listens for touches while another app is in front; stands down while Masar is. */
    private fun update() {
        if (homeVisible || !allowed(this)) {
            removePill()
            removeWatcher()
        } else {
            addWatcher()
        }
    }

    /** One pixel in the corner. It takes no touches of its own to speak of, but is told about all of them. */
    @SuppressLint("ClickableViewAccessibility")
    private fun addWatcher() {
        if (watcher != null) return
        val view = View(this)
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE || event.actionMasked == MotionEvent.ACTION_DOWN) {
                reveal()
            }
            false
        }
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.title = "Masar touch watcher"
        try {
            wm.addView(view, params)
            watcher = view
        } catch (ex: Exception) {
            // Permission revoked between the check and now: nothing to add.
            watcher = null
        }
    }

    private fun removeWatcher() {
        val view = watcher ?: return
        watcher = null
        try {
            wm.removeView(view)
        } catch (ex: Exception) {
            // Already gone
        }
    }

    /** Shows the pill, or keeps it up, for a few seconds after a touch. */
    private fun reveal() {
        ui.removeCallbacks(hidePill)
        if (pill == null) addPill()
        ui.postDelayed(hidePill, SHOW_MS)
    }

    /**
     * The pill: white with a dark outline so it reads on light and dark screens
     * alike, lifted a little off the bottom edge in case a bezel covers it. The
     * window is larger than the pill itself, to give a finger something to hit.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun addPill() {
        val screen = screenSize()
        u = screen.second / 100f

        val body = View(this)
        val shape = GradientDrawable()
        shape.cornerRadius = u * PILL_THICK
        shape.setColor(PILL_COLOR)
        shape.setStroke(max(2, (u * 0.4f).roundToInt()), PILL_EDGE)
        body.background = shape

        val target = FrameLayout(this)
        target.addView(
            body,
            FrameLayout.LayoutParams(
                (u * PILL_LONG).roundToInt(),
                max(8, (u * PILL_THICK).roundToInt()),
                Gravity.CENTER
            )
        )
        target.alpha = 0f
        target.setOnTouchListener { view, event -> onPillTouch(view, event) }

        val params = WindowManager.LayoutParams(
            (u * TARGET_WIDE).roundToInt(),
            (u * TARGET_TALL).roundToInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        params.y = (u * LIFT).roundToInt()
        params.title = "Masar home handle"

        try {
            wm.addView(target, params)
            pill = target
            target.animate().alpha(1f).setDuration(FADE_IN_MS).start()
        } catch (ex: Exception) {
            pill = null
        }
    }

    private fun removePill() {
        ui.removeCallbacks(hidePill)
        val view = pill ?: return
        pill = null
        try {
            wm.removeView(view)
        } catch (ex: Exception) {
            // Already gone
        }
    }

    /**
     * A tap on the pill, or a swipe up from it, goes home. A sideways drag does
     * nothing. Touching the pill also keeps it on screen.
     */
    private fun onPillTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ui.removeCallbacks(hidePill)
                downX = event.rawX
                downY = event.rawY
                fired = false
            }
            MotionEvent.ACTION_MOVE -> if (!fired) {
                val up = downY - event.rawY
                val sideways = abs(event.rawX - downX)
                if (up > u * SWIPE_UNITS && up > sideways) fire(view)
            }
            MotionEvent.ACTION_UP -> {
                val moved = max(abs(event.rawX - downX), abs(event.rawY - downY))
                if (!fired && moved < u * TAP_SLOP) fire(view)
                if (!fired) ui.postDelayed(hidePill, SHOW_MS)
            }
            MotionEvent.ACTION_CANCEL -> ui.postDelayed(hidePill, SHOW_MS)
        }
        return true
    }

    private fun fire(view: View) {
        fired = true
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        goHome()
        removePill()
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

        /** Pill length and thickness, in percent of screen height. On 1200x720: 158 x 20 px. */
        private const val PILL_LONG = 22f
        private const val PILL_THICK = 2.8f

        /** The touchable window around the pill, and how far it sits above the bottom edge. */
        private const val TARGET_WIDE = 30f
        private const val TARGET_TALL = 9f
        private const val LIFT = 1.5f

        /** A swipe has to travel this far up; a tap may wander this far. Percent of screen height. */
        private const val SWIPE_UNITS = 4f
        private const val TAP_SLOP = 3f

        /** Nearly solid white with a dark edge, so it shows on any background. */
        private const val PILL_COLOR = 0xF2FFFFFF.toInt()
        private const val PILL_EDGE = 0xCC000000.toInt()

        /** How long the pill stays after the last touch. */
        private const val SHOW_MS = 3000L
        private const val FADE_IN_MS = 120L

        const val PREF_ENABLED = "handle_on"

        private var current: HandleService? = null

        /** True while Masar's own window is on screen; the handle stands down then. */
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
