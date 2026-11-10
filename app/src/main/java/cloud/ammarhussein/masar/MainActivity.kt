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
import android.graphics.drawable.Drawable
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.min
import kotlin.math.roundToInt

/** One launchable app: what the tiles and the drawer show. */
class AppEntry(val label: String, val component: ComponentName, val icon: Drawable)

/**
 * The whole launcher is this one screen.
 *
 * Left: clock, date, GPS speed and heading, and two status lines (GPS, internet).
 * Right: eight large tiles. The first seven hold apps, the last opens "All apps".
 *
 * Every size is derived from the screen height (see [applySizes]), so the layout
 * looks the same on any head unit resolution or density.
 */
class MainActivity : Activity() {

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
    private lateinit var grid: GridView

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

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("masar", Context.MODE_PRIVATE)

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
        grid = findViewById(R.id.grid)

        val inflater = LayoutInflater.from(this)
        val row1 = findViewById<LinearLayout>(R.id.row1)
        val row2 = findViewById<LinearLayout>(R.id.row2)
        for (i in 0 until SLOTS) {
            val row = if (i < SLOTS / 2) row1 else row2
            val tile = inflater.inflate(R.layout.tile, row, false)
            row.addView(tile)
            tiles.add(tile)
        }

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
        ui.removeCallbacks(staleCheck)
        ui.postDelayed(staleCheck, 2000)
    }

    override fun onStop() {
        super.onStop()
        stopGps()
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
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // A launcher has nowhere to go back to; Back only closes the drawer.
        showDrawer(false)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startGps()
    }

    // ---------------------------------------------------------------- sizing

    private fun px(view: TextView, units: Float) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, u * units)
    }

    private fun applySizes() {
        val height = home.height
        if (height == 0) return
        u = height / 100f

        px(date, 4.6f)
        px(heading, 5.2f)
        px(unit, 3.8f)
        px(statusGps, 3.9f)
        px(statusNet, 3.9f)
        px(findViewById(R.id.drawerTitle), 6f)
        px(findViewById(R.id.drawerClose), 4.2f)

        val panelWidth = (panel.width - panel.paddingLeft - panel.paddingRight).toFloat()
        // Clock: five characters ("12:45") have to fit the panel width.
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, min(u * 24f, panelWidth / 2.75f))
        // Speed: three digits, with room for the heading and unit beside them.
        speed.setTextSize(TypedValue.COMPLEX_UNIT_PX, min(u * 21f, (panelWidth - u * 14f) / 1.8f))

        val iconSize = (u * 17f).roundToInt()
        for (tile in tiles) {
            val icon = tile.findViewById<ImageView>(R.id.icon)
            val params = icon.layoutParams
            params.width = iconSize
            params.height = iconSize
            icon.layoutParams = params
            px(tile.findViewById(R.id.label), 4f)
        }
        gridAdapter.notifyDataSetChanged()
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
        for (slot in 0 until SLOTS) {
            val tile = tiles[slot]
            val icon = tile.findViewById<ImageView>(R.id.icon)
            val label = tile.findViewById<TextView>(R.id.label)

            if (slot == SLOTS - 1) {
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
        val names = Array(SLOTS - 1) { slot ->
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
            if (kmh >= 5 && location.hasBearing()) {
                heading.text = COMPASS[(((location.bearing + 22.5f) / 45f).toInt()) % 8]
            }
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

    /** Blank the speed if fixes stop arriving (garage, tunnel), so a stale number never shows. */
    private val staleCheck = object : Runnable {
        override fun run() {
            if (!gpsListening || SystemClock.elapsedRealtime() - lastFixAt > 5000) {
                speed.text = "–"
                heading.text = ""
            }
            ui.postDelayed(this, 2000)
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

    private fun setNet(state: Int) {
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
        private const val SLOTS = 8
        private const val AUTOKIT = "cn.manstep.phonemirrorBox"

        private const val NET_OFFLINE = 0
        private const val NET_NO_INTERNET = 1
        private const val NET_ONLINE = 2

        private val COMPASS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

        /**
         * What each tile holds until the user changes it: the first installed
         * package in each list wins. Tile 8 is always "All apps".
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
