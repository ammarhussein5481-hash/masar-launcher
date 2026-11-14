package cloud.ammarhussein.masar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * Plays one internet radio station, and keeps playing while other apps are in
 * front — a foreground service so the audio isn't cut when Masar leaves the
 * screen. Controlled by start intents: [ACTION_PLAY] with a URL and name, and
 * [ACTION_STOP]. It asks for audio focus, so it pauses for calls and ducks for
 * navigation prompts.
 */
@UnstableApi
class RadioService : Service() {

    private var player: ExoPlayer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                nowPlaying = intent.getStringExtra(EXTRA_NAME) ?: ""
                play(url)
            }
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun play(url: String) {
        goForeground()
        val existing = player
        val p = existing ?: ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true // handle audio focus: pause for calls, duck for prompts
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also { built ->
                player = built
                built.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        notifyChange()
                    }
                })
            }
        p.setMediaItem(MediaItem.fromUri(url))
        p.playWhenReady = true
        p.prepare()
        notifyChange()
    }

    private fun goForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL, getString(R.string.radio_channel), NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        nm.createNotificationChannel(channel)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, RadioService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentTitle(nowPlaying.ifEmpty { getString(R.string.radio_title) })
            .setContentText(getString(R.string.radio_playing))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.radio_stop), stop).build())
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        if (current === this) current = null
        nowPlaying = ""
        try {
            player?.release()
        } catch (ex: Exception) {
            // Already released
        }
        player = null
        notifyChange()
        super.onDestroy()
    }

    private fun notifyChange() {
        current = this
        onChange?.invoke()
    }

    companion object {
        const val ACTION_PLAY = "cloud.ammarhussein.masar.RADIO_PLAY"
        const val ACTION_STOP = "cloud.ammarhussein.masar.RADIO_STOP"
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"
        private const val CHANNEL = "radio"
        private const val NOTIFICATION_ID = 2

        private var current: RadioService? = null

        /** The station now playing, "" when stopped. */
        @Volatile
        var nowPlaying: String = ""
            private set

        /** True while a station is loaded. */
        val isOn: Boolean get() = current != null && nowPlaying.isNotEmpty()

        /** Called on the main thread when playback starts or stops, for the UI. */
        var onChange: (() -> Unit)? = null

        fun play(context: Context, station: Station) {
            val intent = Intent(context, RadioService::class.java)
                .setAction(ACTION_PLAY)
                .putExtra(EXTRA_URL, station.url)
                .putExtra(EXTRA_NAME, station.name)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RadioService::class.java).setAction(ACTION_STOP))
        }
    }
}
