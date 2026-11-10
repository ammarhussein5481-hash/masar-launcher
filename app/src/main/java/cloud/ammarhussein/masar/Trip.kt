package cloud.ammarhussein.masar

import android.content.SharedPreferences
import android.location.Location
import android.os.SystemClock
import kotlin.math.max

/**
 * The current trip: distance, time spent moving, and top speed, all from GPS.
 *
 * It is fed by [HandleService], which keeps listening while other apps are in
 * front, so the numbers cover the whole drive. A trip ends by itself when no
 * fix has arrived for [GAP_MS] (the car was parked), and the next fix starts a
 * new one. The totals are saved so a reboot in the middle of a drive keeps them.
 */
object Trip {

    var meters = 0.0
        private set
    var movingMs = 0L
        private set
    var topKmh = 0
        private set

    /** Wall-clock time of the last fix, to notice a long stop across reboots. */
    private var lastWall = 0L
    /** Uptime of the last fix, to measure the step between two fixes. */
    private var lastUptime = 0L
    private var lastSaved = 0L
    private var loaded = false

    fun load(prefs: SharedPreferences) {
        if (loaded) return
        loaded = true
        meters = prefs.getFloat("trip_m", 0f).toDouble()
        movingMs = prefs.getLong("trip_ms", 0L)
        topKmh = prefs.getInt("trip_top", 0)
        lastWall = prefs.getLong("trip_last", 0L)
    }

    fun reset(prefs: SharedPreferences) {
        meters = 0.0
        movingMs = 0L
        topKmh = 0
        lastUptime = 0L
        save(prefs)
    }

    fun onFix(location: Location, prefs: SharedPreferences) {
        load(prefs)
        val wall = System.currentTimeMillis()
        val uptime = SystemClock.elapsedRealtime()
        if (lastWall != 0L && (wall - lastWall > GAP_MS || wall < lastWall - GAP_MS)) {
            // Parked long enough: this fix begins a new trip.
            meters = 0.0
            movingMs = 0L
            topKmh = 0
            lastUptime = 0L
        }
        lastWall = wall

        val speed = if (location.hasSpeed()) location.speed else 0f
        val step = if (lastUptime == 0L) 0L else uptime - lastUptime
        lastUptime = uptime
        // Count a step only when the car is moving and the fixes are consecutive;
        // GPS speed below walking pace is noise.
        if (speed >= MOVING_MPS && step in 1..MAX_STEP_MS) {
            meters += speed * step / 1000.0
            movingMs += step
            topKmh = max(topKmh, (speed * 3.6f).toInt())
        }
        if (uptime - lastSaved > SAVE_EVERY_MS) save(prefs)
    }

    fun save(prefs: SharedPreferences) {
        lastSaved = SystemClock.elapsedRealtime()
        prefs.edit()
            .putFloat("trip_m", meters.toFloat())
            .putLong("trip_ms", movingMs)
            .putInt("trip_top", topKmh)
            .putLong("trip_last", lastWall)
            .apply()
    }

    /** Average speed over the time spent moving, in km/h. */
    fun averageKmh(): Int = if (movingMs < 1000L) 0 else (meters / (movingMs / 1000.0) * 3.6).toInt()

    private const val GAP_MS = 30L * 60 * 1000
    private const val MAX_STEP_MS = 10_000L
    private const val SAVE_EVERY_MS = 30_000L
    private const val MOVING_MPS = 0.9f
}
