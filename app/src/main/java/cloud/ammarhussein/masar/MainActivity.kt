package cloud.ammarhussein.masar

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.location.Geocoder
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One launchable app: what the tiles and the drawer show. */
class AppEntry(val label: String, val component: ComponentName, val icon: Drawable)

/**
 * The whole launcher is this one activity, with two pages side by side.
 *
 * Page 1, home. Left: clock, date, next prayer, GPS speed and heading, and
 * three status lines. Right: eight or twelve large tiles, the last of which
 * opens "All apps", and under them a strip for whatever is playing.
 *
 * Page 2, drive: a compass with the trip under it, a small map that follows
 * the car, and the weather. Swipe sideways or tap the dots to change page.
 *
 * Long-press the clock for the settings menu: home handle, speed alert, tile
 * count, accent colour, background photo, night dimming, prayer times and
 * language. The choices themselves live in [Options].
 *
 * Every size is derived from the screen height (see [applySizes]), so the layout
 * looks the same on any head unit resolution or density.
 */
class MainActivity : Activity(), DashCam.Listener {

    private lateinit var prefs: SharedPreferences
    private lateinit var home: View
    private lateinit var panel: View
    private lateinit var drawer: View
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var speedBox: View
    private lateinit var speed: TextView
    private lateinit var heading: TextView
    private lateinit var unit: TextView
    private lateinit var statusGps: TextView
    private lateinit var statusNet: TextView
    private lateinit var statusHandle: TextView
    private lateinit var grid: GridView

    private lateinit var page2: View
    private lateinit var page3: View
    private lateinit var page4: View
    private lateinit var camStatus: TextView
    private lateinit var camButton: TextView
    private lateinit var dashCam: DashCam
    /** Which camera pane fills the page: -1 for both side by side. */
    private var camBig = -1
    private val camPlaying = BooleanArray(2)
    /** 0 live view, 1 the clip list, 2 one clip playing. */
    private var camMode = CAM_LIVE
    /** Set when the camera is being opened to go straight to the clip list. */
    private var camWantClips = false
    private var camClipsLoaded = false
    private var camTab = 0
    private val camClips = arrayOf<List<Clip>>(emptyList(), emptyList())
    private var clipPlayer: ExoPlayer? = null
    private lateinit var pages: List<View>
    private lateinit var dots: List<View>
    private lateinit var carStatus: TextView
    private lateinit var carGauge: GaugeView
    private lateinit var carSpeed: TextView
    private lateinit var carCodes: TextView
    private lateinit var carClear: TextView
    private lateinit var carTrip: TextView
    private lateinit var carService: TextView
    private lateinit var carStrip: View
    private lateinit var stripCoolant: TextView
    private lateinit var stripTrans: TextView
    private lateinit var stripFuel: TextView
    /** The six reading cells on the Car page: container, label, value. */
    private val carCells = ArrayList<Triple<View, TextView, TextView>>()
    private lateinit var compass: CompassView
    private lateinit var coords: TextView
    private lateinit var altitude: TextView
    private lateinit var map: TileMapView
    private lateinit var weatherPlace: TextView
    private lateinit var weatherSymbol: TextView
    private lateinit var weatherTemp: TextView
    private lateinit var weatherCond: TextView
    private lateinit var weatherDetail: TextView
    private lateinit var weatherNote: TextView
    private lateinit var forecast: LinearLayout
    private lateinit var trip: TextView
    private lateinit var prayer: TextView
    private lateinit var nightScrim: View
    private lateinit var wallpaper: ImageView
    private lateinit var wallpaperShade: View
    private lateinit var nowPlaying: View
    private lateinit var npArt: ImageView
    private lateinit var npTitle: TextView
    private lateinit var npArtist: TextView
    private lateinit var npPlay: ImageView

    /** Tiles on the home page: 8 or 12. The last one is always "All apps". */
    private var slots = 8
    private var accent = 0
    private lateinit var compass8: Array<String>

    private var controller: MediaController? = null
    private var phoneListening = false
    private var mediaListening = false

    private var prayerDay: PrayerTimes.Day? = null
    private var prayerKey = ""
    private var positionSavedAt = 0L

    private val tiles = ArrayList<View>()
    private var apps: List<AppEntry> = emptyList()
    private var appsLoaded = false

    /** One percent of the home screen height, in pixels. All sizes are multiples of it. */
    private var u = 6f

    private val ui = Handler(Looper.getMainLooper())
    private var lastFixAt = 0L
    private var satsUsed = -1
    private var gpsListening = false
    private var netRegistered = false
    private var online = false

    /** 0 is home, 1 is the drive page, 2 is the car page. */
    private var page = 0
    private var downX = 0f
    private var downY = 0f
    private var swiping = false

    private var lastLocation: Location? = null
    /** Direction of travel in degrees, or null until the car has moved with a GPS fix. */
    private var headingDeg: Float? = null

    private var weather: WeatherReport? = null
    private var weatherPlaceName = ""
    private var weatherAt = 0L
    private var weatherLat = 0.0
    private var weatherLon = 0.0
    private var weatherBusy = false
    private var weatherTriedAt = 0L

    // ---------------------------------------------------------------- lifecycle

    /** Everything is drawn in the language chosen in the settings menu. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Options.localized(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Options.prefs(this)
        accent = Options.accent(prefs)
        compass8 = resources.getStringArray(R.array.compass_8)
        Trip.load(prefs)

        home = findViewById(R.id.home)
        panel = findViewById(R.id.panel)
        drawer = findViewById(R.id.drawer)
        clock = findViewById(R.id.clock)
        date = findViewById(R.id.date)
        speedBox = findViewById(R.id.speedBox)
        speed = findViewById(R.id.speed)
        heading = findViewById(R.id.heading)
        unit = findViewById(R.id.unit)
        statusGps = findViewById(R.id.statusGps)
        statusNet = findViewById(R.id.statusNet)
        statusNet.setOnClickListener { reconnectWifi() }
        statusHandle = findViewById(R.id.statusHandle)
        grid = findViewById(R.id.grid)

        page2 = findViewById(R.id.page2)
        page3 = findViewById(R.id.page3)
        page4 = findViewById(R.id.page4)
        pages = listOf(home, page2, page3, page4)
        dots = listOf<View>(findViewById(R.id.dot0), findViewById(R.id.dot1), findViewById(R.id.dot2), findViewById(R.id.dot3))
        camStatus = findViewById(R.id.camStatus)
        camButton = findViewById(R.id.camButton)
        dashCam = DashCam(this, this)
        camButton.setOnClickListener { if (dashCam.isOpen) dashCam.close() else openCamera() }
        camButton.setOnLongClickListener {
            cameraSetup(false)
            true
        }
        val frontBox = findViewById<View>(R.id.camFrontBox)
        val rearBox = findViewById<View>(R.id.camRearBox)
        frontBox.setOnClickListener { camZoom(0) }
        rearBox.setOnClickListener { camZoom(1) }
        // Keep each picture 16:9 inside its box, whatever shape the box is.
        for ((box, id) in listOf(frontBox to R.id.camFront, rearBox to R.id.camRear)) {
            val video = findViewById<View>(id)
            box.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                val w = right - left - box.paddingLeft - box.paddingRight
                val h = bottom - top - box.paddingTop - box.paddingBottom
                if (w <= 0 || h <= 0) return@addOnLayoutChangeListener
                val fitW = min(w, h * 16 / 9)
                val fitH = fitW * 9 / 16
                val params = video.layoutParams
                if (params.width != fitW || params.height != fitH) {
                    params.width = fitW
                    params.height = fitH
                    video.post { video.layoutParams = params }
                }
            }
        }
        findViewById<View>(R.id.camClips).setOnClickListener { toggleClips() }
        findViewById<View>(R.id.camTabNormal).setOnClickListener { showClipTab(0) }
        findViewById<View>(R.id.camTabEvent).setOnClickListener { showClipTab(1) }
        findViewById<View>(R.id.camPlayerBack).setOnClickListener { stopClipPlayer(); camShow(CAM_CLIPS) }
        val clipList = findViewById<ListView>(R.id.camList)
        clipList.adapter = clipAdapter
        clipList.setOnItemClickListener { _, _, position, _ ->
            (camClips[camTab].getOrNull(position))?.let { playClip(it) }
        }
        onCamState(DashCam.CLOSED, -1)
        carStatus = findViewById(R.id.carStatus)
        carGauge = findViewById(R.id.carGauge)
        carSpeed = findViewById(R.id.carSpeed)
        carCodes = findViewById(R.id.carCodes)
        carClear = findViewById(R.id.carClear)
        carTrip = findViewById(R.id.carTrip)
        carService = findViewById(R.id.carService)
        val tripBox = findViewById<View>(R.id.carTripBox)
        tripBox.setOnClickListener { historyDialog() }
        tripBox.setOnLongClickListener {
            endTripDialog()
            true
        }
        findViewById<View>(R.id.carServiceBox).setOnClickListener { serviceDialog() }
        carStrip = findViewById(R.id.carStrip)
        stripCoolant = findViewById(R.id.stripCoolant)
        stripTrans = findViewById(R.id.stripTrans)
        stripFuel = findViewById(R.id.stripFuel)
        carStrip.setOnClickListener { showPage(2) }
        buildCarCells()
        carStatus.setOnClickListener { chooseDongle() }
        findViewById<View>(R.id.carCheck).setOnClickListener { Obd.readCodes() }
        carClear.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.car_clear_ask)
                .setPositiveButton(R.string.car_clear) { _, _ -> Obd.clearCodes() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        compass = findViewById(R.id.compass)
        coords = findViewById(R.id.coords)
        altitude = findViewById(R.id.altitude)
        map = findViewById(R.id.map)
        weatherPlace = findViewById(R.id.weatherPlace)
        weatherSymbol = findViewById(R.id.weatherSymbol)
        weatherTemp = findViewById(R.id.weatherTemp)
        weatherCond = findViewById(R.id.weatherCond)
        weatherDetail = findViewById(R.id.weatherDetail)
        weatherNote = findViewById(R.id.weatherNote)
        forecast = findViewById(R.id.forecast)
        trip = findViewById(R.id.trip)
        prayer = findViewById(R.id.prayer)
        nightScrim = findViewById(R.id.nightScrim)
        wallpaper = findViewById(R.id.wallpaper)
        wallpaperShade = findViewById(R.id.wallpaperShade)
        nowPlaying = findViewById(R.id.nowPlaying)
        npArt = findViewById(R.id.npArt)
        npTitle = findViewById(R.id.npTitle)
        npArtist = findViewById(R.id.npArtist)
        npPlay = findViewById(R.id.npPlay)

        clock.setOnLongClickListener {
            settingsMenu()
            true
        }
        prayer.setOnClickListener { prayerDialog() }
        trip.setOnClickListener { historyDialog() }
        trip.setOnLongClickListener {
            endTripDialog()
            true
        }
        findViewById<View>(R.id.npPrev).setOnClickListener { controller?.transportControls?.skipToPrevious() }
        findViewById<View>(R.id.npNext).setOnClickListener { controller?.transportControls?.skipToNext() }
        npPlay.setOnClickListener {
            val active = controller ?: return@setOnClickListener
            if (active.playbackState?.state == PlaybackState.STATE_PLAYING) {
                active.transportControls.pause()
            } else {
                active.transportControls.play()
            }
        }
        findViewById<View>(R.id.npText).setOnClickListener { openPlayer() }

        compass.hint = getString(R.string.compass_hint)
        map.waitingText = getString(R.string.map_wait_gps)
        map.noDataText = getString(R.string.map_no_data)
        map.zoom = prefs.getInt("map_zoom", TileMapView.DEFAULT_ZOOM)
        map.setOnClickListener { openMap() }
        findViewById<View>(R.id.zoomIn).setOnClickListener { zoomMap(1) }
        findViewById<View>(R.id.zoomOut).setOnClickListener { zoomMap(-1) }
        findViewById<View>(R.id.weatherCard).setOnClickListener { refreshWeather(true) }
        val pager = findViewById<View>(R.id.pager)
        pager.setOnClickListener { showPage((page + 1) % pages.size) }
        pager.setOnLongClickListener {
            toggleHandle()
            true
        }
        loadSavedWeather()
        showPage(0)
        applyAccent()
        loadWallpaper()
        buildTiles()

        grid.adapter = gridAdapter
        grid.setOnItemClickListener { _, _, position, _ -> launch(apps[position]) }
        grid.setOnItemLongClickListener { _, _, position, _ ->
            appMenu(apps[position])
            true
        }
        findViewById<View>(R.id.drawerClose).setOnClickListener { showDrawer(false) }

        val askForLocation = View.OnClickListener {
            if (!hasLocationPermission()) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            }
        }
        speedBox.setOnClickListener(askForLocation)
        statusGps.setOnClickListener(askForLocation)
        // The handle's line: a tap fixes a missing permission, a long-press is the on/off switch.
        statusHandle.setOnClickListener {
            if (HandleService.enabled(this) && !Settings.canDrawOverlays(this)) askForOverlay()
        }
        statusHandle.setOnLongClickListener {
            toggleHandle()
            true
        }

        home.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                ui.post { applySizes() }
            }
        }

        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_PACKAGE_ADDED)
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED)
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED)
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED)
        filter.addDataScheme("package")
        registerReceiver(packageReceiver, filter)

        setNet(NET_OFFLINE)
        updateGpsStatus()
        loadApps()
    }

    override fun onStart() {
        super.onStart()
        HandleService.homeVisible = true
        startGps()
        if (!netRegistered) {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.registerDefaultNetworkCallback(netCallback)
                netRegistered = true
            } catch (ex: Exception) {
                // Leave the indicator on "Offline"
            }
        }
        startMedia()
        Obd.start(this)
        dashCam.tidy(prefs.getString("cam_ssid", "70mai") ?: "70mai")
        try {
            registerReceiver(phoneReceiver, IntentFilter(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED))
            phoneListening = true
        } catch (ex: Exception) {
            // No Bluetooth on this unit
        }
        ui.removeCallbacks(carTick)
        ui.post(carTick)
        ui.removeCallbacks(staleCheck)
        ui.post(staleCheck)
    }

    /** Also runs on Home while already in front, so a permission granted over adb takes effect then. */
    override fun onResume() {
        super.onResume()
        syncHandle()
        updateHandleStatus()
        // Once per start of the unit, if asked to: go straight to CarPlay.
        if (!autoOpened) {
            autoOpened = true
            if (Options.autoCarPlay(prefs)) ui.postDelayed({ openCarPlay() }, 2500)
        }
    }

    /** Opens the CarPlay dongle app, but only from Masar's own screen: never over another app. */
    private fun openCarPlay() {
        if (!HandleService.homeVisible || !Options.autoCarPlay(prefs)) return
        val launcher = packageManager.getLaunchIntentForPackage(AUTOKIT) ?: return
        open(launcher, getString(R.string.carplay))
    }

    /** A phone (anything but the OBD dongle) connecting over Bluetooth is the cue to open CarPlay. */
    private val phoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getParcelableExtra<android.bluetooth.BluetoothDevice>(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
            val name = try {
                device?.name ?: ""
            } catch (ex: SecurityException) {
                ""
            }
            if (name.contains("OBD", ignoreCase = true) || name == Obd.deviceName) return
            ui.postDelayed({ openCarPlay() }, 1500)
        }
    }

    override fun onStop() {
        super.onStop()
        HandleService.homeVisible = false
        stopGps()
        stopMedia()
        stopClipPlayer()
        dashCam.close()
        ui.removeCallbacks(carTick)
        if (phoneListening) {
            phoneListening = false
            try {
                unregisterReceiver(phoneReceiver)
            } catch (ex: Exception) {
                // Already gone
            }
        }
        if (netRegistered) {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(netCallback)
            } catch (ex: Exception) {
                // Already unregistered
            }
            netRegistered = false
        }
        ui.removeCallbacks(staleCheck)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(packageReceiver)
        } catch (ex: Exception) {
            // Was never registered
        }
        super.onDestroy()
    }

    /** The Home button while we're already in front: go back to the tiles. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        showDrawer(false)
        showPage(0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // A launcher has nowhere to go back to; Back closes the drawer, then
        // returns to the first page.
        if (drawer.visibility == View.VISIBLE) showDrawer(false) else showPage(0)
    }

    /**
     * A sideways swipe anywhere changes page. Once a drag is clearly horizontal
     * the views underneath get a cancel, so the swipe doesn't also press a tile.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (drawer.visibility == View.VISIBLE) return super.dispatchTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                swiping = false
            }
            MotionEvent.ACTION_MOVE -> if (!swiping) {
                val dx = abs(event.x - downX)
                val dy = abs(event.y - downY)
                if (dx > u * 5f && dx > dy * 1.5f) {
                    swiping = true
                    val cancel = MotionEvent.obtain(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                }
            }
            MotionEvent.ACTION_UP -> if (swiping) {
                swiping = false
                val dx = event.x - downX
                if (abs(dx) > u * 12f) showPage((page + if (dx < 0) 1 else -1).coerceIn(0, pages.size - 1))
                return true
            }
            MotionEvent.ACTION_CANCEL -> swiping = false
        }
        return if (swiping) true else super.dispatchTouchEvent(event)
    }

    private fun showPage(index: Int) {
        val from = page
        page = index
        // INVISIBLE rather than GONE: hidden pages keep their size, which
        // applySizes() reads.
        for (i in pages.indices) pages[i].visibility = if (i == index) View.VISIBLE else View.INVISIBLE
        val shown = pages[index]
        if (index != from) {
            shown.animate().cancel()
            shown.alpha = 0f
            shown.translationX = (if (index < from) -1 else 1) * u * 8f
            shown.animate().alpha(1f).translationX(0f).setDuration(160).start()
        }
        val idle = getColor(R.color.idle)
        for (i in dots.indices) dots[i].backgroundTintList = ColorStateList.valueOf(if (i == index) accent else idle)
        if (index == 1) refreshWeather(false)
        if (index == 2) renderCar()
        // Leaving the camera page gives the Wi-Fi back to the hotspot.
        if (index != 3 && from == 3) {
            stopClipPlayer()
            dashCam.close()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startGps()
    }

    // ---------------------------------------------------------------- home handle

    /**
     * Starts Masar's service, or tells the running one that a setting changed.
     * It always runs: besides the home handle it keeps the trip and the speed
     * alert. If the handle is on but the overlay permission is missing, opens
     * the system screen for it, but only once by itself; after that only when
     * the handle is turned on by hand.
     */
    private fun syncHandle() {
        try {
            startForegroundService(Intent(this, HandleService::class.java))
        } catch (ex: Exception) {
            // The system refused; the floating dot in AutoKit still works.
        }
        if (HandleService.enabled(this) && !Settings.canDrawOverlays(this) &&
            !prefs.getBoolean(PREF_HANDLE_ASKED, false)
        ) {
            prefs.edit().putBoolean(PREF_HANDLE_ASKED, true).apply()
            askForOverlay()
        }
    }

    /** Steps the home handle through its looks: bar, pill, off, and round again. */
    private fun toggleHandle() {
        setHandleMode(
            when (HandleService.mode(this)) {
                HandleService.MODE_BAR -> HandleService.MODE_PILL
                HandleService.MODE_PILL -> HandleService.MODE_OFF
                else -> HandleService.MODE_BAR
            }
        )
    }

    private fun setHandleMode(next: String) {
        prefs.edit().putString(HandleService.PREF_MODE, next).apply()
        updateHandleStatus()
        if (next != HandleService.MODE_OFF && !Settings.canDrawOverlays(this)) {
            askForOverlay()
            return
        }
        val message = when (next) {
            HandleService.MODE_BAR -> R.string.handle_bar_on
            HandleService.MODE_PILL -> R.string.handle_pill_on
            else -> R.string.handle_off
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        syncHandle()
    }

    /** The third status line: whether the home handle is on, off, or waiting for its permission. */
    private fun updateHandleStatus() {
        if (!HandleService.enabled(this)) {
            setStatus(statusHandle, getString(R.string.handle_status_off), R.color.idle)
        } else if (!Settings.canDrawOverlays(this)) {
            setStatus(statusHandle, getString(R.string.handle_status_permission), R.color.accent)
        } else if (HandleService.mode(this) == HandleService.MODE_PILL) {
            setStatus(statusHandle, getString(R.string.handle_status_pill), R.color.ok)
        } else {
            setStatus(statusHandle, getString(R.string.handle_status_bar), R.color.ok)
        }
    }

    /** The system "Display over other apps" screen for Masar, or its app info if that is missing. */
    private fun askForOverlay() {
        Toast.makeText(this, R.string.handle_need_permission, Toast.LENGTH_LONG).show()
        val pkg = Uri.parse("package:$packageName")
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg))
        } catch (ex: Exception) {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg), getString(R.string.app_name))
        }
    }

    // ---------------------------------------------------------------- look

    /** Paints everything that carries the accent colour. */
    private fun applyAccent() {
        accent = Options.accent(prefs)
        heading.setTextColor(accent)
        prayer.setTextColor(accent)
        compass.accent = accent
        carGauge.accent = accent
        camButton.backgroundTintList = ColorStateList.valueOf(accent)
        map.accent = accent
        showPage(page)
    }

    private fun wallpaperFile(): File = File(filesDir, WALLPAPER_FILE)

    /** Shows the background photo, if one was chosen, under a dark shade. */
    private fun loadWallpaper() {
        var bitmap: Bitmap? = null
        try {
            if (wallpaperFile().exists()) bitmap = BitmapFactory.decodeFile(wallpaperFile().path)
        } catch (ex: Throwable) {
            // Unreadable or too large: carry on with the plain background
        }
        wallpaper.setImageBitmap(bitmap)
        wallpaper.visibility = if (bitmap != null) View.VISIBLE else View.GONE
        wallpaperShade.visibility = wallpaper.visibility
    }

    private fun choosePhoto() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        try {
            startActivityForResult(pick, REQUEST_PHOTO)
        } catch (ex: Exception) {
            try {
                startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQUEST_PHOTO)
            } catch (ex2: Exception) {
                Toast.makeText(this, R.string.photo_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQUEST_PHOTO || resultCode != RESULT_OK || uri == null) return
        // Copy a screen-sized version into Masar's own storage, so the photo
        // stays even if the original is moved or the USB stick is pulled out.
        Thread {
            var saved = false
            try {
                val bounds = BitmapFactory.Options()
                bounds.inJustDecodeBounds = true
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                val options = BitmapFactory.Options()
                options.inSampleSize = 1
                while (max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 2600) options.inSampleSize *= 2
                val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                if (bitmap != null) {
                    FileOutputStream(wallpaperFile()).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                    saved = true
                }
            } catch (ex: Throwable) {
                // Reported below
            }
            runOnUiThread {
                if (saved) loadWallpaper() else Toast.makeText(this, R.string.photo_failed, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    /** Sunrise, sunset and the prayers for today at the car's position, or null if no position is known yet. */
    private fun today(): PrayerTimes.Day? {
        val lat: Double
        val lon: Double
        val here = lastLocation
        if (here != null) {
            lat = here.latitude
            lon = here.longitude
        } else {
            val saved = Options.savedPosition(prefs) ?: return null
            lat = saved[0]
            lon = saved[1]
        }
        val now = Calendar.getInstance()
        // Worked out again only on a new day or after a real change of place.
        val key = String.format(
            Locale.US, "%d-%d %.1f %.1f",
            now.get(Calendar.YEAR), now.get(Calendar.DAY_OF_YEAR), lat, lon
        )
        if (key != prayerKey || prayerDay == null) {
            prayerDay = try {
                PrayerTimes.forDay(now, lat, lon, TimeZone.getDefault())
            } catch (ex: Exception) {
                null
            }
            prayerKey = key
        }
        return prayerDay
    }

    private fun hoursNow(): Double {
        val now = Calendar.getInstance()
        return now.get(Calendar.HOUR_OF_DAY) + now.get(Calendar.MINUTE) / 60.0 + now.get(Calendar.SECOND) / 3600.0
    }

    /** A veil over the screen at night, so the unit doesn't glare. */
    private fun renderNight() {
        val dark = when (Options.night(prefs)) {
            Options.NIGHT_ALWAYS -> true
            Options.NIGHT_OFF -> false
            else -> {
                val day = today()
                val hours = hoursNow()
                // With no position yet, assume a plain 6-to-6 day.
                val sunrise = day?.sunrise ?: 6.0
                val sunset = day?.maghrib ?: 18.0
                hours < sunrise || hours >= sunset
            }
        }
        val target = if (dark) NIGHT_ALPHA else 0f
        if (nightScrim.alpha != target) nightScrim.alpha = target
    }

    // ---------------------------------------------------------------- prayer times

    /** "5:08 PM" for a number of hours after today's midnight. */
    private fun clockTime(hours: Double): String {
        val at = Calendar.getInstance()
        at.set(Calendar.HOUR_OF_DAY, 0)
        at.set(Calendar.MINUTE, 0)
        at.set(Calendar.SECOND, 0)
        at.set(Calendar.MILLISECOND, 0)
        at.add(Calendar.MINUTE, (hours * 60.0).roundToInt())
        return DateFormat.getTimeFormat(this).format(at.time)
    }

    /** The line under the date: the next prayer, its time, and how long until it. */
    private fun renderPrayer() {
        val day = if (Options.prayerOn(prefs)) today() else null
        if (day == null) {
            prayer.visibility = View.GONE
            return
        }
        val names = resources.getStringArray(R.array.prayers)
        val times = day.prayers()
        val now = hoursNow()
        var index = times.indexOfFirst { it > now }
        // After Isha the next one is tomorrow's Fajr, near enough today's time plus a day.
        val at = if (index < 0) times[PrayerTimes.FAJR] + 24.0 else times[index]
        if (index < 0) index = PrayerTimes.FAJR
        val minutes = max(0, ((at - now) * 60.0).roundToInt())
        val left = String.format(Locale.US, "%d:%02d", minutes / 60, minutes % 60)
        prayer.text = getString(R.string.prayer_next, names[index], clockTime(at), left)
        prayer.visibility = View.VISIBLE
    }

    private fun prayerDialog() {
        val day = today() ?: return
        val names = resources.getStringArray(R.array.prayers)
        val text = StringBuilder()
        fun line(name: String, hours: Double) {
            text.append(name).append("   ").append(clockTime(hours)).append('\n')
        }
        line(names[PrayerTimes.FAJR], day.fajr)
        line(getString(R.string.prayer_sunrise), day.sunrise)
        line(names[PrayerTimes.DHUHR], day.dhuhr)
        line(names[PrayerTimes.ASR], day.asr)
        line(names[PrayerTimes.MAGHRIB], day.maghrib)
        line(names[PrayerTimes.ISHA], day.isha)
        text.append('\n').append(getString(R.string.prayer_note))
        AlertDialog.Builder(this)
            .setTitle(R.string.prayer_title)
            .setMessage(text)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    // ---------------------------------------------------------------- trip

    private fun renderTrip() {
        val minutes = (Trip.movingMs / 60_000L).toInt()
        trip.text = getString(
            R.string.trip_line,
            String.format(Locale.US, "%.1f", Trip.meters / 1000.0),
            String.format(Locale.US, "%d:%02d", minutes / 60, minutes % 60),
            Trip.averageKmh(), Trip.topKmh
        )
    }

    private fun km(meters: Double): String = String.format(Locale.US, "%.1f", meters / 1000.0)

    private fun hoursMinutes(ms: Long): String {
        val minutes = (ms / 60_000L).toInt()
        return String.format(Locale.US, "%d:%02d", minutes / 60, minutes % 60)
    }

    private fun money(liters: Double): String = String.format(Locale.US, "%.2f", liters * Options.fuelPrice(prefs))

    /** The trip block on the Car page: the Drive page's two lines, plus fuel and cost once the dongle has counted some. */
    private fun renderCarTrip() {
        val text = StringBuilder(
            getString(R.string.car_trip, km(Trip.meters), hoursMinutes(Trip.movingMs), Trip.averageKmh(), Trip.topKmh)
        )
        if (Trip.liters > 0.01) {
            val per100 = Trip.per100()?.let { String.format(Locale.US, "%.1f", it) } ?: "–"
            text.append('\n').append(
                getString(
                    R.string.car_trip_fuel,
                    String.format(Locale.US, "%.1f", Trip.liters), per100,
                    money(Trip.liters), getString(R.string.currency)
                )
            )
        }
        if (carTrip.text.toString() != text.toString()) carTrip.text = text
    }

    private fun endTripDialog() {
        AlertDialog.Builder(this)
            .setMessage(R.string.trip_end)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Trip.reset(prefs)
                renderTrip()
                renderCarTrip()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Finished trips, newest first. */
    private fun historyDialog() {
        val past = Trip.history(prefs)
        val builder = AlertDialog.Builder(this).setTitle(R.string.history_title)
        val day = SimpleDateFormat("d MMM", Locale.getDefault())
        val time = DateFormat.getTimeFormat(this)
        // First line: where the car was last seen, which is where it is parked.
        val parked = if (Trip.lastSeen != 0L && (Trip.lastLat != 0.0 || Trip.lastLon != 0.0)) {
            val seen = Date(Trip.lastSeen)
            getString(R.string.parked_line, day.format(seen) + " " + time.format(seen))
        } else {
            null
        }
        if (past.isEmpty() && parked == null) {
            builder.setMessage(R.string.history_empty)
        } else {
            val offset = if (parked == null) 0 else 1
            val lines = Array(past.size + offset) { row ->
                if (row < offset) return@Array parked ?: ""
                val i = row - offset
                val trip = past[i]
                val started = Date(trip.start)
                var line = getString(
                    R.string.history_line,
                    day.format(started) + " " + time.format(started), km(trip.meters), hoursMinutes(trip.movingMs)
                )
                if (trip.liters > 0.01) {
                    line += getString(
                        R.string.history_fuel,
                        String.format(Locale.US, "%.1f", trip.liters), money(trip.liters), getString(R.string.currency)
                    )
                }
                line
            }
            // A tap shows the place on a map: the parked car, or where that trip ended.
            builder.setItems(lines) { _, row ->
                if (row < offset) {
                    showOnMap(Trip.lastLat, Trip.lastLon)
                } else {
                    val trip = past[row - offset]
                    showOnMap(trip.lat, trip.lon)
                }
            }
        }
        builder.setPositiveButton(R.string.close, null).show()
    }

    private fun showOnMap(lat: Double, lon: Double) {
        if (lat == 0.0 && lon == 0.0) return
        val place = String.format(Locale.US, "%.6f,%.6f", lat, lon)
        val uri = Uri.parse("geo:$place?q=$place")
        open(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), getString(R.string.map_title))
    }

    // ---------------------------------------------------------------- service

    /** Oil life from the car if it reports it, then the reminders Masar counts by distance. */
    private fun renderService() {
        val names = resources.getStringArray(R.array.services)
        val text = StringBuilder()
        var due = false
        val oil = Obd.oilLife
        if (oil != null) {
            text.append(getString(R.string.svc_oil_life, oil))
            if (oil <= 10) due = true
        }
        for (i in names.indices) {
            // The car's own oil monitor replaces the distance count for oil.
            if (i == 0 && oil != null) continue
            val left = Options.serviceLeftKm(prefs, i)
            if (text.isNotEmpty()) text.append('\n')
            if (left <= 0) {
                text.append(getString(R.string.svc_due, names[i]))
                due = true
            } else {
                text.append(getString(R.string.svc_left, names[i], String.format(Locale.US, "%,d", left)))
            }
        }
        if (carService.text.toString() != text.toString()) carService.text = text
        carService.setTextColor(getColor(if (due) R.color.bad else R.color.text))
    }

    private fun serviceDialog() {
        val names = resources.getStringArray(R.array.services)
        val items = Array(names.size) { getString(R.string.svc_done, names[it]) }
        AlertDialog.Builder(this)
            .setTitle(R.string.svc_pick)
            .setItems(items) { _, which ->
                Options.serviceDone(prefs, which)
                renderService()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- now playing

    private fun hasMediaAccess(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return enabled.contains("$packageName/")
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> pickSession(list) }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            renderMedia()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            renderMedia()
        }

        override fun onSessionDestroyed() {
            pickSession(null)
        }
    }

    /** Listens for media sessions. Without notification access the strip simply stays hidden. */
    private fun startMedia() {
        if (mediaListening) return
        try {
            val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val who = ComponentName(this, MediaService::class.java)
            manager.addOnActiveSessionsChangedListener(sessionsListener, who)
            mediaListening = true
            pickSession(manager.getActiveSessions(who))
        } catch (ex: Exception) {
            pickSession(null)
        }
    }

    private fun stopMedia() {
        if (mediaListening) {
            try {
                val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                manager.removeOnActiveSessionsChangedListener(sessionsListener)
            } catch (ex: Exception) {
                // Already removed
            }
            mediaListening = false
        }
        pickSession(null)
    }

    /** Follows the session that is playing, or failing that the most recent one. */
    private fun pickSession(list: List<MediaController>?) {
        val next = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list?.firstOrNull()
        val old = controller
        if (old?.sessionToken != next?.sessionToken || next == null) {
            try {
                old?.unregisterCallback(mediaCallback)
            } catch (ex: Exception) {
                // The session is already gone
            }
            controller = next
            try {
                next?.registerCallback(mediaCallback, ui)
            } catch (ex: Exception) {
                controller = null
            }
        }
        renderMedia()
    }

    private fun renderMedia() {
        val active = controller
        val data = try {
            active?.metadata
        } catch (ex: Exception) {
            null
        }
        val title = data?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: data?.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (active == null || title.isNullOrBlank()) {
            nowPlaying.visibility = View.GONE
            return
        }
        npTitle.text = title
        val artist = data?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: data?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        npArtist.text = artist
        npArtist.visibility = if (artist.isBlank()) View.GONE else View.VISIBLE
        val art = data?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: data?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: data?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (art != null) {
            npArt.setImageBitmap(art)
        } else {
            // No cover: the player's own icon stands in.
            npArt.setImageDrawable(apps.firstOrNull { it.component.packageName == active.packageName }?.icon)
        }
        val playing = active.playbackState?.state == PlaybackState.STATE_PLAYING
        npPlay.setImageResource(if (playing) R.drawable.ic_np_pause else R.drawable.ic_np_play)
        nowPlaying.visibility = View.VISIBLE
    }

    /** A tap on the title opens the player itself. */
    private fun openPlayer() {
        val active = controller ?: return
        try {
            val session = active.sessionActivity
            if (session != null) {
                session.send()
                return
            }
        } catch (ex: Exception) {
            // Fall through to the app's own launcher entry
        }
        val launcher = packageManager.getLaunchIntentForPackage(active.packageName) ?: return
        open(launcher, active.packageName)
    }

    // ---------------------------------------------------------------- car page

    /** Three rows of two cells; [renderCar] fills as many as the car has readings for. */
    private fun buildCarCells() {
        val grid = findViewById<LinearLayout>(R.id.carGrid)
        val margin = (resources.displayMetrics.density * 6f).roundToInt()
        val pad = (resources.displayMetrics.density * 14f).roundToInt()
        for (r in 0 until 3) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.isBaselineAligned = false
            grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            for (c in 0 until 2) {
                val cell = LinearLayout(this)
                cell.orientation = LinearLayout.VERTICAL
                cell.gravity = Gravity.CENTER_VERTICAL
                cell.setBackgroundResource(R.drawable.card_bg)
                cell.setPadding(pad, 0, pad, 0)
                val label = TextView(this)
                label.setTextColor(getColor(R.color.text_dim))
                label.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                label.maxLines = 1
                val value = TextView(this)
                value.typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
                value.includeFontPadding = false
                value.maxLines = 2
                cell.addView(label)
                cell.addView(value)
                val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                params.setMargins(margin, margin, margin, margin)
                row.addView(cell, params)
                cell.visibility = View.INVISIBLE
                carCells.add(Triple(cell, label, value))
            }
        }
    }

    /** Redraws the Car page a few times a second while it is the one showing. */
    private val carTick = object : Runnable {
        override fun run() {
            if (page == 2) renderCar()
            if (page == 0) renderCarStrip()
            ui.postDelayed(this, 300)
        }
    }

    /** Coolant, transmission and fuel in one line on the home page, while the dongle is connected. */
    private fun renderCarStrip() {
        val coolant = Obd.coolant
        val trans = Obd.trans
        val fuel = Obd.fuel
        if (Obd.state != Obd.CONNECTED || (coolant == null && trans == null && fuel == null)) {
            if (carStrip.visibility != View.GONE) carStrip.visibility = View.GONE
            return
        }
        val text = getColor(R.color.text)
        val warn = Options.ACCENTS[0]
        val bad = getColor(R.color.bad)
        fun show(view: TextView, label: Int, value: String?, color: Int) {
            view.visibility = if (value == null) View.GONE else View.VISIBLE
            if (value != null) {
                val line = getString(R.string.strip_item, getString(label), value)
                if (view.text.toString() != line) view.text = line
                view.setTextColor(color)
            }
        }
        show(stripCoolant, R.string.car_coolant, coolant?.let { getString(R.string.car_degrees, it) },
            if (coolant != null && coolant >= 113) bad else if (coolant != null && coolant >= 108) warn else text)
        show(stripTrans, R.string.car_trans, trans?.let { getString(R.string.car_degrees, it) },
            if (trans != null && trans >= 120) bad else if (trans != null && trans >= 110) warn else text)
        show(stripFuel, R.string.car_fuel, fuel?.let { getString(R.string.car_percent, it) },
            if (fuel != null && fuel <= 8) bad else if (fuel != null && fuel <= 15) warn else text)
        if (carStrip.visibility != View.VISIBLE) carStrip.visibility = View.VISIBLE
    }

    private fun renderCar() {
        val name = Obd.deviceName
        when (Obd.state) {
            Obd.NO_BLUETOOTH -> setStatus(carStatus, getString(R.string.car_no_bluetooth), R.color.bad)
            Obd.NO_DEVICE -> setStatus(carStatus, getString(R.string.car_no_device), R.color.accent)
            Obd.CONNECTING -> setStatus(carStatus, getString(R.string.car_connecting, name), R.color.idle)
            Obd.CONNECTED -> setStatus(carStatus, getString(R.string.car_connected, name), R.color.ok)
            else -> setStatus(carStatus, getString(R.string.car_failed, name), R.color.bad)
        }
        carGauge.value = Obd.rpm
        carSpeed.text = Obd.speed?.toString() ?: "–"

        // Only what the car actually reports, most useful first; the first six get a cell.
        val text = getColor(R.color.text)
        val warn = Options.ACCENTS[0]
        val bad = getColor(R.color.bad)
        class Row(val label: String, val value: String, val color: Int, val small: Boolean = false)
        val rows = ArrayList<Row>()
        Obd.coolant?.let {
            rows.add(Row(getString(R.string.car_coolant), getString(R.string.car_degrees, it), if (it >= 113) bad else if (it >= 108) warn else text))
        }
        Obd.trans?.let {
            rows.add(Row(getString(R.string.car_trans), getString(R.string.car_degrees, it), if (it >= 120) bad else if (it >= 110) warn else text))
        }
        Obd.volts?.let {
            val shown = String.format(Locale.US, "%.1f", it)
            rows.add(Row(getString(R.string.car_battery), getString(R.string.car_volts, shown), if (it < 11.8f || it > 15.2f) bad else if (it < 12.3f) warn else text))
        }
        Obd.fuel?.let {
            // What is left in the tank at the long-run rate of use.
            val range = (Options.tankLiters(prefs) * it / 100.0 / Options.averageUse(prefs) * 100.0).roundToInt()
            rows.add(Row(getString(R.string.car_fuel_range, range), getString(R.string.car_percent, it), if (it <= 8) bad else if (it <= 15) warn else text))
        }
        Obd.litersPerHour?.let {
            // Per distance on the move, per hour at a standstill.
            val speed = Obd.speed ?: 0
            val shown = if (speed >= 10) {
                getString(R.string.car_use_100, String.format(Locale.US, "%.1f", min(it / speed * 100f, 99.9f)))
            } else {
                getString(R.string.car_use_hour, String.format(Locale.US, "%.1f", it))
            }
            rows.add(Row(getString(R.string.car_use), shown, text, small = true))
        }
        Obd.tyres?.let {
            // As the car sits: front pair on top, rear pair below.
            val low = it.minOrNull() ?: 0
            rows.add(Row(getString(R.string.car_tyres), "${it[0]}  ${it[1]}\n${it[3]}  ${it[2]}", if (low < 26) bad else if (low < 30) warn else text, small = true))
        }
        Obd.intake?.let { rows.add(Row(getString(R.string.car_intake), getString(R.string.car_degrees, it), text)) }
        Obd.load?.let { rows.add(Row(getString(R.string.car_load), getString(R.string.car_percent, it), text)) }
        for (i in carCells.indices) {
            val cell = carCells[i]
            val row = rows.getOrNull(i)
            cell.first.visibility = if (row == null) View.INVISIBLE else View.VISIBLE
            if (row != null) {
                if (cell.second.text.toString() != row.label) cell.second.text = row.label
                if (cell.third.text.toString() != row.value) cell.third.text = row.value
                cell.third.setTextColor(row.color)
                cell.third.maxLines = 2
                cell.third.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * (if (row.small) 5.6f else 8.4f))
            }
        }
        renderCarTrip()
        renderService()

        val codes = Obd.codes
        val checked = DateFormat.getTimeFormat(this).format(Date(Obd.codesAt))
        if (codes == null) {
            carCodes.setText(R.string.car_codes_wait)
            carCodes.setTextColor(getColor(R.color.text_dim))
        } else if (codes.isEmpty()) {
            carCodes.text = getString(R.string.car_codes_none, checked)
            carCodes.setTextColor(getColor(R.color.ok))
        } else {
            val named = codes.joinToString("\n") { code ->
                val what = Dtc.describe(code)
                if (what.isEmpty()) code else "$code · $what"
            }
            carCodes.text = getString(R.string.car_codes_some, named, checked)
            carCodes.setTextColor(bad)
        }
        carClear.visibility = if (codes.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /** Which paired Bluetooth device is the dongle. "Automatic" takes the one with OBD in its name. */
    private fun chooseDongle() {
        val paired = Obd.paired()
        if (paired.isNullOrEmpty()) {
            Toast.makeText(this, if (paired == null) R.string.car_no_bluetooth else R.string.obd_none_paired, Toast.LENGTH_LONG).show()
            open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), getString(R.string.app_name))
            return
        }
        val saved = prefs.getString(Obd.PREF_MAC, "") ?: ""
        val names = arrayOf(getString(R.string.obd_auto)) + paired.map { it.first } + getString(R.string.obd_pair_new)
        val current = paired.indexOfFirst { it.second == saved } + 1
        choose(R.string.obd_pick, names, current) {
            if (it == names.size - 1) {
                open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), getString(R.string.app_name))
            } else {
                prefs.edit().putString(Obd.PREF_MAC, if (it == 0) "" else paired[it - 1].second).apply()
                Obd.reconnect()
            }
        }
    }

    // ---------------------------------------------------------------- dash cam page

    /** Asks for the camera's Wi-Fi name and password the first time, then joins it. */
    private fun openCamera(live: Boolean = true) {
        if (prefs.getString("cam_pass", "").isNullOrEmpty()) {
            cameraSetup(true)
            return
        }
        if (!hasLocationPermission()) {
            // Android only lets an app pick a Wi-Fi network if it may see the ones nearby.
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            return
        }
        camPlaying[0] = false
        camPlaying[1] = false
        dashCam.open(
            prefs.getString("cam_ssid", "70mai") ?: "70mai",
            prefs.getString("cam_pass", "") ?: "",
            findViewById(R.id.camFront), findViewById(R.id.camRear),
            live
        )
    }

    // ---------------------------------------------------------------- clips

    /** Shows one of the camera page's three faces: live, the clip list, a clip playing. */
    private fun camShow(mode: Int) {
        camMode = mode
        findViewById<View>(R.id.camPanes).visibility = if (mode == CAM_LIVE) View.VISIBLE else View.GONE
        findViewById<View>(R.id.camListBox).visibility = if (mode == CAM_CLIPS) View.VISIBLE else View.GONE
        findViewById<View>(R.id.camPlayerBox).visibility = if (mode == CAM_PLAYING) View.VISIBLE else View.GONE
        (findViewById<TextView>(R.id.camClips)).setText(if (mode == CAM_LIVE) R.string.cam_clips else R.string.cam_live_btn)
    }

    /** The Clips/Live button: switch the camera page between the live view and the recordings. */
    private fun toggleClips() {
        if (!dashCam.isOpen) {
            // Open the camera straight into the clip list, without the live streams.
            camWantClips = true
            camClipsLoaded = false
            openCamera(live = false)
            return
        }
        if (camMode == CAM_LIVE) {
            dashCam.stopLive()
            camShow(CAM_CLIPS)
            if (!camClipsLoaded) fetchClips()
        } else {
            stopClipPlayer()
            camShow(CAM_LIVE)
            dashCam.startLive()
        }
    }

    /** Reads both clip lists off the main thread, with the camera's required gap between calls. */
    private fun fetchClips() {
        findViewById<TextView>(R.id.camListEmpty).setText(R.string.cam_list_loading)
        findViewById<View>(R.id.camListEmpty).visibility = View.VISIBLE
        Thread {
            val normal = try {
                DashcamClips.list("Normal")
            } catch (ex: Exception) {
                null
            }
            // The camera drops requests that come too close together.
            try {
                Thread.sleep(3000)
            } catch (ex: InterruptedException) {
                // Carry on
            }
            val event = try {
                DashcamClips.list("Event")
            } catch (ex: Exception) {
                null
            }
            runOnUiThread {
                if (camMode == CAM_LIVE) return@runOnUiThread
                camClips[0] = normal ?: emptyList()
                camClips[1] = event ?: emptyList()
                camClipsLoaded = normal != null || event != null
                showClipTab(camTab)
            }
        }.start()
    }

    private fun showClipTab(tab: Int) {
        camTab = tab
        findViewById<TextView>(R.id.camTabNormal).setTextColor(getColor(if (tab == 0) R.color.text else R.color.text_dim))
        findViewById<TextView>(R.id.camTabEvent).setTextColor(getColor(if (tab == 1) R.color.text else R.color.text_dim))
        clipAdapter.notifyDataSetChanged()
        val empty = findViewById<TextView>(R.id.camListEmpty)
        if (camClips[tab].isEmpty()) {
            empty.setText(if (camClipsLoaded) R.string.cam_list_empty else R.string.cam_list_fail)
            empty.visibility = View.VISIBLE
        } else {
            empty.visibility = View.GONE
        }
    }

    private val clipAdapter = object : BaseAdapter() {
        override fun getCount(): Int = camClips[camTab].size
        override fun getItem(position: Int): Any = camClips[camTab][position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_clip, parent, false)
            val clip = camClips[camTab][position]
            val time = view.findViewById<TextView>(R.id.clipTime)
            time.text = clip.time.ifEmpty { clip.name.substringAfterLast('/') }
            px(time, 3.8f)
            val size = view.findViewById<TextView>(R.id.clipSize)
            size.text = String.format(Locale.US, "%.0f MB", clip.sizeBytes / 1_000_000.0)
            px(size, 3.2f)
            val badge = view.findViewById<TextView>(R.id.clipBadge)
            if (clip.rear) {
                badge.setText(R.string.cam_rear)
                badge.setBackgroundColor(getColor(R.color.tile_stroke))
                badge.setTextColor(getColor(R.color.text))
                badge.visibility = View.VISIBLE
            } else {
                badge.visibility = View.GONE
            }
            return view
        }
    }

    private fun playClip(clip: Clip) {
        stopClipPlayer()
        camShow(CAM_PLAYING)
        val player = ExoPlayer.Builder(this).build()
        clipPlayer = player
        findViewById<PlayerView>(R.id.camPlayer).player = player
        player.setMediaItem(MediaItem.fromUri(clip.url))
        player.playWhenReady = true
        player.prepare()
    }

    private fun stopClipPlayer() {
        val player = clipPlayer ?: return
        clipPlayer = null
        try {
            findViewById<PlayerView>(R.id.camPlayer).player = null
            player.release()
        } catch (ex: Exception) {
            // Already released
        }
    }

    /** The camera's Wi-Fi name (or how it starts) and its password. Kept only on this unit. */
    private fun cameraSetup(thenOpen: Boolean) {
        val pad = (resources.displayMetrics.density * 20f).roundToInt()
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(pad, pad / 2, pad, 0)
        val name = android.widget.EditText(this)
        name.hint = getString(R.string.cam_ssid_hint)
        name.setText(prefs.getString("cam_ssid", "70mai"))
        name.setSingleLine()
        val pass = android.widget.EditText(this)
        pass.hint = getString(R.string.cam_pass_hint)
        pass.setText(prefs.getString("cam_pass", ""))
        pass.setSingleLine()
        pass.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        box.addView(name)
        box.addView(pass)
        AlertDialog.Builder(this)
            .setTitle(R.string.cam_setup_title)
            .setMessage(R.string.cam_setup_text)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val typed = pass.text.toString()
                if (typed.length < 8) {
                    Toast.makeText(this, R.string.cam_pass_short, Toast.LENGTH_LONG).show()
                } else {
                    prefs.edit()
                        .putString("cam_ssid", name.text.toString().trim().ifEmpty { "70mai" })
                        .putString("cam_pass", typed)
                        .apply()
                    if (thenOpen) openCamera()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** A tap on a picture makes it fill the page; another tap brings both back. */
    private fun camZoom(pane: Int) {
        camBig = if (camBig == pane) -1 else pane
        findViewById<View>(R.id.camFrontBox).visibility = if (camBig == 1) View.GONE else View.VISIBLE
        findViewById<View>(R.id.camRearBox).visibility = if (camBig == 0) View.GONE else View.VISIBLE
    }

    override fun onCamState(state: Int, pane: Int) {
        if (pane >= 0) camPlaying[pane] = state == DashCam.PLAYING
        val open = dashCam.isOpen
        if (!open) {
            camPlaying[0] = false
            camPlaying[1] = false
            camClipsLoaded = false
            camClips[0] = emptyList()
            camClips[1] = emptyList()
            stopClipPlayer()
            camShow(CAM_LIVE)
        } else if (state == DashCam.JOINED && camWantClips) {
            // Opened straight into the clip list.
            camWantClips = false
            camShow(CAM_CLIPS)
            if (!camClipsLoaded) fetchClips()
        }
        // A picture that is not playing is hidden, so no frozen last frame is left on screen.
        findViewById<View>(R.id.camFront).alpha = if (camPlaying[0]) 1f else 0f
        findViewById<View>(R.id.camRear).alpha = if (camPlaying[1]) 1f else 0f
        camButton.setText(if (open) R.string.cam_close else R.string.cam_open)
        when {
            state == DashCam.NOT_FOUND -> setStatus(camStatus, getString(R.string.cam_not_found), R.color.bad)
            state == DashCam.LOST -> setStatus(camStatus, getString(R.string.cam_lost), R.color.bad)
            !open -> setStatus(camStatus, getString(R.string.cam_idle), R.color.idle)
            state == DashCam.JOINING -> setStatus(camStatus, getString(R.string.cam_joining), R.color.accent)
            camPlaying[0] || camPlaying[1] -> setStatus(camStatus, getString(R.string.cam_live), R.color.ok)
            state == DashCam.NO_VIDEO && !camPlaying[0] && !camPlaying[1] ->
                setStatus(camStatus, getString(R.string.cam_no_video), R.color.bad)
            else -> setStatus(camStatus, getString(R.string.cam_starting), R.color.accent)
        }
    }

    // ---------------------------------------------------------------- settings

    /** A list to pick one of; [current] is ticked. */
    private fun choose(title: Int, labels: Array<String>, current: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                onPick(which)
            }
            .show()
    }

    private fun onOff(on: Boolean): String = getString(if (on) R.string.mode_on else R.string.mode_off)

    /** Long-press on the clock. Each line shows its current value. */
    private fun settingsMenu() {
        val modes = arrayOf(HandleService.MODE_BAR, HandleService.MODE_PILL, HandleService.MODE_OFF)
        val modeNames = arrayOf(getString(R.string.mode_bar), getString(R.string.mode_pill), getString(R.string.mode_off))
        val mode = max(0, modes.indexOf(HandleService.mode(this)))

        val limits = Options.SPEED_LIMITS
        val limitNames = Array(limits.size) {
            if (limits[it] == 0) getString(R.string.mode_off) else getString(R.string.speed_value, limits[it])
        }
        val limit = max(0, limits.indexOf(Options.speedLimit(prefs)))

        val rowNames = arrayOf(getString(R.string.rows_2), getString(R.string.rows_3))
        val rows = Options.rows(prefs) - 2

        val nightNames = arrayOf(getString(R.string.night_auto), getString(R.string.night_always), getString(R.string.mode_off))
        val night = Options.night(prefs)

        val langs = arrayOf("", "en", "ar")
        val langNames = arrayOf(getString(R.string.lang_system), "English", "العربية")
        val lang = max(0, langs.indexOf(Options.language(prefs)))

        val accentNames = resources.getStringArray(R.array.accents)
        val hasPhoto = wallpaperFile().exists()
        val prayerOn = Options.prayerOn(prefs)

        val items = arrayOf(
            getString(R.string.set_handle, modeNames[mode]),
            getString(R.string.set_speed, limitNames[limit]),
            getString(R.string.set_rows, rowNames[rows]),
            getString(R.string.set_accent),
            getString(R.string.set_photo, getString(if (hasPhoto) R.string.photo_set else R.string.photo_none)),
            getString(R.string.set_night, nightNames[night]),
            getString(R.string.set_prayer, onOff(prayerOn)),
            getString(R.string.set_lang, langNames[lang]),
            getString(R.string.set_media, if (hasMediaAccess()) onOff(true) else getString(R.string.media_need)),
            getString(R.string.set_keys, if (KeysService.instance != null) onOff(true) else getString(R.string.keys_need)),
            getString(R.string.set_obd, Obd.deviceName.ifEmpty { getString(R.string.obd_auto) }),
            getString(R.string.set_price, String.format(Locale.US, "%.2f", Options.fuelPrice(prefs)), getString(R.string.currency)),
            getString(R.string.set_autoplay, onOff(Options.autoCarPlay(prefs))),
            getString(R.string.set_report),
            getString(R.string.set_about, versionName())
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_title_version, versionName()))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> choose(R.string.settings_title, modeNames, mode) { setHandleMode(modes[it]) }
                    1 -> choose(R.string.settings_title, limitNames, limit) {
                        prefs.edit().putInt("speed_limit", limits[it]).apply()
                    }
                    2 -> choose(R.string.settings_title, rowNames, rows) {
                        prefs.edit().putInt("rows", it + 2).apply()
                        buildTiles()
                        applySizes()
                        renderTiles()
                    }
                    3 -> choose(R.string.set_accent, accentNames, Options.ACCENTS.indexOf(accent)) {
                        prefs.edit().putInt("accent", Options.ACCENTS[it]).apply()
                        applyAccent()
                        renderTiles()
                        syncHandle()
                    }
                    4 -> if (!hasPhoto) choosePhoto() else {
                        val photo = arrayOf(getString(R.string.photo_choose), getString(R.string.photo_remove))
                        AlertDialog.Builder(this)
                            .setItems(photo) { _, pick ->
                                if (pick == 0) choosePhoto() else {
                                    wallpaperFile().delete()
                                    loadWallpaper()
                                }
                            }
                            .show()
                    }
                    5 -> choose(R.string.settings_title, nightNames, night) {
                        prefs.edit().putInt("night", it).apply()
                        renderNight()
                    }
                    6 -> {
                        prefs.edit().putBoolean("prayer", !prayerOn).apply()
                        renderPrayer()
                    }
                    7 -> choose(R.string.settings_title, langNames, lang) {
                        if (it != lang) {
                            // commit, not apply: the new activity reads it straight away.
                            prefs.edit().putString("lang", langs[it]).commit()
                            stopService(Intent(this, HandleService::class.java))
                            recreate()
                        }
                    }
                    8 -> {
                        if (!hasMediaAccess()) Toast.makeText(this, R.string.media_hint, Toast.LENGTH_LONG).show()
                        open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), getString(R.string.app_name))
                    }
                    9 -> open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), getString(R.string.app_name))
                    10 -> chooseDongle()
                    11 -> priceDialog()
                    12 -> prefs.edit().putBoolean("auto_carplay", !Options.autoCarPlay(prefs)).apply()
                    14 -> aboutDialog()
                    13 -> AlertDialog.Builder(this)
                        .setTitle(R.string.set_report)
                        .setMessage(Obd.report.ifEmpty { getString(R.string.report_empty) })
                        .setPositiveButton(R.string.close, null)
                        .show()
                }
            }
            .show()
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (ex: Exception) {
        ""
    }

    private fun aboutDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(getString(R.string.about_text, versionName()))
            .setPositiveButton(R.string.about_site) { _, _ ->
                open(Intent(Intent.ACTION_VIEW, Uri.parse("https://masar.ammarhussein.cloud")), getString(R.string.app_name))
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /** Petrol price per litre, typed in; it turns litres into money on the trip lines. */
    private fun priceDialog() {
        val input = android.widget.EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        input.setText(String.format(Locale.US, "%.2f", Options.fuelPrice(prefs)))
        input.selectAll()
        AlertDialog.Builder(this)
            .setTitle(R.string.price_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val price = input.text.toString().replace(',', '.').toFloatOrNull()
                if (price != null && price > 0f && price < 100f) prefs.edit().putFloat("fuel_price", price).apply()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- sizing

    private fun px(view: TextView, units: Float) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * units)
    }

    private fun square(view: View, size: Int) {
        val params = view.layoutParams
        params.width = size
        params.height = size
        view.layoutParams = params
    }

    private fun applySizes() {
        val height = home.height
        if (height == 0) return
        u = height / 100f

        px(date, 4.6f)
        px(prayer, 4f)
        px(heading, 5.2f)
        px(unit, 3.8f)
        px(statusGps, 3.9f)
        px(statusNet, 3.9f)
        px(statusHandle, 3.9f)
        px(findViewById(R.id.drawerTitle), 6f)
        px(findViewById(R.id.drawerClose), 4.2f)

        val panelWidth = (panel.width - panel.paddingLeft - panel.paddingRight).toFloat()
        // Clock: five characters ("12:45") have to fit the panel width.
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, min(u * 24f, panelWidth / 2.75f))
        // Speed: three digits, with room for the heading and unit beside them.
        speed.setTextSize(TypedValue.COMPLEX_UNIT_PX, min(u * 21f, (panelWidth - u * 14f) / 1.8f))

        // Twelve tiles are shorter than eight, so their icons and labels shrink.
        val three = slots > 8
        val iconSize = (u * (if (three) 11f else 17f)).roundToInt()
        for (tile in tiles) {
            val icon = tile.findViewById<ImageView>(R.id.icon)
            square(icon, iconSize)
            val label = tile.findViewById<TextView>(R.id.label)
            px(label, if (three) 3.3f else 4f)
            (label.layoutParams as ViewGroup.MarginLayoutParams).topMargin = (u * (if (three) 1f else 2.4f)).roundToInt()
        }
        gridAdapter.notifyDataSetChanged()

        square(npArt, (u * 8f).roundToInt())
        for (id in intArrayOf(R.id.npPrev, R.id.npPlay, R.id.npNext)) {
            val button = findViewById<ImageView>(id)
            square(button, (u * 10f).roundToInt())
            val pad = (u * 2.2f).roundToInt()
            button.setPadding(pad, pad, pad, pad)
        }
        px(npTitle, 3.8f)
        px(npArtist, 3.2f)
        px(trip, 3.4f)

        px(camStatus, 3.6f)
        px(findViewById(R.id.camClips), 3.6f)
        px(findViewById(R.id.camTabNormal), 3.6f)
        px(findViewById(R.id.camTabEvent), 3.6f)
        px(findViewById(R.id.camListEmpty), 3.6f)
        px(findViewById(R.id.camPlayerBack), 3.4f)
        px(camButton, 3.8f)
        px(findViewById(R.id.camFrontLabel), 3.2f)
        px(findViewById(R.id.camRearLabel), 3.2f)
        px(stripCoolant, 3.6f)
        px(stripTrans, 3.6f)
        px(stripFuel, 3.6f)
        px(carStatus, 3.4f)
        px(carSpeed, 13f)
        px(findViewById(R.id.carSpeedUnit), 3.8f)
        px(findViewById(R.id.carSpeedNote), 3f)
        px(findViewById(R.id.carCodesTitle), 3.4f)
        px(carCodes, 3.6f)
        px(findViewById(R.id.carTripTitle), 3.4f)
        px(carTrip, 3.4f)
        px(findViewById(R.id.carServiceTitle), 3.4f)
        px(carService, 3.4f)
        px(findViewById(R.id.carCheck), 3.2f)
        px(carClear, 3.2f)
        for (cell in carCells) {
            px(cell.second, 3.4f)
            px(cell.third, 8.4f)
        }

        px(findViewById(R.id.compassTitle), 3.4f)
        px(coords, 3.4f)
        px(altitude, 3.4f)
        px(weatherPlace, 3.4f)
        px(weatherSymbol, 10f)
        px(weatherTemp, 13f)
        px(weatherCond, 4.4f)
        px(weatherDetail, 3.4f)
        px(weatherNote, 3f)
        val zoomSize = (u * 10f).roundToInt()
        for (id in intArrayOf(R.id.zoomIn, R.id.zoomOut)) {
            val button = findViewById<TextView>(id)
            val params = button.layoutParams
            params.width = zoomSize
            params.height = zoomSize
            button.layoutParams = params
            px(button, 6f)
        }
        // The forecast rows are built in code, so they need rebuilding at the new size.
        renderWeather()
    }

    // ---------------------------------------------------------------- apps

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            loadApps()
        }
    }

    /** Reads the installed apps off the main thread, then redraws tiles and drawer. */
    private fun loadApps() {
        Thread {
            val pm = packageManager
            val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val found = ArrayList<AppEntry>()
            try {
                for (info in pm.queryIntentActivities(query, 0)) {
                    val activity = info.activityInfo
                    if (activity.packageName == packageName) continue
                    found.add(
                        AppEntry(
                            info.loadLabel(pm).toString(),
                            ComponentName(activity.packageName, activity.name),
                            info.loadIcon(pm)
                        )
                    )
                }
            } catch (ex: Exception) {
                // Keep whatever was read so far
            }
            found.sortBy { it.label.lowercase() }
            runOnUiThread {
                apps = found
                appsLoaded = true
                renderTiles()
                gridAdapter.notifyDataSetChanged()
            }
        }.start()
    }

    /** Fills the tile rows for the chosen count: two rows of four, or three. */
    private fun buildTiles() {
        val rows = Options.rows(prefs)
        slots = rows * 4
        val views = arrayOf<LinearLayout>(findViewById(R.id.row1), findViewById(R.id.row2), findViewById(R.id.row3))
        for (row in views) row.removeAllViews()
        views[2].visibility = if (rows == 3) View.VISIBLE else View.GONE
        tiles.clear()
        val inflater = LayoutInflater.from(this)
        for (i in 0 until slots) {
            val row = views[i / 4]
            val tile = inflater.inflate(R.layout.tile, row, false)
            row.addView(tile)
            tiles.add(tile)
        }
    }

    /** The app in a tile: the user's choice if there is one, otherwise the default. */
    private fun slotComponent(slot: Int): ComponentName? {
        val saved = prefs.getString("slot_$slot", null)
        if (saved != null) {
            return if (saved.isEmpty()) null else ComponentName.unflattenFromString(saved)
        }
        val wanted = DEFAULTS.getOrNull(slot) ?: return null
        for (pkg in wanted) {
            val match = apps.firstOrNull { it.component.packageName == pkg }
            if (match != null) return match.component
        }
        return null
    }

    private fun entryFor(component: ComponentName?): AppEntry? {
        if (component == null) return null
        return apps.firstOrNull { it.component == component }
            ?: apps.firstOrNull { it.component.packageName == component.packageName }
    }

    private fun renderTiles() {
        if (!appsLoaded) return
        // The home bar shows the apps from tiles 2 to 4; it runs in a service and
        // reads them from here.
        val barApps = prefs.edit()
        for (i in 0 until HandleService.BAR_APPS) {
            val component = entryFor(slotComponent(i + 1))?.component
            barApps.putString(HandleService.PREF_BAR_APP + i, component?.flattenToString() ?: "")
        }
        barApps.apply()
        for (slot in 0 until slots) {
            val tile = tiles[slot]
            tile.backgroundTintList = null
            val icon = tile.findViewById<ImageView>(R.id.icon)
            val label = tile.findViewById<TextView>(R.id.label)

            if (slot == slots - 1) {
                tile.setBackgroundResource(R.drawable.tile_bg)
                icon.setImageResource(R.drawable.ic_apps)
                label.setText(R.string.all_apps)
                label.setTextColor(getColor(R.color.text))
                tile.setOnClickListener { showDrawer(true) }
                continue
            }

            val entry = entryFor(slotComponent(slot))
            if (entry == null) {
                tile.setBackgroundResource(R.drawable.tile_bg_empty)
                icon.setImageResource(R.drawable.ic_add)
                label.setText(R.string.add_app)
                label.setTextColor(getColor(R.color.idle))
            } else if (entry.component.packageName == AUTOKIT) {
                // The CarPlay dongle app is the main thing on this screen.
                tile.setBackgroundResource(R.drawable.tile_bg_hero)
                tile.backgroundTintList = ColorStateList.valueOf(accent)
                icon.setImageDrawable(entry.icon)
                label.setText(R.string.carplay)
                label.setTextColor(getColor(R.color.on_accent))
            } else {
                tile.setBackgroundResource(R.drawable.tile_bg)
                icon.setImageDrawable(entry.icon)
                label.text = entry.label
                label.setTextColor(getColor(R.color.text))
            }
            tile.setOnClickListener { if (entry == null) pickFor(slot) else launch(entry) }
            tile.setOnLongClickListener {
                pickFor(slot)
                true
            }
        }
    }

    private fun launch(entry: AppEntry) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(entry.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        open(intent, entry.label)
    }

    private fun open(intent: Intent, name: String) {
        try {
            startActivity(intent)
        } catch (ex: Exception) {
            Toast.makeText(this, getString(R.string.cant_open, name), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDrawer(show: Boolean) {
        drawer.visibility = if (show) View.VISIBLE else View.GONE
        if (show) grid.setSelection(0)
    }

    // ---------------------------------------------------------------- dialogs

    /** Long-press on a tile: choose which app lives there. */
    private fun pickFor(slot: Int) {
        val items = ArrayList<AppEntry?>()
        items.add(null)
        items.addAll(apps)
        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = items.size
            override fun getItem(position: Int): Any? = items[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_pick, parent, false)
                val entry = items[position]
                val icon = view.findViewById<ImageView>(R.id.icon)
                if (entry == null) icon.setImageResource(R.drawable.ic_add) else icon.setImageDrawable(entry.icon)
                view.findViewById<TextView>(R.id.label).text = entry?.label ?: getString(R.string.pick_empty)
                return view
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.pick_title)
            .setAdapter(adapter) { _, which ->
                val chosen = items[which]
                prefs.edit().putString("slot_$slot", chosen?.component?.flattenToString() ?: "").apply()
                renderTiles()
            }
            .show()
    }

    /** Long-press on an app in the drawer. */
    private fun appMenu(entry: AppEntry) {
        val options = arrayOf(
            getString(R.string.menu_pin),
            getString(R.string.menu_info),
            getString(R.string.menu_uninstall)
        )
        val pkg = Uri.parse("package:" + entry.component.packageName)
        AlertDialog.Builder(this)
            .setTitle(entry.label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pinMenu(entry)
                    1 -> open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg), entry.label)
                    2 -> open(Intent(Intent.ACTION_DELETE, pkg), entry.label)
                }
            }
            .show()
    }

    private fun pinMenu(entry: AppEntry) {
        val names = Array(slots - 1) { slot ->
            val current = entryFor(slotComponent(slot))?.label ?: getString(R.string.tile_empty)
            getString(R.string.tile_n, slot + 1, current)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.pin_title)
            .setItems(names) { _, which ->
                prefs.edit().putString("slot_$which", entry.component.flattenToString()).apply()
                renderTiles()
                showDrawer(false)
            }
            .show()
    }

    private val gridAdapter = object : BaseAdapter() {
        override fun getCount(): Int = apps.size
        override fun getItem(position: Int): Any = apps[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_app, parent, false)
            val entry = apps[position]
            val icon = view.findViewById<ImageView>(R.id.icon)
            val size = (u * 12.5f).roundToInt()
            val params = icon.layoutParams
            params.width = size
            params.height = size
            icon.layoutParams = params
            icon.setImageDrawable(entry.icon)
            val label = view.findViewById<TextView>(R.id.label)
            label.text = entry.label
            px(label, 3.4f)
            return view
        }
    }

    // ---------------------------------------------------------------- GPS

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (!gpsListening && hasLocationPermission()) {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener)
                lm.registerGnssStatusCallback(gnssCallback, ui)
                gpsListening = true
                // Until the first fix arrives, the last position the unit knew
                // is enough to draw the map and fetch the weather.
                if (lastLocation == null) {
                    lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                        lastLocation = it
                        updateDrive(it)
                    }
                }
            } catch (ex: Exception) {
                // No GPS hardware or provider: the status line says "GPS off"
            }
        }
        updateGpsStatus()
    }

    private fun stopGps() {
        if (!gpsListening) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.removeUpdates(locationListener)
            lm.unregisterGnssStatusCallback(gnssCallback)
        } catch (ex: Exception) {
            // Already stopped
        }
        gpsListening = false
        satsUsed = -1
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastFixAt = SystemClock.elapsedRealtime()
            val kmh = if (location.hasSpeed()) (location.speed * 3.6f).roundToInt() else 0
            // Below walking pace GPS speed is noise; show a steady zero when parked.
            speed.text = if (kmh < 3) "0" else kmh.toString()
            val limit = Options.speedLimit(prefs)
            speed.setTextColor(getColor(if (limit > 0 && kmh > limit) R.color.bad else R.color.text))
            if (kmh >= 5 && location.hasBearing()) {
                heading.text = compass8[CompassView.cardinalIndex(location.bearing)]
                headingDeg = location.bearing
            }
            lastLocation = location
            if (lastFixAt - positionSavedAt > 60_000L) {
                positionSavedAt = lastFixAt
                Options.savePosition(prefs, location.latitude, location.longitude)
            }
            updateDrive(location)
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

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) used++
            }
            if (used != satsUsed) {
                satsUsed = used
                updateGpsStatus()
            }
        }

        override fun onStopped() {
            satsUsed = -1
            updateGpsStatus()
        }
    }

    /**
     * Every two seconds: blank the speed if fixes stop arriving (garage, tunnel),
     * so a stale number never shows, and refresh the trip, the next prayer and
     * the night dimming.
     */
    private val staleCheck = object : Runnable {
        override fun run() {
            if (!gpsListening || SystemClock.elapsedRealtime() - lastFixAt > 5000) {
                speed.text = "–"
                speed.setTextColor(getColor(R.color.text))
                heading.text = ""
                headingDeg = null
                compass.heading = null
            }
            renderTrip()
            renderPrayer()
            renderNight()
            ui.postDelayed(this, 2000)
        }
    }

    // ---------------------------------------------------------------- drive page

    /** Feeds a position to the compass, the coordinates, the map and the weather. */
    private fun updateDrive(location: Location) {
        compass.heading = headingDeg
        coords.text = String.format(
            Locale.US, "%.4f° %s   %.4f° %s",
            abs(location.latitude), if (location.latitude >= 0) "N" else "S",
            abs(location.longitude), if (location.longitude >= 0) "E" else "W"
        )
        altitude.text = if (location.hasAltitude()) getString(R.string.altitude, location.altitude.roundToInt()) else ""
        map.setPosition(location.latitude, location.longitude, headingDeg)
        refreshWeather(false)
    }

    private fun zoomMap(delta: Int) {
        map.zoom = map.zoom + delta
        prefs.edit().putInt("map_zoom", map.zoom).apply()
    }

    /** A tap on the map hands the position to a real map app. */
    private fun openMap() {
        val here = lastLocation ?: return
        val uri = Uri.parse(String.format(Locale.US, "geo:%.5f,%.5f?z=%d", here.latitude, here.longitude, map.zoom))
        open(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), getString(R.string.map_title))
    }

    // ---------------------------------------------------------------- weather

    /** The forecast from the last run, so the page isn't empty while a new one loads. */
    private fun loadSavedWeather() {
        val savedAt = prefs.getLong("weather_at", 0L)
        val age = System.currentTimeMillis() - savedAt
        if (age < 0 || age > WEATHER_KEEP_MS) return
        val report = Weather.parse(prefs.getString("weather_json", null) ?: return) ?: return
        weather = report
        weatherAt = savedAt
        weatherLat = prefs.getFloat("weather_lat", 0f).toDouble()
        weatherLon = prefs.getFloat("weather_lon", 0f).toDouble()
        weatherPlaceName = prefs.getString("weather_place", "") ?: ""
    }

    /**
     * Fetches a forecast when there is none, when it is over half an hour old,
     * or when the car has moved a long way since. [force] skips those checks.
     */
    private fun refreshWeather(force: Boolean) {
        val here = lastLocation
        if (here == null || !online || weatherBusy) {
            if (weather == null) renderWeather()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!force) {
            if (weatherTriedAt != 0L && now - weatherTriedAt < WEATHER_RETRY_MS) return
            if (weather != null) {
                val age = System.currentTimeMillis() - weatherAt
                val moved = FloatArray(1)
                Location.distanceBetween(weatherLat, weatherLon, here.latitude, here.longitude, moved)
                if (age in 0L until WEATHER_MAX_AGE_MS && moved[0] < WEATHER_MOVE_M) return
            }
        }
        weatherBusy = true
        weatherTriedAt = now
        if (weather == null) renderWeather()

        val lat = here.latitude
        val lon = here.longitude
        Thread {
            var json: String? = null
            try {
                json = Weather.download(lat, lon)
            } catch (ex: Exception) {
                // Keep showing the previous forecast
            }
            val report = json?.let { Weather.parse(it) }
            val place = if (report != null) placeName(lat, lon) else null
            runOnUiThread {
                weatherBusy = false
                if (report != null) {
                    weather = report
                    weatherAt = System.currentTimeMillis()
                    weatherLat = lat
                    weatherLon = lon
                    weatherPlaceName = place ?: ""
                    prefs.edit()
                        .putString("weather_json", json)
                        .putLong("weather_at", weatherAt)
                        .putFloat("weather_lat", lat.toFloat())
                        .putFloat("weather_lon", lon.toFloat())
                        .putString("weather_place", weatherPlaceName)
                        .apply()
                }
                renderWeather()
            }
        }.start()
    }

    /** The town for a position, if the unit has a geocoder. Blocks. */
    private fun placeName(lat: Double, lon: Double): String? {
        try {
            if (!Geocoder.isPresent()) return null
            @Suppress("DEPRECATION")
            val found = Geocoder(this, Locale.getDefault()).getFromLocation(lat, lon, 1)
            val first = found?.firstOrNull() ?: return null
            return first.locality ?: first.subAdminArea ?: first.adminArea
        } catch (ex: Exception) {
            return null
        }
    }

    private fun renderWeather() {
        forecast.removeAllViews()
        val report = weather
        if (report == null) {
            weatherPlace.setText(R.string.weather_title)
            weatherSymbol.text = ""
            weatherTemp.text = "–"
            weatherCond.setText(
                when {
                    lastLocation == null -> R.string.weather_wait_gps
                    !online -> R.string.weather_offline
                    else -> R.string.weather_loading
                }
            )
            weatherDetail.text = ""
            weatherNote.text = ""
            return
        }

        weatherPlace.text = weatherPlaceName.ifEmpty { getString(R.string.weather_title) }
        weatherSymbol.text = Weather.symbol(report.code, report.isDay)
        weatherTemp.text = "${report.temp}°"
        weatherCond.setText(Weather.label(report.code))
        val today = report.days.firstOrNull()
        weatherDetail.text = getString(
            R.string.weather_detail,
            today?.max ?: report.temp, today?.min ?: report.temp,
            report.feels, report.wind, report.humidity
        )
        weatherNote.text = getString(R.string.weather_updated, DateFormat.getTimeFormat(this).format(Date(weatherAt)))

        // The next three days, one row each: day, pictogram, high / low.
        val dayParser = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dayName = SimpleDateFormat("EEE", Locale.getDefault())
        for (day in report.days.drop(1).take(3)) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, (u * 0.9f).roundToInt(), 0, (u * 0.9f).roundToInt())

            val name = TextView(this)
            name.text = try {
                dayName.format(dayParser.parse(day.date) ?: Date())
            } catch (ex: Exception) {
                day.date
            }
            name.setTextColor(getColor(R.color.text_dim))
            px(name, 4f)
            row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val symbol = TextView(this)
            symbol.text = Weather.symbol(day.code, true)
            symbol.setTextColor(getColor(R.color.text))
            px(symbol, 4f)
            row.addView(symbol)

            val range = TextView(this)
            range.text = getString(R.string.weather_day_range, day.max, day.min)
            range.setTextColor(getColor(R.color.text))
            range.gravity = Gravity.END
            px(range, 4f)
            row.addView(range, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))

            forecast.addView(row)
        }
    }

    private fun updateGpsStatus() {
        if (!hasLocationPermission()) {
            setStatus(statusGps, getString(R.string.gps_tap), R.color.accent)
        } else if (!gpsListening) {
            setStatus(statusGps, getString(R.string.gps_off), R.color.idle)
        } else if (satsUsed > 0) {
            setStatus(statusGps, getString(R.string.gps_sats, satsUsed), R.color.ok)
        } else {
            setStatus(statusGps, getString(R.string.gps_searching), R.color.idle)
        }
    }

    // ---------------------------------------------------------------- network

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ui.post { setNet(if (validated) NET_ONLINE else NET_NO_INTERNET) }
        }

        override fun onLost(network: Network) {
            ui.post { setNet(NET_OFFLINE) }
        }
    }

    /**
     * A tap on the internet line: look for known Wi-Fi networks right now, and
     * open Android's Wi-Fi sheet so the hotspot is one tap away. An app may not
     * pick the network itself, but this skips waiting for the next routine scan.
     */
    @Suppress("DEPRECATION")
    private fun reconnectWifi() {
        try {
            (applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager).startScan()
        } catch (ex: Exception) {
            // Scans are rationed; the sheet below scans by itself anyway
        }
        try {
            startActivity(Intent(Settings.Panel.ACTION_WIFI))
        } catch (ex: Exception) {
            open(Intent(Settings.ACTION_WIFI_SETTINGS), getString(R.string.app_name))
        }
    }

    private fun setNet(state: Int) {
        val wasOnline = online
        online = state == NET_ONLINE
        if (online && !wasOnline) refreshWeather(false)
        when (state) {
            NET_ONLINE -> setStatus(statusNet, getString(R.string.net_online), R.color.ok)
            NET_NO_INTERNET -> setStatus(statusNet, getString(R.string.net_no_internet), R.color.accent)
            else -> setStatus(statusNet, getString(R.string.net_offline), R.color.bad)
        }
    }

    private fun setStatus(view: TextView, text: String, colorRes: Int) {
        view.text = text
        view.compoundDrawableTintList = ColorStateList.valueOf(getColor(colorRes))
    }

    companion object {
        private const val AUTOKIT = "cn.manstep.phonemirrorBox"
        private const val PREF_HANDLE_ASKED = "handle_asked"

        private const val NET_OFFLINE = 0
        private const val NET_NO_INTERNET = 1
        private const val NET_ONLINE = 2

        private const val WEATHER_MAX_AGE_MS = 30L * 60 * 1000
        private const val WEATHER_KEEP_MS = 3L * 60 * 60 * 1000
        private const val WEATHER_RETRY_MS = 60_000L
        private const val WEATHER_MOVE_M = 25_000f

        private const val REQUEST_PHOTO = 2
        private const val CAM_LIVE = 0
        private const val CAM_CLIPS = 1
        private const val CAM_PLAYING = 2
        /** True once CarPlay has been opened automatically in this run of the unit. */
        private var autoOpened = false
        private const val WALLPAPER_FILE = "wallpaper.jpg"
        /** How dark the night veil is: enough to stop the glare, not enough to hide the tiles. */
        private const val NIGHT_ALPHA = 0.38f

        /**
         * What each tile holds until the user changes it: the first installed
         * package in each list wins. The last tile is always "All apps".
         */
        private val DEFAULTS: List<List<String>> = listOf(
            listOf(AUTOKIT),
            listOf("com.google.android.apps.maps", "com.waze", "com.sygic.aura"),
            listOf("com.google.android.youtube"),
            listOf("com.google.android.apps.youtube.music", "com.soundcloud.android", "com.spotify.music"),
            listOf("com.android.chrome"),
            listOf("com.android.vending"),
            listOf("com.android.settings")
        )
    }
}
