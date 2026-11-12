package cloud.ammarhussein.masar

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Handler
import android.os.Looper
import android.os.PatternMatcher
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource

/**
 * Live view from a Wi-Fi dash cam (written for the 70mai A800S).
 *
 * The camera is its own Wi-Fi network with no internet, and a head unit has
 * one Wi-Fi radio, so looking at the camera means leaving the phone's hotspot
 * for as long as the view is open. [open] asks Android to join the camera's
 * network (the system shows its own "connect to device" sheet), points this
 * app's traffic at it, and plays the front and rear streams. [close] lets go
 * of the network and Android goes back to the hotspot by itself.
 *
 * The streams are plain RTSP with no password: H.264, 1280x720.
 */
@OptIn(UnstableApi::class)
class DashCam(private val context: Context, private val listener: Listener) {

    interface Listener {
        /** [state] is one of the constants below; [pane] is 0 for front, 1 for rear, -1 for the link itself. */
        fun onCamState(state: Int, pane: Int)
    }

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val ui = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null
    private val players = arrayOfNulls<ExoPlayer>(2)
    private val views = arrayOfNulls<TextureView>(2)
    private val retries = IntArray(2)

    var isOpen = false
        private set

    fun open(ssidPrefix: String, password: String, front: TextureView, rear: TextureView) {
        if (isOpen) return
        isOpen = true
        views[0] = front
        views[1] = rear
        listener.onCamState(JOINING, -1)
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsidPattern(PatternMatcher(ssidPrefix, PatternMatcher.PATTERN_PREFIX))
            .setWpa2Passphrase(password)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val made = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                ui.post {
                    if (!isOpen) return@post
                    // The camera has no internet, so Android would not route to it by itself.
                    cm.bindProcessToNetwork(network)
                    listener.onCamState(JOINED, -1)
                    // A moment for the camera to hand out an address before asking for video.
                    ui.postDelayed({ if (isOpen) { play(0); play(1) } }, 800)
                }
            }

            override fun onUnavailable() {
                ui.post { if (isOpen) fail(NOT_FOUND) }
            }

            override fun onLost(network: Network) {
                ui.post { if (isOpen) fail(LOST) }
            }
        }
        callback = made
        try {
            cm.requestNetwork(request, made, JOIN_TIMEOUT_MS)
        } catch (ex: Exception) {
            fail(NOT_FOUND)
        }
    }

    private fun play(pane: Int) {
        val view = views[pane] ?: return
        stop(pane)
        val load = DefaultLoadControl.Builder()
            // A live picture: start as soon as there is anything to show.
            .setBufferDurationsMs(500, 1500, 250, 500)
            .build()
        val player = ExoPlayer.Builder(context).setLoadControl(load).build()
        players[pane] = player
        player.volume = 0f
        player.setVideoTextureView(view)
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                retries[pane] = 0
                listener.onCamState(PLAYING, pane)
            }

            override fun onPlayerError(error: PlaybackException) {
                again(pane)
            }

            override fun onPlaybackStateChanged(state: Int) {
                // A live stream should never end; if the camera drops it, ask again.
                if (state == Player.STATE_ENDED) again(pane)
            }
        })
        val source = RtspMediaSource.Factory()
            .setTimeoutMs(8000)
            .createMediaSource(MediaItem.fromUri(URLS[pane]))
        player.setMediaSource(source)
        player.playWhenReady = true
        player.prepare()
        listener.onCamState(STARTING, pane)
    }

    private fun again(pane: Int) {
        if (!isOpen) return
        if (++retries[pane] > MAX_RETRIES) {
            stop(pane)
            listener.onCamState(NO_VIDEO, pane)
            return
        }
        listener.onCamState(STARTING, pane)
        ui.postDelayed({ if (isOpen) play(pane) }, 1500)
    }

    private fun stop(pane: Int) {
        val player = players[pane] ?: return
        players[pane] = null
        try {
            player.clearVideoSurface()
            player.release()
        } catch (ex: Exception) {
            // Already released
        }
    }

    private fun fail(state: Int) {
        close()
        listener.onCamState(state, -1)
    }

    /** Stops the video and gives the Wi-Fi back to the hotspot. */
    fun close() {
        if (!isOpen) return
        isOpen = false
        ui.removeCallbacksAndMessages(null)
        stop(0)
        stop(1)
        retries[0] = 0
        retries[1] = 0
        try {
            cm.bindProcessToNetwork(null)
        } catch (ex: Exception) {
            // Not bound
        }
        val made = callback
        callback = null
        if (made != null) {
            try {
                cm.unregisterNetworkCallback(made)
            } catch (ex: Exception) {
                // Already released
            }
        }
        listener.onCamState(CLOSED, -1)
    }

    companion object {
        const val CLOSED = 0
        const val JOINING = 1
        const val JOINED = 2
        const val STARTING = 3
        const val PLAYING = 4
        const val NO_VIDEO = 5
        const val NOT_FOUND = 6
        const val LOST = 7

        private const val JOIN_TIMEOUT_MS = 45_000
        private const val MAX_RETRIES = 4

        // Front and rear on the 70mai A800S.
        private val URLS = arrayOf(
            "rtsp://192.168.1.1:554/liveRTSP/av4",
            "rtsp://192.168.1.1:554/liveRTSP/av5"
        )
    }
}
