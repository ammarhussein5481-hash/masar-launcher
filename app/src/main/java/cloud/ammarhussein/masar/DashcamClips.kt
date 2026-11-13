package cloud.ammarhussein.masar

import android.net.Network
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** One recorded clip on the dash cam. [name] is the camera's own path, e.g. /mnt/mmc/Normal/.s_Front/NO….mp4 */
class Clip(val name: String, val sizeBytes: Long, val time: String, val event: Boolean) {
    /** Plain HTTP, seekable, no signature. Plays straight into a player. */
    val url: String get() = "http://192.168.1.1$name"
    /** Rear clips are marked only in the path and name on this camera. */
    val rear: Boolean get() = name.contains(".s_Rear") || name.endsWith("R.mp4")
}

/**
 * Lists the recordings on a 70mai A800S over its own Wi-Fi. The unit's whole
 * process is bound to the camera's network while the camera page is open (see
 * [DashCam]), so an ordinary connection reaches 192.168.1.1 here.
 *
 * Read-only: this asks only for the directory. Playing a clip is a plain GET of
 * [Clip.url]; neither needs the vendor app's signature.
 */
object DashcamClips {

    const val USER_AGENT = "Masar launcher"
    private const val BASE = "http://192.168.1.1"

    /**
     * The clips for one property (Normal, Event, NormalRear, Rear), newest first.
     * The request goes out [network] (the camera's Wi-Fi), because the unit's
     * default network is the phone hotspot, which can't reach the camera. Blocks.
     */
    @Throws(IOException::class)
    fun list(network: Network?, property: String, count: Int = 50): List<Clip> {
        val url = URL("$BASE/cgi-bin/Config.cgi?action=dir&property=$property&format=all&count=$count&from=0")
        val conn = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        try {
            if (conn.responseCode != 200) return emptyList()
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val event = property.startsWith("Event", ignoreCase = true)
            val clips = ArrayList<Clip>()
            for (match in FILE.findAll(body)) {
                val block = match.groupValues[1]
                val name = tag(block, "name") ?: continue
                val size = tag(block, "size")?.toLongOrNull() ?: 0L
                val time = tag(block, "time") ?: ""
                clips.add(Clip(name, size, time, event))
            }
            return clips
        } finally {
            conn.disconnect()
        }
    }

    private val FILE = Regex("<file>(.*?)</file>", RegexOption.DOT_MATCHES_ALL)

    private fun tag(block: String, name: String): String? =
        Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1)?.trim()
}
