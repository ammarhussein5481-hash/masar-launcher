package cloud.ammarhussein.masar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * Plays internet radio, and keeps playing while other apps are in front.
 *
 * It holds a queue (the list the station was picked from) so Next and Previous
 * work from anywhere: the Radio page, the home page's now-playing strip, the
 * notification, or the car's media keys, all through one media session.
 *
 * The car is online through a phone hotspot, so a stream that drops is simply
 * tried again, slowly, until it comes back or the driver stops it. Pausing
 * lets go of the stream, so resuming is always live and never an old buffer.
 * Audio focus is respected: it pauses for calls and ducks for navigation.
 */
@UnstableApi
class RadioService : Service() {

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private val ui = Handler(Looper.getMainLooper())
    /** True while the driver wants sound, whatever the stream is doing. */
    private var wanted = false
    private var retries = 0
    private val retry = Runnable { load() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        current = this
        val s = MediaSession(this, "MasarRadio")
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = start()
            override fun onPause() = pause()
            override fun onStop() = stopSelf()
            override fun onSkipToNext() = step(1)
            override fun onSkipToPrevious() = step(-1)
        })
        s.isActive = true
        session = s
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                goForeground()
                start()
            }
            ACTION_TOGGLE -> if (wanted) pause() else start()
            ACTION_NEXT -> step(1)
            ACTION_PREV -> step(-1)
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    /** Plays [station] from the top. */
    private fun start() {
        val s = station
        if (s == null) {
            stopSelf()
            return
        }
        wanted = true
        retries = 0
        // The station and the list it came from, so after a restart Play, the card and the
        // dial all pick up exactly here.
        Options.prefs(this).edit()
            .putString(PREF_LAST, s.toJson())
            .putString(PREF_LAST_LIST, Station.listToJson(queue))
            .apply()
        load()
    }

    private fun load() {
        val s = station ?: return
        if (!wanted) return
        ui.removeCallbacks(retry)
        val p = player ?: build()
        val item = MediaItem.Builder().setUri(s.url)
        if (s.hls) item.setMimeType(MimeTypes.APPLICATION_M3U8)
        p.setMediaItem(item.build())
        p.playWhenReady = true
        p.prepare()
        if (state != RETRYING) {
            title = ""
            state = CONNECTING
        }
        publish()
    }

    private fun pause() {
        wanted = false
        ui.removeCallbacks(retry)
        player?.stop()
        state = PAUSED
        publish()
    }

    /** Moves along the queue, wrapping at the ends. */
    private fun step(by: Int) {
        val list = queue
        if (list.size < 2) return
        index = ((index + by) % list.size + list.size) % list.size
        station = list[index]
        state = STOPPED
        start()
    }

    private fun build(): ExoPlayer {
        // Stations often redirect between http and https; the stock source refuses that.
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Radio.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(12_000)
        val built = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true // handle audio focus: pause for calls, duck for prompts
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        built.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!wanted) return
                if (isPlaying) {
                    retries = 0
                    state = PLAYING
                } else if (built.playbackState == Player.STATE_READY) {
                    // Held back for a call or another app's sound; it resumes by itself.
                    state = PAUSED
                }
                publish()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (!wanted) return
                if (playbackState == Player.STATE_BUFFERING && state == PLAYING) {
                    state = CONNECTING
                    publish()
                } else if (playbackState == Player.STATE_ENDED) {
                    // A live stream only "ends" when the connection is lost.
                    again()
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // Another app took the sound for good (CarPlay music, a video): stop asking.
                if (!playWhenReady && wanted) pause()
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!wanted) return
                // 404 or 410: the stream is gone for good, so retrying only shows a false hope.
                if (gone(error)) offline() else again()
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                // The song or programme name, where the station sends one.
                val text = mediaMetadata.title?.toString()?.trim() ?: ""
                val shown = if (text.equals(station?.name, ignoreCase = true) || text == "-") "" else text
                if (shown != title) {
                    title = shown
                    publish()
                }
            }
        })
        player = built
        return built
    }

    /** True when the server says the stream no longer exists (HTTP 404 or 410), not just that it dropped. */
    private fun gone(error: PlaybackException): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) return cause.responseCode == 404 || cause.responseCode == 410
            cause = cause.cause
        }
        return false
    }

    /** Stops for good on a station that is gone, and says so. Play tries it once more. */
    private fun offline() {
        wanted = false
        ui.removeCallbacks(retry)
        player?.stop()
        state = OFFLINE
        publish()
    }

    /** The stream dropped: wait a little longer each time, then try it again. */
    private fun again() {
        retries++
        if (retries > MAX_RETRIES) {
            pause()
            return
        }
        state = RETRYING
        publish()
        ui.removeCallbacks(retry)
        ui.postDelayed(retry, minOf(15_000L, 2_000L * retries))
    }

    /** Tells the media session, the notification and the page what is going on. */
    private fun publish() {
        val s = station
        val name = s?.name ?: getString(R.string.radio_title)
        session?.setMetadata(
            android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, title.ifEmpty { name })
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, if (title.isEmpty()) getString(R.string.radio_title) else name)
                .build()
        )
        val playback = when (state) {
            PLAYING -> PlaybackState.STATE_PLAYING
            CONNECTING, RETRYING -> PlaybackState.STATE_BUFFERING
            else -> PlaybackState.STATE_PAUSED
        }
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(playback, PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (state == PLAYING) 1f else 0f)
                .build()
        )
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification())
        } catch (ex: Exception) {
            // The notification is only a convenience
        }
        onChange?.invoke()
    }

    private fun action(icon: Int, label: Int, action: String, code: Int): Notification.Action {
        val intent = PendingIntent.getService(
            this, code,
            Intent(this, RadioService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), getString(label), intent).build()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val sounding = state == PLAYING || state == CONNECTING || state == RETRYING
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentTitle(station?.name ?: getString(R.string.radio_title))
            .setContentText(title.ifEmpty { getString(stateText(state)) })
            .setContentIntent(open)
            .setOngoing(sounding)
            .addAction(action(R.drawable.ic_np_prev, R.string.np_previous, ACTION_PREV, 2))
            .addAction(action(if (sounding) R.drawable.ic_np_pause else R.drawable.ic_np_play, R.string.np_play_pause, ACTION_TOGGLE, 3))
            .addAction(action(R.drawable.ic_np_next, R.string.np_next, ACTION_NEXT, 4))
        val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
        session?.sessionToken?.let { style.setMediaSession(it) }
        return builder.setStyle(style).build()
    }

    private fun goForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL, getString(R.string.radio_channel), NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        nm.createNotificationChannel(channel)
        startForeground(NOTIFICATION_ID, notification())
    }

    override fun onDestroy() {
        ui.removeCallbacks(retry)
        wanted = false
        if (current === this) current = null
        state = STOPPED
        title = ""
        try {
            player?.release()
        } catch (ex: Exception) {
            // Already released
        }
        player = null
        try {
            session?.release()
        } catch (ex: Exception) {
            // Already released
        }
        session = null
        onChange?.invoke()
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAY = "cloud.ammarhussein.masar.RADIO_PLAY"
        const val ACTION_TOGGLE = "cloud.ammarhussein.masar.RADIO_TOGGLE"
        const val ACTION_NEXT = "cloud.ammarhussein.masar.RADIO_NEXT"
        const val ACTION_PREV = "cloud.ammarhussein.masar.RADIO_PREV"
        const val ACTION_STOP = "cloud.ammarhussein.masar.RADIO_STOP"
        const val PREF_LAST = "radio_last"
        const val PREF_LAST_LIST = "radio_last_list"
        private const val CHANNEL = "radio"
        private const val NOTIFICATION_ID = 2
        // About five minutes of trying before giving up on a stream.
        private const val MAX_RETRIES = 24

        const val STOPPED = 0
        const val CONNECTING = 1
        const val PLAYING = 2
        const val PAUSED = 3
        const val RETRYING = 4
        const val OFFLINE = 5

        private var current: RadioService? = null

        @Volatile var state = STOPPED
            private set
        /** The station loaded, playing or not; kept after a stop so Play brings it back. */
        @Volatile var station: Station? = null
            private set
        /** The song or programme the station says it is playing, "" if it does not say. */
        @Volatile var title = ""
            private set
        private var queue: List<Station> = emptyList()
        private var index = 0
        /** The list the station loaded was started from (a tab's stations, or the presets). */
        val list: List<Station> get() = queue

        /** True while sound is playing or being fetched. */
        val sounding: Boolean get() = state == PLAYING || state == CONNECTING || state == RETRYING

        /** Called on the main thread when anything above changes, for the UI. */
        var onChange: (() -> Unit)? = null

        fun stateText(state: Int): Int = when (state) {
            PLAYING -> R.string.radio_live
            CONNECTING -> R.string.radio_connecting
            RETRYING -> R.string.radio_retrying
            PAUSED -> R.string.radio_paused
            OFFLINE -> R.string.radio_station_offline
            else -> R.string.radio_stopped
        }

        /** Puts [fresh] in place of every loaded station matching [old] (a stream that moved). Main thread. */
        fun replace(old: (Station) -> Boolean, fresh: Station) {
            if (queue.any(old)) queue = queue.map { if (old(it)) fresh else it }
            val s = station ?: return
            if (!old(s)) return
            station = fresh
            if (state == OFFLINE) state = STOPPED
            onChange?.invoke()
        }

        /** Remembers what to play without starting it (the last station, at launch). Not while something is loaded. */
        fun prepare(list: List<Station>, at: Int) {
            if (current != null || station != null || list.isEmpty()) return
            queue = list
            index = at.coerceIn(0, list.size - 1)
            station = list[index]
        }

        fun play(context: Context, list: List<Station>, at: Int) {
            if (list.isEmpty()) return
            queue = ArrayList(list)
            index = at.coerceIn(0, list.size - 1)
            station = queue[index]
            state = STOPPED
            context.startForegroundService(Intent(context, RadioService::class.java).setAction(ACTION_PLAY))
        }

        /** Play or pause; from cold it starts the remembered station. */
        fun toggle(context: Context) {
            if (station == null) return
            if (current == null) {
                context.startForegroundService(Intent(context, RadioService::class.java).setAction(ACTION_PLAY))
            } else {
                context.startService(Intent(context, RadioService::class.java).setAction(ACTION_TOGGLE))
            }
        }

        fun skip(context: Context, by: Int) {
            if (queue.size < 2) return
            if (current == null) {
                index = ((index + by) % queue.size + queue.size) % queue.size
                station = queue[index]
                context.startForegroundService(Intent(context, RadioService::class.java).setAction(ACTION_PLAY))
            } else {
                context.startService(Intent(context, RadioService::class.java).setAction(if (by > 0) ACTION_NEXT else ACTION_PREV))
            }
        }

        fun stop(context: Context) {
            if (current != null) context.startService(Intent(context, RadioService::class.java).setAction(ACTION_STOP))
        }
    }
}
