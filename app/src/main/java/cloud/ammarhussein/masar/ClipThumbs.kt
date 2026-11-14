package cloud.ammarhussein.masar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Network
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * A thumbnail for each dash cam clip.
 *
 * Two ways to get one, tried in order: the camera's own thumbnail (/thumb/<clip
 * path>, cheap), otherwise, for front clips only, the clip's first frame pulled
 * over the camera's Wi-Fi (heavier). Results are cached for the session, and a
 * clip that yields nothing is remembered so it isn't tried again.
 *
 * Work runs on one background thread with a small gap between clips, because the
 * camera drops requests that arrive too fast. The whole process is already bound
 * to the camera's network while the clip list is open (see [DashCam]).
 */
object ClipThumbs {

    private const val MAX_CACHED = 80

    private val cache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean = size > MAX_CACHED
    }
    private val failed = HashSet<String>()
    private val inFlight = HashSet<String>()
    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    @Synchronized
    fun cached(name: String): Bitmap? = cache[name]

    /**
     * Asks for [clip]'s thumbnail at about [w]x[h] px. If it is ready it is cached
     * and [onReady] is called on the main thread; a clip with no thumbnail calls
     * nothing. Safe to call for the same clip repeatedly.
     */
    fun request(clip: Clip, network: Network?, w: Int, h: Int, onReady: (Bitmap) -> Unit) {
        val name = clip.name
        synchronized(this) {
            cache[name]?.let {
                onReady(it)
                return
            }
            if (failed.contains(name) || inFlight.contains(name)) return
            inFlight.add(name)
        }
        worker.execute {
            val bitmap = load(clip, network, w, h)
            synchronized(this) {
                inFlight.remove(name)
                if (bitmap != null) cache[name] = bitmap else failed.add(name)
            }
            if (bitmap != null) ui.post { onReady(bitmap) }
            // Ease off the camera between clips.
            try {
                Thread.sleep(250)
            } catch (ex: InterruptedException) {
                // Carry on
            }
        }
    }

    private fun load(clip: Clip, network: Network?, w: Int, h: Int): Bitmap? {
        // The camera serves /thumb/<clip path> for front clips; <clip>.jpg is always a 404, so it isn't asked for.
        fetchImage(network, "http://192.168.1.1/thumb${clip.name}")?.let { return it }
        // Rear clips are twice the size of front ones; don't pull frames from them, the placeholder will do.
        return if (clip.rear) null else firstFrame(clip.url, w, h)
    }

    private fun fetchImage(network: Network?, url: String): Bitmap? {
        var conn: HttpURLConnection? = null
        return try {
            val target = URL(url)
            conn = (network?.openConnection(target) ?: target.openConnection()) as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.setRequestProperty("User-Agent", DashcamClips.USER_AGENT)
            if (conn.responseCode != 200) return null
            val type = conn.contentType ?: ""
            if (!type.startsWith("image", ignoreCase = true)) return null
            // A real thumbnail is small; anything large is not one.
            if (conn.contentLength > 2_000_000) return null
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (ex: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun firstFrame(url: String, w: Int, h: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(url, HashMap<String, String>())
            retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h)
        } catch (ex: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (ex: Exception) {
                // Already released
            }
        }
    }
}
