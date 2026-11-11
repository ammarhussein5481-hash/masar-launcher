package cloud.ammarhussein.masar

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import java.util.Locale
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import android.util.TypedValue

/**
 * Masar's one background service. It keeps running while other apps are in
 * front, and does the things that have to carry on then:
 *
 * The home handle: a way to reach Home, Back and the volume from any app,
 * including ones that hide the system navigation bar such as CarPlay in AutoKit.
 * Touch the screen anywhere and it appears at the bottom for a few seconds,
 * then goes away again. It has two looks, chosen in Masar ([MODE_BAR],
 * [MODE_PILL]):
 *  - bar: a full row of buttons like the head unit's own bar. Back, Recents,
 *    volume down and up, Home, and the apps from Masar's tiles 2 to 4.
 *  - pill: only a small white capsule. Tap it, or swipe up from it, to go home.
 * It is made of overlay windows: a one-pixel "watcher" that hears every touch
 * on the screen without taking it, and the bar or pill, which is only on screen
 * for those few seconds. This part needs the "display over other apps"
 * permission.
 *
 * The trip and the speed alert: it listens to GPS for the whole drive, feeds
 * [Trip], and when the speed passes the chosen limit it beeps once and shows
 * the speed in red at the top of the screen until the car slows down.
 */
class HandleService : Service() {

    private lateinit var wm: WindowManager
    private var watcher: View? = null
    private var pill: View? = null
    private var bar: View? = null
    private val ui = Handler(Looper.getMainLooper())
    private val hideAll = Runnable {
        removePill()
        removeBar()
    }

    /** One percent of the screen height, in pixels. All sizes are multiples of it. */
    private var u = 7.2f

    private var downX = 0f
    private var downY = 0f
    private var fired = false

    private var gpsOn = false
    private var overLimit = false
    private var chip: TextView? = null

    private var alert: TextView? = null
    /** Which warning is showing: "", "coolant", "trans", "volts", "tyre" or "code". */
    private var alertKey = ""
    private var voltsOff = 0
    private var codeText = ""
    private var codeUntil = 0L
    private var carWatching = false
    private val carWatch = object : Runnable {
        override fun run() {
            checkCar()
            ui.postDelayed(this, 2000)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Options.localized(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        goForeground()
        current = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The look may have changed in Masar: drop whatever is showing.
        removePill()
        removeBar()
        update()
        startGps()
        Obd.start(this)
        if (!carWatching) {
            carWatching = true
            ui.postDelayed(carWatch, 2000)
        }
        // If the system kills the process, bring the handle back when it can.
        return START_STICKY
    }

    override fun onDestroy() {
        if (current === this) current = null
        removePill()
        removeBar()
        removeWatcher()
        removeChip()
        removeAlert()
        ui.removeCallbacks(carWatch)
        stopGps()
        Trip.save(Options.prefs(this))
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Sizes come from the screen; drop what is showing so the next one is measured afresh.
        removePill()
        removeBar()
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
            // The location type may only be claimed once the permission is held.
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (hasLocation()) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(NOTIFICATION_ID, notification, types)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---------------------------------------------------------------- watcher

    /**
     * Decides whether to listen for touches. The bar is useful everywhere, Masar
     * included (volume, Back). The pill only goes home, so it stands down while
     * Masar is already in front.
     */
    private fun update() {
        val mode = mode(this)
        val listen = allowed(this) && (mode == MODE_BAR || (mode == MODE_PILL && !homeVisible))
        if (listen) {
            addWatcher()
        } else {
            removePill()
            removeBar()
            removeWatcher()
        }
        if (mode == MODE_PILL && homeVisible) removePill()
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
        removeWindow(view)
    }

    private fun removeWindow(view: View) {
        try {
            wm.removeView(view)
        } catch (ex: Exception) {
            // Already gone
        }
    }

    /** A touch somewhere: show the bar or pill, or keep it up a little longer. */
    private fun reveal() {
        ui.removeCallbacks(hideAll)
        if (mode(this) == MODE_BAR) {
            if (bar == null) addBar()
        } else {
            if (pill == null) addPill()
        }
        ui.postDelayed(hideAll, SHOW_MS)
    }

    private fun keepUp() {
        ui.removeCallbacks(hideAll)
        ui.postDelayed(hideAll, SHOW_MS)
    }

    private fun overlayParams(width: Int, height: Int): WindowManager.LayoutParams {
        val params = WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        return params
    }

    // ---------------------------------------------------------------- bar

    /**
     * A row of buttons across the bottom of the screen, in the place and style
     * of the head unit's own bar.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun addBar() {
        u = screenSize().second / 100f

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        val back = GradientDrawable()
        back.setColor(BAR_COLOR)
        row.background = back
        // A touch on the bar between the buttons keeps it up and goes no further.
        row.setOnTouchListener { _, _ ->
            keepUp()
            true
        }

        addButton(row, getDrawable(R.drawable.ic_bar_back), ICON) { pressKey(AccessibilityService.GLOBAL_ACTION_BACK) }
        addButton(row, getDrawable(R.drawable.ic_bar_recents), ICON) { pressKey(AccessibilityService.GLOBAL_ACTION_RECENTS) }
        addButton(row, getDrawable(R.drawable.ic_bar_vol_down), ICON) { volume(AudioManager.ADJUST_LOWER) }
        addButton(row, getDrawable(R.drawable.ic_bar_vol_up), ICON) { volume(AudioManager.ADJUST_RAISE) }
        val homeIcon = getDrawable(R.drawable.ic_bar_home)?.mutate()
        homeIcon?.setTint(Options.accent(Options.prefs(this)))
        addButton(row, homeIcon, ICON_HOME) {
            goHome()
            removeBar()
        }
        // Up to three apps, the ones in Masar's tiles 2 to 4.
        val prefs = getSharedPreferences("masar", Context.MODE_PRIVATE)
        for (i in 0 until BAR_APPS) {
            val saved = prefs.getString(PREF_BAR_APP + i, null) ?: continue
            val component = ComponentName.unflattenFromString(saved) ?: continue
            val icon = try {
                packageManager.getActivityIcon(component)
            } catch (ex: Exception) {
                continue
            }
            addButton(row, icon, ICON_APP) {
                launch(component)
                removeBar()
            }
        }

        val params = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, (u * BAR_TALL).roundToInt())
        params.title = "Masar home bar"
        row.alpha = 0f
        row.translationY = u * 3f
        try {
            wm.addView(row, params)
            bar = row
            row.animate().alpha(1f).translationY(0f).setDuration(FADE_IN_MS).start()
        } catch (ex: Exception) {
            bar = null
        }
    }

    private fun addButton(row: LinearLayout, icon: Drawable?, iconUnits: Float, action: () -> Unit) {
        val button = ImageView(this)
        button.setImageDrawable(icon)
        button.scaleType = ImageView.ScaleType.FIT_CENTER
        val pad = ((BAR_TALL - iconUnits) / 2f * u).roundToInt()
        val side = ((BUTTON_WIDE - iconUnits) / 2f * u).roundToInt()
        button.setPadding(side, pad, side, pad)
        button.setBackgroundResource(R.drawable.bar_button)
        button.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            keepUp()
            action()
        }
        row.addView(button, LinearLayout.LayoutParams((u * BUTTON_WIDE).roundToInt(), LinearLayout.LayoutParams.MATCH_PARENT))
    }

    private fun removeBar() {
        val view = bar ?: return
        bar = null
        removeWindow(view)
    }

    private fun pressKey(action: Int) {
        if (KeysService.press(action)) return
        // The accessibility switch is off: say so and open the screen that has it.
        Toast.makeText(this, R.string.keys_needed, Toast.LENGTH_LONG).show()
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (ex: Exception) {
            // No such screen on this unit
        }
        removeBar()
    }

    private fun volume(direction: Int) {
        try {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        } catch (ex: Exception) {
            // Fixed-volume device
        }
    }

    private fun launch(component: ComponentName) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(intent)
        } catch (ex: Exception) {
            // Uninstalled since Masar last looked
        }
    }

    // ---------------------------------------------------------------- pill

    /**
     * The pill: white with a dark outline so it reads on light and dark screens
     * alike, lifted a little off the bottom edge in case a bezel covers it. The
     * window is larger than the pill itself, to give a finger something to hit.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun addPill() {
        u = screenSize().second / 100f

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

        val params = overlayParams((u * TARGET_WIDE).roundToInt(), (u * TARGET_TALL).roundToInt())
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
        val view = pill ?: return
        pill = null
        removeWindow(view)
    }

    /**
     * A tap on the pill, or a swipe up from it, goes home. A sideways drag does
     * nothing. Touching the pill also keeps it on screen.
     */
    private fun onPillTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ui.removeCallbacks(hideAll)
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
                if (!fired) keepUp()
            }
            MotionEvent.ACTION_CANCEL -> keepUp()
        }
        return true
    }

    private fun fire(view: View) {
        fired = true
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        goHome()
        removePill()
    }

    // ---------------------------------------------------------------- trip and speed alert

    private fun hasLocation(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (gpsOn || !hasLocation()) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener)
            gpsOn = true
        } catch (ex: Exception) {
            // No GPS provider on this unit
        }
    }

    private fun stopGps() {
        if (!gpsOn) return
        gpsOn = false
        try {
            (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(locationListener)
        } catch (ex: Exception) {
            // Already stopped
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val prefs = Options.prefs(this@HandleService)
            Trip.onFix(location, prefs)
            checkSpeed(location, Options.speedLimit(prefs))
        }

        // These three are abstract on Android 10, so they must be overridden.
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        }

        override fun onProviderEnabled(provider: String) {
        }

        override fun onProviderDisabled(provider: String) {
        }
    }

    /**
     * Over the limit: one beep as the speed crosses it, and a red speed readout
     * at the top of the screen while it stays over. Slowing to a little under
     * the limit clears it, so hovering at the limit doesn't keep beeping.
     */
    private fun checkSpeed(location: Location, limit: Int) {
        val kmh = if (location.hasSpeed()) (location.speed * 3.6f).roundToInt() else 0
        if (limit <= 0) {
            overLimit = false
            removeChip()
            return
        }
        if (!overLimit && kmh > limit) {
            overLimit = true
            beep()
        } else if (overLimit && kmh < limit - 3) {
            overLimit = false
        }
        // Masar shows the speed in red itself; the chip is for other apps.
        if (overLimit && !homeVisible && Settings.canDrawOverlays(this)) showChip(kmh) else removeChip()
    }

    private fun beep() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 350)
            ui.postDelayed({ tone.release() }, 800)
        } catch (ex: Exception) {
            // No tone generator on this unit: the red readout still shows
        }
    }

    private fun showChip(kmh: Int) {
        var view = chip
        if (view == null) {
            u = screenSize().second / 100f
            view = TextView(this)
            view.setTextColor(0xFFFFFFFF.toInt())
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 6f)
            view.gravity = Gravity.CENTER
            val pad = (u * 2.2f).roundToInt()
            view.setPadding(pad * 2, pad / 2, pad * 2, pad / 2)
            val shape = GradientDrawable()
            shape.cornerRadius = u * 6f
            shape.setColor(CHIP_COLOR)
            view.background = shape
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            params.y = (u * 2f).roundToInt()
            params.title = "Masar speed alert"
            try {
                wm.addView(view, params)
                chip = view
            } catch (ex: Exception) {
                return
            }
        }
        view.text = getString(R.string.speed_chip, kmh)
    }

    // ---------------------------------------------------------------- car warnings

    /**
     * Watches the figures from the OBD dongle and puts a red banner over
     * whatever app is in front when one needs attention: engine or
     * transmission too hot, charging voltage wrong while the engine runs, or a
     * fault code that wasn't there before. One beep as each warning starts.
     */
    private fun checkCar() {
        var text: String? = null
        var key = ""
        val now = SystemClock.elapsedRealtime()
        if (Obd.state == Obd.CONNECTED) {
            val coolant = Obd.coolant
            val trans = Obd.trans
            val volts = Obd.volts
            val rpm = Obd.rpm
            // A warning that is showing clears a little below where it started.
            val coolantAt = if (alertKey == "coolant") COOLANT_HOT - 3 else COOLANT_HOT
            val transAt = if (alertKey == "trans") TRANS_HOT - 3 else TRANS_HOT
            // Voltage only counts with the engine running, and only if it stays wrong.
            if (volts != null && rpm != null && rpm > 400 && (volts < VOLTS_LOW || volts > VOLTS_HIGH)) voltsOff++ else voltsOff = 0

            if (coolant != null && coolant >= coolantAt) {
                text = getString(R.string.alert_coolant, coolant)
                key = "coolant"
            } else if (trans != null && trans >= transAt) {
                text = getString(R.string.alert_trans, trans)
                key = "trans"
            } else if (voltsOff >= 5 && volts != null) {
                text = getString(R.string.alert_volts, String.format(Locale.US, "%.1f", volts))
                key = "volts"
            } else {
                val low = Obd.tyres?.minOrNull()
                if (low != null && low < (if (alertKey == "tyre") TYRE_LOW + 2 else TYRE_LOW)) {
                    text = getString(R.string.alert_tyre, low)
                    key = "tyre"
                }
            }

            // A fault code is announced once, for a while, when it first appears.
            val codes = Obd.codes
            if (codes != null) {
                val prefs = Options.prefs(this)
                val joined = codes.joinToString(" ")
                if (joined != prefs.getString(PREF_CODES_SEEN, "")) {
                    prefs.edit().putString(PREF_CODES_SEEN, joined).apply()
                    if (codes.isNotEmpty()) {
                        codeText = getString(R.string.alert_fault, joined)
                        codeUntil = now + CODE_SHOW_MS
                        if (alertKey == "code") alertKey = ""
                    }
                }
            }
            if (text == null && now < codeUntil) {
                text = codeText
                key = "code"
            }
        }
        if (text == null || !Settings.canDrawOverlays(this)) {
            alertKey = ""
            removeAlert()
            return
        }
        if (key != alertKey) {
            alertKey = key
            beep()
        }
        showAlert(text)
    }

    private fun showAlert(text: String) {
        var view = alert
        if (view == null) {
            u = screenSize().second / 100f
            view = TextView(this)
            view.setTextColor(0xFFFFFFFF.toInt())
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 5f)
            view.gravity = Gravity.CENTER
            val pad = (u * 2.2f).roundToInt()
            view.setPadding(pad * 2, pad / 2, pad * 2, pad / 2)
            val shape = GradientDrawable()
            shape.cornerRadius = u * 6f
            shape.setColor(CHIP_COLOR)
            view.background = shape
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            // Under the speed chip, so both can show at once.
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            params.y = (u * 13f).roundToInt()
            params.title = "Masar car warning"
            try {
                wm.addView(view, params)
                alert = view
            } catch (ex: Exception) {
                return
            }
        }
        if (view.text.toString() != text) view.text = text
    }

    private fun removeAlert() {
        val view = alert ?: return
        alert = null
        removeWindow(view)
    }

    private fun removeChip() {
        val view = chip ?: return
        chip = null
        removeWindow(view)
    }

    // ---------------------------------------------------------------- shared

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

        /** Bar height, button width and icon sizes, in percent of screen height. On 1200x720 the bar is 76 px tall. */
        private const val BAR_TALL = 10.5f
        private const val BUTTON_WIDE = 13f
        private const val ICON = 5.6f
        private const val ICON_HOME = 7f
        private const val ICON_APP = 6.4f
        private const val BAR_COLOR = 0xF5111418.toInt()
        private const val CHIP_COLOR = 0xF2D32F2F.toInt()

        // Car warnings. A warm V8 runs up to about 105°C; these are well past normal.
        private const val COOLANT_HOT = 113
        private const val TRANS_HOT = 120
        private const val VOLTS_LOW = 12.0f
        private const val VOLTS_HIGH = 15.5f
        /** psi. The door placard is about 35; this is a properly soft tyre. */
        private const val TYRE_LOW = 26
        private const val CODE_SHOW_MS = 30_000L
        private const val PREF_CODES_SEEN = "codes_seen"

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

        /** How long the bar or pill stays after the last touch. */
        private const val SHOW_MS = 4000L
        private const val FADE_IN_MS = 120L

        const val MODE_BAR = "bar"
        const val MODE_PILL = "pill"
        const val MODE_OFF = "off"

        const val PREF_MODE = "handle_mode"
        /** The on/off switch of earlier versions; still read so "off" stays off. */
        private const val PREF_ENABLED_OLD = "handle_on"
        /** Tiles 2 to 4, written by MainActivity: "bar_app_0" … "bar_app_2". */
        const val PREF_BAR_APP = "bar_app_"
        const val BAR_APPS = 3

        private var current: HandleService? = null

        /** True while Masar's own window is on screen. */
        var homeVisible = false
            set(value) {
                field = value
                current?.update()
                if (value) current?.removeChip()
            }

        fun mode(context: Context): String {
            val prefs = context.getSharedPreferences("masar", Context.MODE_PRIVATE)
            val saved = prefs.getString(PREF_MODE, null)
            if (saved != null && (saved == MODE_BAR || saved == MODE_PILL || saved == MODE_OFF)) return saved
            return if (prefs.getBoolean(PREF_ENABLED_OLD, true)) MODE_BAR else MODE_OFF
        }

        fun enabled(context: Context): Boolean = mode(context) != MODE_OFF

        /** Turned on in Masar, and the system lets us draw over other apps. */
        fun allowed(context: Context): Boolean = enabled(context) && Settings.canDrawOverlays(context)
    }
}
