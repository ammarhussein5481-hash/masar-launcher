package cloud.ammarhussein.masar

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One internet radio station. [url] is the playable stream. */
class Station(
    val uuid: String,
    val name: String,
    val url: String,
    val codec: String,
    val bitrate: Int,
    val hls: Boolean
) {
    fun toJson(): String = JSONArray().put(uuid).put(name).put(url).put(codec).put(bitrate).put(hls).toString()

    companion object {
        fun fromJson(text: String): Station? = try {
            val a = JSONArray(text)
            Station(a.getString(0), a.getString(1), a.getString(2), a.optString(3), a.optInt(4), a.optBoolean(5))
        } catch (ex: Exception) {
            null
        }
    }
}

/**
 * Internet radio stations from radio-browser.info, a free directory with no key.
 * The directory keeps the stream URLs current, so Masar holds none of its own.
 * Favourites are saved on the unit (see [Options]-style prefs in MainActivity).
 */
object Radio {

    const val USER_AGENT = "Masar/1.0 (github.com/ammarhussein5481-hash/masar-launcher)"
    private const val BASE = "https://all.api.radio-browser.info/json/stations"

    /** Most-voted working stations for a country (default Saudi Arabia). Blocks. */
    fun top(countryCode: String = "SA", limit: Int = 60): List<Station> =
        fetch("$BASE/bycountrycodeexact/$countryCode?order=votes&reverse=true&hidebroken=true&limit=$limit")

    /** Working stations whose name matches [query], anywhere. Blocks. */
    fun search(query: String, limit: Int = 60): List<Station> {
        val q = URLEncoder.encode(query, "UTF-8")
        return fetch("$BASE/byname/$q?order=votes&reverse=true&hidebroken=true&limit=$limit")
    }

    private fun fetch(spec: String): List<Station> {
        val conn = URL(spec).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 10000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        try {
            if (conn.responseCode != 200) return emptyList()
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val array = JSONArray(body)
            val out = ArrayList<Station>()
            val seen = HashSet<String>()
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val url = o.optString("url_resolved").ifEmpty { o.optString("url") }
                val name = o.optString("name").trim()
                if (url.isEmpty() || name.isEmpty()) continue
                // One entry per stream.
                if (!seen.add(url)) continue
                out.add(
                    Station(
                        o.optString("stationuuid"),
                        name,
                        url,
                        o.optString("codec"),
                        o.optInt("bitrate"),
                        o.optInt("hls") == 1
                    )
                )
            }
            return out
        } finally {
            conn.disconnect()
        }
    }
}
