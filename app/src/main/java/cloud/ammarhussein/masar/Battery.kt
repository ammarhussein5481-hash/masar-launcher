package cloud.ammarhussein.masar

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Watches the 12 V battery for signs that it is wearing out. Two readings are
 * taken at each engine start, when Masar is already connected to the car:
 * the voltage at rest with the key on, and the lowest voltage while the
 * starter turns. A tired battery sits lower at rest and sags further on the
 * starter. Its age counts too, because heat ends batteries without warning.
 */
object Battery {

    const val UNKNOWN = -1
    const val GOOD = 0
    const val WEAK = 1
    const val REPLACE = 2

    private const val TAG = "MasarBattery"
    private const val PREF_LOG = "battery_log"
    private const val PREF_FITTED = "battery_fitted"
    private const val KEEP = 60
    private const val WINDOW_MS = 10_000L
    private const val MONTH_MS = 30.44 * 24 * 3600 * 1000

    // Engine-off samples from the last few seconds: time, volts, and whether the engine was still (rpm 0).
    private class Sample(val at: Long, val volts: Float, val still: Boolean)
    private val recent = ArrayList<Sample>()
    private var running = false
    private var stoppedAt = 0L

    /** Called by the car link on every round; [volts] only when it was just read with the engine not running. */
    @Synchronized
    fun sample(context: Context, volts: Float?, rpm: Int?) {
        val now = SystemClock.elapsedRealtime()
        val runs = rpm != null && rpm > 400
        if (!runs) {
            if (running) {
                stoppedAt = now
                recent.clear()
            }
            running = false
            if (volts != null) recent.add(Sample(now, volts, rpm == 0))
            recent.removeAll { now - it.at > WINDOW_MS }
            return
        }
        if (running) return
        running = true
        recent.removeAll { now - it.at > WINDOW_MS }
        // Just after a drive the battery still carries a surface charge, which flatters it.
        val restarted = stoppedAt != 0L && now - stoppedAt < 5 * 60_000L
        val rest = if (restarted) null else recent.filter { it.still && now - it.at > 2000 }.maxOfOrNull { it.volts }
        val low = recent.minOfOrNull { it.volts }
        // A dip only counts if one was really caught; the starter is quick and may fall between two readings.
        val before = rest ?: recent.maxOfOrNull { it.volts }
        val crank = if (low != null && before != null && low < before - 0.5f) low else null
        recent.clear()
        if (rest == null && crank == null) return
        Log.i(TAG, "start: rest=$rest crank=$crank")
        val prefs = Options.prefs(context)
        val log = entries(prefs)
        log.put(JSONObject().put("t", System.currentTimeMillis()).put("rest", rest?.toDouble() ?: 0.0).put("crank", crank?.toDouble() ?: 0.0))
        val kept = JSONArray()
        for (i in maxOf(0, log.length() - KEEP) until log.length()) kept.put(log.get(i))
        prefs.edit().putString(PREF_LOG, kept.toString()).apply()
    }

    private fun entries(prefs: SharedPreferences): JSONArray = try {
        JSONArray(prefs.getString(PREF_LOG, "[]"))
    } catch (ex: Exception) {
        JSONArray()
    }

    /** The average of the last three readings of [field], or null if there are none. */
    private fun latest(prefs: SharedPreferences, field: String): Float? {
        val log = entries(prefs)
        val values = ArrayList<Double>()
        var i = log.length() - 1
        while (i >= 0 && values.size < 3) {
            val v = log.optJSONObject(i)?.optDouble(field, 0.0) ?: 0.0
            if (v > 0) values.add(v)
            i--
        }
        return if (values.isEmpty()) null else values.average().toFloat()
    }

    fun rest(prefs: SharedPreferences): Float? = latest(prefs, "rest")
    fun crank(prefs: SharedPreferences): Float? = latest(prefs, "crank")

    /** Whole months since the battery was fitted, or null if the date was never given. */
    fun ageMonths(prefs: SharedPreferences): Int? {
        val fitted = prefs.getLong(PREF_FITTED, 0L)
        return if (fitted <= 0L) null else ((System.currentTimeMillis() - fitted) / MONTH_MS).toInt().coerceAtLeast(0)
    }

    /** Sets the day the battery was fitted. A new battery starts a new record. */
    fun fitted(prefs: SharedPreferences, at: Long, fresh: Boolean) {
        val edit = prefs.edit().putLong(PREF_FITTED, at)
        if (fresh) edit.remove(PREF_LOG)
        edit.apply()
    }

    fun verdict(prefs: SharedPreferences): Int {
        val rest = rest(prefs)
        val crank = crank(prefs)
        val age = ageMonths(prefs)
        if (rest == null && crank == null && age == null) return UNKNOWN
        if ((crank != null && crank < 9.0f) || (rest != null && rest < 11.8f) || (age != null && age >= 36)) return REPLACE
        if ((crank != null && crank < 9.6f) || (rest != null && rest < 12.1f) || (age != null && age >= 24)) return WEAK
        return GOOD
    }
}
