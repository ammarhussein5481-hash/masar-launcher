package cloud.ammarhussein.masar

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
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
 * for as long as the view is open. [open] switches the unit's Wi-Fi to the
 * camera, points this app's traffic at it, and plays the front and rear
 * streams. [close] removes the camera's network again and tells Android to
 * reconnect, which brings it back to the saved hotspot.
 *
 * Switching Wi-Fi directly is something Android only allows apps built for
 * Android 9 and earlier, which is why Masar's targetSdk is 28. If that route
 * is refused (the camera's network was also saved by hand in Settings, say),
 * [open] falls back to asking Android, which shows its "connect to device"
 * sheet.
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
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /** The network Masar added for the camera, or -1. It is removed again on close. */
    private var addedId = -1
    private var joined = false
    private var tries = 0
    /** Whether to play the live streams once joined. Clips mode joins without them. */
    private var live = true

    var isOpen = false
        private set

    fun open(ssidPrefix: String, password: String, front: TextureView, rear: TextureView, live: Boolean = true) {
        if (isOpen) return
        isOpen = true
        this.live = live
        views[0] = front
        views[1] = rear
        // Already on the camera's Wi-Fi (joined by hand in Settings)? Then there
        // is nothing to ask Android for: use the connection as it is.
        val already = currentCameraNetwork(ssidPrefix)
        if (already != null) {
            cm.bindProcessToNetwork(already)
            listener.onCamState(JOINED, -1)
            ui.postDelayed({ if (isOpen && this.live) { play(0); play(1) } }, 300)
            return
        }
        listener.onCamState(JOINING, -1)
        joined = false
        tries = 0
        if (context.applicationInfo.targetSdkVersion < 29 && wifi.isWifiEnabled) {
            switchTo(ssidPrefix, password)
        } else {
            askAndroid(ssidPrefix, password)
        }
    }

    // ---------------------------------------------------------------- joining, directly

    /** Finds the camera among the networks in range, adds it, and connects to it. */
    @Suppress("DEPRECATION")
    private fun switchTo(ssidPrefix: String, password: String) {
        if (!isOpen) return
        val name = try {
            wifi.scanResults.mapNotNull { it.SSID }.firstOrNull { it.startsWith(ssidPrefix) }
        } catch (ex: Exception) {
            null
        }
        if (name == null) {
            // Not in the last scan: look again, a few times.
            if (tries % 3 == 0) {
                try {
                    wifi.startScan()
                } catch (ex: Exception) {
                    // Scans are rationed
                }
            }
            if (++tries > 9) {
                fail(NOT_FOUND)
                return
            }
            ui.postDelayed({ switchTo(ssidPrefix, password) }, 1500)
            return
        }
        val config = WifiConfiguration()
        config.SSID = "\"" + name + "\""
        config.preSharedKey = "\"" + password + "\""
        val id = try {
            wifi.addNetwork(config)
        } catch (ex: Exception) {
            -1
        }
        if (id == -1) {
            // Refused, usually because the same network is already saved by hand.
            askAndroid(ssidPrefix, password)
            return
        }
        addedId = id
        wifi.disconnect()
        wifi.enableNetwork(id, true)
        wifi.reconnect()
        tries = 0
        waitForCamera(ssidPrefix)
    }

    /** Polls until the unit is on the camera's network with an address, then starts the video. */
    private fun waitForCamera(ssidPrefix: String) {
        if (!isOpen) return
        val network = currentCameraNetwork(ssidPrefix)
        val ready = network != null && try {
            cm.getLinkProperties(network)?.linkAddresses?.any { it.address is java.net.Inet4Address } == true
        } catch (ex: Exception) {
            false
        }
        if (network != null && ready) {
            joined = true
            cm.bindProcessToNetwork(network)
            listener.onCamState(JOINED, -1)
            ui.postDelayed({ if (isOpen && this.live) { play(0); play(1) } }, 500)
            ui.postDelayed({ watchLink(ssidPrefix) }, 4000)
            return
        }
        if (++tries > 40) {
            fail(NOT_FOUND)
            return
        }
        ui.postDelayed({ waitForCamera(ssidPrefix) }, 750)
    }

    /** While the camera is showing: notices if its Wi-Fi goes away. */
    private fun watchLink(ssidPrefix: String) {
        if (!isOpen || !joined) return
        if (currentCameraNetwork(ssidPrefix) == null) {
            fail(LOST)
            return
        }
        ui.postDelayed({ watchLink(ssidPrefix) }, 3000)
    }

    /**
     * Removes any camera network Masar added and never took away, for instance
     * because the unit lost power while the camera was showing. Left in place,
     * Android could join the camera by itself instead of the hotspot.
     */
    @Suppress("DEPRECATION")
    fun tidy(ssidPrefix: String) {
        if (isOpen || ssidPrefix.isEmpty() || context.applicationInfo.targetSdkVersion >= 29) return
        try {
            for (config in wifi.configuredNetworks ?: emptyList()) {
                val name = (config.SSID ?: "").trim('"')
                // Only networks this app created can be removed; the call just fails for the others.
                if (name.startsWith(ssidPrefix)) wifi.removeNetwork(config.networkId)
            }
        } catch (ex: Exception) {
            // No permission to list them: nothing to tidy
        }
    }

    // ---------------------------------------------------------------- joining, by asking Android

    private fun askAndroid(ssidPrefix: String, password: String) {
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
                    ui.postDelayed({ if (isOpen && this.live) { play(0); play(1) } }, 800)
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

    /** The Wi-Fi network the unit is on right now, if its name starts with [ssidPrefix]. */
    @Suppress("DEPRECATION")
    private fun currentCameraNetwork(ssidPrefix: String): Network? {
        try {
            val name = (wifi.connectionInfo?.ssid ?: "").trim('"')
            if (ssidPrefix.isEmpty() || !name.startsWith(ssidPrefix)) return null
            return cm.allNetworks.firstOrNull {
                cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        } catch (ex: Exception) {
            return null
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

    /** Stops the live streams but keeps the camera's Wi-Fi, for switching to the clip list. */
    fun stopLive() {
        live = false
        stop(0)
        stop(1)
    }

    /** Starts the live streams again after the clip list, if the camera is still joined. */
    fun startLive() {
        if (!isOpen) return
        live = true
        play(0)
        play(1)
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
        joined = false
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
        leaveCamera()
        // Nudge Android to look for the hotspot now, instead of at its next
        // routine scan, so the internet comes back sooner.
        ui.postDelayed({ rescan() }, 1500)
        listener.onCamState(CLOSED, -1)
    }

    /** Takes the camera's network away again and has Android reconnect to what is saved. */
    @Suppress("DEPRECATION")
    private fun leaveCamera() {
        val id = addedId
        addedId = -1
        if (id == -1) return
        try {
            wifi.disableNetwork(id)
            wifi.removeNetwork(id)
            wifi.disconnect()
            wifi.reconnect()
        } catch (ex: Exception) {
            // Android will find the hotspot at its next scan
        }
    }

    @Suppress("DEPRECATION")
    private fun rescan() {
        try {
            wifi.startScan()
        } catch (ex: Exception) {
            // Scans are rationed; the routine one will do
        }
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
