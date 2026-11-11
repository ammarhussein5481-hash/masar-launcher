package cloud.ammarhussein.masar

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import java.util.Locale

/** The user's choices from Masar's settings menu, all kept in one preferences file. */
object Options {

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("masar", Context.MODE_PRIVATE)

    // Speed alert: km/h, or 0 for off.
    val SPEED_LIMITS = intArrayOf(0, 60, 80, 100, 110, 120, 130, 140)
    fun speedLimit(prefs: SharedPreferences): Int = prefs.getInt("speed_limit", 0)

    // Tile rows on the home page: 2 (eight tiles) or 3 (twelve).
    fun rows(prefs: SharedPreferences): Int = if (prefs.getInt("rows", 2) == 3) 3 else 2

    // Accent colour.
    val ACCENTS = intArrayOf(
        0xFFFFB020.toInt(), // amber
        0xFF4DA3FF.toInt(), // blue
        0xFF3DDC84.toInt(), // green
        0xFFFF6B6B.toInt(), // red
        0xFFB794F6.toInt(), // purple
        0xFFF2F4F7.toInt()  // white
    )
    fun accent(prefs: SharedPreferences): Int = prefs.getInt("accent", ACCENTS[0])

    // Night dimming: follow the sun, always dim, or never.
    const val NIGHT_AUTO = 0
    const val NIGHT_ALWAYS = 1
    const val NIGHT_OFF = 2
    fun night(prefs: SharedPreferences): Int = prefs.getInt("night", NIGHT_AUTO)

    fun prayerOn(prefs: SharedPreferences): Boolean = prefs.getBoolean("prayer", true)

    // Language: "" follows the system, otherwise "en" or "ar".
    fun language(prefs: SharedPreferences): String = prefs.getString("lang", "") ?: ""

    /**
     * A context in the chosen language. Arabic keeps Western digits, so the
     * clock and the speed read the same in both languages.
     */
    fun localized(base: Context): Context {
        val tag = when (language(prefs(base))) {
            "ar" -> "ar-u-nu-latn"
            "en" -> "en"
            else -> {
                // Back to the unit's own language, in case another was set earlier in this run.
                Locale.setDefault(base.resources.configuration.locales[0])
                return base
            }
        }
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    // Fuel. The price is per litre in the local currency; the tank is a 2015 Tahoe's.
    const val DEFAULT_PRICE = 2.18f
    fun fuelPrice(prefs: SharedPreferences): Float = prefs.getFloat("fuel_price", DEFAULT_PRICE)
    fun tankLiters(prefs: SharedPreferences): Float = prefs.getFloat("tank_l", 98f)
    /** Long-run litres per 100 km, learned from finished trips; a guess until then. */
    fun averageUse(prefs: SharedPreferences): Double = prefs.getFloat("avg_l100", 16f).toDouble()

    fun autoCarPlay(prefs: SharedPreferences): Boolean = prefs.getBoolean("auto_carplay", false)

    // Service reminders, by distance on Masar's own odometer (Trip.totalMeters).
    val SERVICES = arrayOf("oil", "rotation", "air")
    val SERVICE_KM = intArrayOf(8000, 10000, 20000)
    /** Kilometres left until service [index] is due; negative once it is overdue. */
    fun serviceLeftKm(prefs: SharedPreferences, index: Int): Int {
        val doneAt = prefs.getFloat("svc_" + SERVICES[index], 0f).toDouble()
        return SERVICE_KM[index] - ((Trip.totalMeters - doneAt) / 1000.0).toInt()
    }
    fun serviceDone(prefs: SharedPreferences, index: Int) {
        prefs.edit().putFloat("svc_" + SERVICES[index], Trip.totalMeters.toFloat()).apply()
    }

    /** The last position the unit knew, for prayer times and sunset before the first fix. */
    fun savePosition(prefs: SharedPreferences, lat: Double, lon: Double) {
        prefs.edit().putFloat("last_lat", lat.toFloat()).putFloat("last_lon", lon.toFloat()).apply()
    }

    fun savedPosition(prefs: SharedPreferences): DoubleArray? {
        if (!prefs.contains("last_lat")) return null
        return doubleArrayOf(prefs.getFloat("last_lat", 0f).toDouble(), prefs.getFloat("last_lon", 0f).toDouble())
    }
}
