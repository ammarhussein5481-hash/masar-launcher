package cloud.ammarhussein.masar

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One internet radio station. [url] is the playable stream, [favicon] its logo if it has one. */
class Station(
    val uuid: String,
    val name: String,
    val url: String,
    val codec: String,
    val bitrate: Int,
    val hls: Boolean,
    val favicon: String = ""
) {
    fun toJson(): String = JSONArray().put(uuid).put(name).put(url).put(codec).put(bitrate).put(hls).put(favicon).toString()

    companion object {
        fun fromJson(text: String): Station? = try {
            val a = JSONArray(text)
            Station(a.getString(0), a.getString(1), a.getString(2), a.optString(3), a.optInt(4), a.optBoolean(5), a.optString(6))
        } catch (ex: Exception) {
            null
        }

        fun listToJson(list: List<Station>): String {
            val array = JSONArray()
            for (s in list) array.put(s.toJson())
            return array.toString()
        }

        fun listFromJson(text: String?): List<Station> {
            val out = ArrayList<Station>()
            try {
                val array = JSONArray(text ?: "[]")
                for (i in 0 until array.length()) fromJson(array.getString(i))?.let { out.add(it) }
            } catch (ex: Exception) {
                // A damaged list reads as empty
            }
            return out
        }
    }
}

/**
 * Internet radio stations from radio-browser.info, a free directory with no key.
 * The directory keeps the stream URLs current, so Masar holds none of its own.
 */
object Radio {

    const val USER_AGENT = "Masar/1.0 (github.com/ammarhussein5481-hash/masar-launcher)"
    private const val BASE = "https://all.api.radio-browser.info/json/stations/search"

    /** What each category tab asks the directory for: Saudi, Quran, News, Music. */
    val CATEGORIES = arrayOf(
        "countrycode=SA",
        "tag=quran",
        "language=arabic&tag=news",
        "language=arabic&tag=music"
    )

    /** Working stations for one of [CATEGORIES], most listened first. Blocks. */
    fun category(filter: String): List<Station> = fetch(filter)

    /** Working stations whose name matches [query], anywhere. Blocks. */
    fun search(query: String): List<Station> = fetch("name=" + URLEncoder.encode(query, "UTF-8"))

    private fun fetch(filter: String): List<Station> {
        val conn = URL("$BASE?$filter&order=clickcount&reverse=true&hidebroken=true&limit=60").openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 10000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        try {
            if (conn.responseCode != 200) throw java.io.IOException("HTTP " + conn.responseCode)
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val array = JSONArray(body)
            val out = ArrayList<Station>()
            val seen = HashSet<String>()
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val url = o.optString("url_resolved").ifEmpty { o.optString("url") }
                val name = o.optString("name").trim().replace(Regex("\\s+"), " ")
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
                        o.optInt("hls") == 1,
                        o.optString("favicon")
                    )
                )
            }
            return out
        } finally {
            conn.disconnect()
        }
    }
}
