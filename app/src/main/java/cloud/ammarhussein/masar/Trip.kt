package cloud.ammarhussein.masar

import android.content.SharedPreferences
import android.location.Location
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/**
 * The current trip: distance, time spent moving and top speed from GPS, and
 * fuel burned from the OBD dongle.
 *
 * GPS fixes come from [HandleService], which keeps listening while other apps
 * are in front, and fuel from [Obd], so the numbers cover the whole drive. A
 * trip ends by itself when no fix has arrived for [GAP_MS] (the car was
 * parked); it is then filed in the history and the next fix starts a new one.
 * The totals are saved so a reboot in the middle of a drive keeps them.
 *
 * [totalMeters] never resets: it is Masar's own odometer, counted from the day
 * it was installed, and the service reminders are measured against it.
 */
object Trip {

    var meters = 0.0
        private set
    var movingMs = 0L
        private set
    var topKmh = 0
        private set
    var liters = 0.0
        private set
    var totalMeters = 0.0
        private set

    /** Wall-clock time the trip began, or 0 before its first fix. */
    private var startWall = 0L
    /** Wall-clock time of the last fix, to notice a long stop across reboots. */
    private var lastWall = 0L
    /** Uptime of the last fix, to measure the step between two fixes. */
    private var lastUptime = 0L
    private var lastSaved = 0L
    private var loaded = false

    /** A finished trip, as kept in the history. */
    class Past(val start: Long, val meters: Double, val movingMs: Long, val topKmh: Int, val liters: Double)

    @Synchronized
    fun load(prefs: SharedPreferences) {
        if (loaded) return
        loaded = true
        meters = prefs.getFloat("trip_m", 0f).toDouble()
        movingMs = prefs.getLong("trip_ms", 0L)
        topKmh = prefs.getInt("trip_top", 0)
        liters = prefs.getFloat("trip_l", 0f).toDouble()
        startWall = prefs.getLong("trip_start", 0L)
        lastWall = prefs.getLong("trip_last", 0L)
        totalMeters = prefs.getFloat("total_m", 0f).toDouble()
    }

    /** Ends the trip by hand: files it and starts from zero. */
    @Synchronized
    fun reset(prefs: SharedPreferences) {
        load(prefs)
        finish(prefs)
        save(prefs)
    }

    @Synchronized
    fun onFix(location: Location, prefs: SharedPreferences) {
        load(prefs)
        val wall = System.currentTimeMillis()
        val uptime = SystemClock.elapsedRealtime()
        if (lastWall != 0L && (wall - lastWall > GAP_MS || wall < lastWall - GAP_MS)) {
            // Parked long enough: this fix begins a new trip.
            finish(prefs)
        }
        lastWall = wall
        if (startWall == 0L) startWall = wall

        val speed = if (location.hasSpeed()) location.speed else 0f
        val step = if (lastUptime == 0L) 0L else uptime - lastUptime
        lastUptime = uptime
        // Count a step only when the car is moving and the fixes are consecutive;
        // GPS speed below walking pace is noise.
        if (speed >= MOVING_MPS && step in 1..MAX_STEP_MS) {
            val moved = speed * step / 1000.0
            meters += moved
            totalMeters += moved
            movingMs += step
            topKmh = max(topKmh, (speed * 3.6f).toInt())
        }
        if (uptime - lastSaved > SAVE_EVERY_MS) save(prefs)
    }

    /** Fuel burned since the last call, from the engine's air flow. */
    @Synchronized
    fun addFuel(amount: Double) {
        if (amount > 0.0 && amount < 0.2) liters += amount
    }

    /** Files the trip in the history, if it was a real one, and zeroes the counters. */
    private fun finish(prefs: SharedPreferences) {
        if (meters >= MIN_FILED_M) {
            val entry = JSONObject()
                .put("t", if (startWall != 0L) startWall else lastWall)
                .put("m", meters)
                .put("ms", movingMs)
                .put("top", topKmh)
                .put("l", liters)
            val old = try {
                JSONArray(prefs.getString("trips", "[]"))
            } catch (ex: Exception) {
                JSONArray()
            }
            // Newest first, and only the last sixty.
            val kept = JSONArray().put(entry)
            for (i in 0 until minOf(old.length(), KEEP - 1)) kept.put(old.get(i))
            val edit = prefs.edit().putString("trips", kept.toString())
            // The long-run fuel figure the range estimate uses.
            if (liters > 0.2 && meters > 3000.0) {
                val per100 = liters / (meters / 100_000.0)
                if (per100 in 5.0..40.0) {
                    edit.putFloat("avg_l100", (Options.averageUse(prefs) * 0.7 + per100 * 0.3).toFloat())
                }
            }
            edit.apply()
        }
        meters = 0.0
        movingMs = 0L
        topKmh = 0
        liters = 0.0
        startWall = 0L
        lastUptime = 0L
    }

    @Synchronized
    fun save(prefs: SharedPreferences) {
        lastSaved = SystemClock.elapsedRealtime()
        prefs.edit()
            .putFloat("trip_m", meters.toFloat())
            .putLong("trip_ms", movingMs)
            .putInt("trip_top", topKmh)
            .putFloat("trip_l", liters.toFloat())
            .putLong("trip_start", startWall)
            .putLong("trip_last", lastWall)
            .putFloat("total_m", totalMeters.toFloat())
            .apply()
    }

    /** Past trips, newest first. */
    fun history(prefs: SharedPreferences): List<Past> {
        val out = ArrayList<Past>()
        try {
            val all = JSONArray(prefs.getString("trips", "[]"))
            for (i in 0 until all.length()) {
                val o = all.getJSONObject(i)
                out.add(Past(o.optLong("t"), o.optDouble("m", 0.0), o.optLong("ms"), o.optInt("top"), o.optDouble("l", 0.0)))
            }
        } catch (ex: Exception) {
            // A damaged list reads as empty
        }
        return out
    }

    /** Average speed over the time spent moving, in km/h. */
    fun averageKmh(): Int = if (movingMs < 1000L) 0 else (meters / (movingMs / 1000.0) * 3.6).toInt()

    /** Litres per 100 km over this trip, or null until there is enough of both. */
    fun per100(): Double? = if (meters < 500.0 || liters <= 0.0) null else liters / (meters / 100_000.0)

    private const val GAP_MS = 30L * 60 * 1000
    private const val MAX_STEP_MS = 10_000L
    private const val SAVE_EVERY_MS = 30_000L
    private const val MOVING_MPS = 0.9f
    private const val MIN_FILED_M = 500.0
    private const val KEEP = 60
}
