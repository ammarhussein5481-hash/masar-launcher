package cloud.ammarhussein.masar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Station logos, fetched once and kept for the session. Many stations have no
 * logo, or one that will not load; those are remembered so they are not tried
 * again, and the page draws the station's first letter instead.
 */
object StationLogos {

    private const val MAX_CACHED = 150
    private const val MAX_BYTES = 600_000
    private const val SIDE = 256

    private val cache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean = size > MAX_CACHED
    }
    private val failed = HashSet<String>()
    private val waiting = HashMap<String, ArrayList<(Bitmap) -> Unit>>()
    private val workers = Executors.newFixedThreadPool(3)
    private val ui = Handler(Looper.getMainLooper())

    @Synchronized
    fun cached(url: String): Bitmap? = cache[url]

    /** Calls [onReady] on the main thread once the logo at [url] has loaded; never, if it has none. */
    fun request(url: String, onReady: (Bitmap) -> Unit) {
        if (!url.startsWith("http")) return
        synchronized(this) {
            if (failed.contains(url)) return
            val queue = waiting[url]
            if (queue != null) {
                queue.add(onReady)
                return
            }
            waiting[url] = arrayListOf(onReady)
        }
        workers.execute {
            val bitmap = try {
                load(url)
            } catch (ex: Exception) {
                null
            }
            val callbacks: List<(Bitmap) -> Unit>
            synchronized(this) {
                callbacks = waiting.remove(url) ?: emptyList()
                if (bitmap != null) cache[url] = bitmap else failed.add(url)
            }
            if (bitmap != null) ui.post { for (c in callbacks) c(bitmap) }
        }
    }

    private fun load(url: String): Bitmap? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 8000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", Radio.USER_AGENT)
        try {
            if (conn.responseCode != 200) return null
            val bytes = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    bytes.write(buffer, 0, n)
                    if (bytes.size() > MAX_BYTES) return null
                }
            }
            val data = bytes.toByteArray()
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options()
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIDE) sample *= 2
            options.inSampleSize = sample
            return BitmapFactory.decodeByteArray(data, 0, data.size, options)
        } finally {
            conn.disconnect()
        }
    }
}
