package cloud.ammarhussein.masar

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

/** One forecast day. [date] is "yyyy-MM-dd" in the place's own time zone. */
class WeatherDay(val date: String, val code: Int, val max: Int, val min: Int)

/** Temperatures in °C, wind in km/h, humidity in percent. [days] starts with today. */
class WeatherReport(
    val temp: Int,
    val feels: Int,
    val code: Int,
    val isDay: Boolean,
    val wind: Int,
    val humidity: Int,
    val days: List<WeatherDay>
)

/** Current conditions and a short forecast from Open-Meteo, which needs no account or key. */
object Weather {

    /** Sent with every request the launcher makes, as the map and weather services ask. */
    const val USER_AGENT = "Masar launcher (github.com/ammarhussein5481-hash/masar-launcher)"

    /** Downloads the raw forecast for a position. Blocks, so call it off the main thread. */
    @Throws(IOException::class)
    fun download(lat: Double, lon: Double): String {
        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,is_day" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
                "&timezone=auto&forecast_days=4",
            lat, lon
        )
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (connection.responseCode != 200) throw IOException("HTTP " + connection.responseCode)
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** Null if the text is not a forecast in the expected shape. */
    fun parse(json: String): WeatherReport? {
        try {
            val root = JSONObject(json)
            val now = root.getJSONObject("current")
            val daily = root.getJSONObject("daily")
            val dates = daily.getJSONArray("time")
            val codes = daily.getJSONArray("weather_code")
            val highs = daily.getJSONArray("temperature_2m_max")
            val lows = daily.getJSONArray("temperature_2m_min")
            val days = ArrayList<WeatherDay>()
            for (i in 0 until dates.length()) {
                days.add(
                    WeatherDay(
                        dates.getString(i),
                        codes.getInt(i),
                        highs.getDouble(i).roundToInt(),
                        lows.getDouble(i).roundToInt()
                    )
                )
            }
            return WeatherReport(
                now.getDouble("temperature_2m").roundToInt(),
                now.getDouble("apparent_temperature").roundToInt(),
                now.getInt("weather_code"),
                now.getInt("is_day") == 1,
                now.getDouble("wind_speed_10m").roundToInt(),
                now.getDouble("relative_humidity_2m").roundToInt(),
                days
            )
        } catch (ex: Exception) {
            return null
        }
    }

    /** The name of a WMO weather code, as a string resource. */
    fun label(code: Int): Int = when (code) {
        0 -> R.string.wx_clear
        1 -> R.string.wx_mostly_clear
        2 -> R.string.wx_partly_cloudy
        3 -> R.string.wx_cloudy
        45, 48 -> R.string.wx_fog
        51, 53, 55 -> R.string.wx_drizzle
        56, 57, 66, 67 -> R.string.wx_freezing_rain
        61, 63, 65 -> R.string.wx_rain
        71, 73, 75, 77, 85, 86 -> R.string.wx_snow
        80, 81, 82 -> R.string.wx_showers
        95, 96, 99 -> R.string.wx_thunder
        else -> R.string.wx_unknown
    }

    /** A pictogram for a WMO weather code. */
    fun symbol(code: Int, isDay: Boolean): String = when (code) {
        0, 1 -> if (isDay) "☀️" else "🌙"
        2 -> if (isDay) "⛅" else "☁️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        51, 53, 55, 61, 63, 65, 80, 81, 82 -> "🌧️"
        56, 57, 66, 67 -> "🌧️"
        71, 73, 75, 77, 85, 86 -> "❄️"
        95, 96, 99 -> "⛈️"
        else -> ""
    }
}
