package cloud.ammarhussein.masar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.util.LruCache
import android.view.View
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * A small north-up map that keeps the car in the middle.
 *
 * It draws OpenStreetMap tiles, recoloured to suit the dark home screen. Tiles
 * are kept on disk, so roads already driven still show without a connection.
 * There is no panning: it is a glance at where you are, and a tap opens the
 * real navigation app.
 */
class TileMapView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var lat = Double.NaN
    private var lon = Double.NaN
    private var bearing: Float? = null

    var zoom = DEFAULT_ZOOM
        set(value) {
            val wanted = value.coerceIn(MIN_ZOOM, MAX_ZOOM)
            if (field != wanted) {
                field = wanted
                invalidate()
            }
        }

    /** Shown in the middle while there is no position, and while no tile could be drawn. */
    var waitingText: String = ""
    var noDataText: String = ""

    private val dp = resources.displayMetrics.density
    // Tiles are 256 px; scale them with the screen density so the labels stay readable.
    private val tileSize = (256 * dp).roundToInt()

    private val memory = LruCache<String, Bitmap>(48)
    // These two are only touched on the main thread.
    private val loading = HashSet<String>()
    private val failedAt = HashMap<String, Long>()
    private val pool = Executors.newFixedThreadPool(2)
    private val diskDir = File(context.cacheDir, "tiles")

    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        // Invert the brightness and turn the hue back, so a light map becomes a
        // dark one while water stays blue and parks stay green.
        colorFilter = ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    0.574f, -1.430f, -0.144f, 0f, 255f,
                    -0.426f, -0.430f, -0.144f, 0f, 255f,
                    -0.426f, -1.430f, 0.856f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    }
    private val background = context.getColor(R.color.tile)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = context.getColor(R.color.text_dim)
    }
    private val creditPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        textSize = 10f * dp
        color = context.getColor(R.color.text_dim)
    }
    private val creditBack = Paint().apply { color = 0xB00A0C0F.toInt() }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent) }

    /** The colour of the car's marker. */
    var accent: Int
        get() = dot.color
        set(value) {
            dot.color = value
            invalidate()
        }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * dp
        color = context.getColor(R.color.text)
    }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp
        color = context.getColor(R.color.tile_stroke)
    }

    private val clip = Path()
    private val bounds = RectF()
    private val dst = Rect()
    private val arrow = Path()

    init {
        pool.execute { prune() }
    }

    fun setPosition(lat: Double, lon: Double, bearing: Float?) {
        this.lat = lat
        this.lon = lon
        this.bearing = bearing
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bounds.set(0f, 0f, w.toFloat(), h.toFloat())
        clip.rewind()
        clip.addRoundRect(bounds, CORNER_DP * dp, CORNER_DP * dp, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawColor(background)
        textPaint.textSize = height * 0.05f

        if (lat.isNaN() || lon.isNaN()) {
            canvas.drawText(waitingText, width / 2f, height / 2f, textPaint)
        } else {
            drawMap(canvas)
        }
        canvas.restore()

        val inset = dp / 2f
        bounds.set(inset, inset, width - inset, height - inset)
        canvas.drawRoundRect(bounds, CORNER_DP * dp, CORNER_DP * dp, border)
    }

    private fun drawMap(canvas: Canvas) {
        // Position in tile units at this zoom (Web Mercator).
        val count = 1 shl zoom
        val latRad = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        val x = (lon + 180.0) / 360.0 * count
        val y = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * count

        val halfW = width / 2.0
        val halfH = height / 2.0
        val firstX = floor(x - halfW / tileSize).toInt()
        val lastX = floor(x + halfW / tileSize).toInt()
        val firstY = floor(y - halfH / tileSize).toInt()
        val lastY = floor(y + halfH / tileSize).toInt()

        var drawn = 0
        for (ty in firstY..lastY) {
            if (ty < 0 || ty >= count) continue
            for (tx in firstX..lastX) {
                val bitmap = tile(zoom, ((tx % count) + count) % count, ty) ?: continue
                dst.set(
                    (halfW + (tx - x) * tileSize).roundToInt(),
                    (halfH + (ty - y) * tileSize).roundToInt(),
                    (halfW + (tx + 1 - x) * tileSize).roundToInt(),
                    (halfH + (ty + 1 - y) * tileSize).roundToInt()
                )
                canvas.drawBitmap(bitmap, null, dst, tilePaint)
                drawn++
            }
        }
        if (drawn == 0) {
            canvas.drawText(noDataText, width / 2f, height * 0.3f, textPaint)
        }

        // The car: a dot, with a pointer ahead of it once the heading is known.
        val cx = width / 2f
        val cy = height / 2f
        val radius = 7f * dp
        val heading = bearing
        if (heading != null) {
            canvas.save()
            canvas.rotate(heading, cx, cy)
            arrow.rewind()
            arrow.moveTo(cx, cy - radius * 3.2f)
            arrow.lineTo(cx - radius * 0.95f, cy - radius * 1.5f)
            arrow.lineTo(cx + radius * 0.95f, cy - radius * 1.5f)
            arrow.close()
            canvas.drawPath(arrow, dot)
            canvas.restore()
        }
        canvas.drawCircle(cx, cy, radius, dot)
        canvas.drawCircle(cx, cy, radius, dotRing)

        // The tiles' licence asks for this credit to stay visible.
        val pad = 6f * dp
        val creditWidth = creditPaint.measureText(CREDIT)
        canvas.drawRect(width - creditWidth - pad * 2, height - creditPaint.textSize - pad * 1.5f, width.toFloat(), height.toFloat(), creditBack)
        canvas.drawText(CREDIT, width - pad, height - pad, creditPaint)
    }

    /** The tile if it is already in memory; otherwise starts loading it and returns null. */
    private fun tile(z: Int, x: Int, y: Int): Bitmap? {
        val key = "$z/$x/$y"
        memory.get(key)?.let { return it }
        if (key in loading) return null
        val failed = failedAt[key]
        if (failed != null && SystemClock.elapsedRealtime() - failed < RETRY_MS) return null

        loading.add(key)
        pool.execute {
            val bitmap = load(key)
            post {
                loading.remove(key)
                if (bitmap != null) {
                    memory.put(key, bitmap)
                    failedAt.remove(key)
                    invalidate()
                } else {
                    failedAt[key] = SystemClock.elapsedRealtime()
                }
            }
        }
        return null
    }

    /** Disk first, then the network. Runs on a pool thread. */
    private fun load(key: String): Bitmap? {
        val file = File(diskDir, "$key.png")
        try {
            if (file.length() > 0) {
                BitmapFactory.decodeFile(file.path)?.let { return it }
            }
            val connection = URL("$TILE_URL/$key.png").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.setRequestProperty("User-Agent", Weather.USER_AGENT)
                if (connection.responseCode != 200) return null
                val bytes = connection.inputStream.use { it.readBytes() }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
                return bitmap
            } finally {
                connection.disconnect()
            }
        } catch (ex: Exception) {
            // Offline or a bad tile: the caller retries later
            return null
        }
    }

    /** Keeps the tile cache from growing without limit: drops the oldest tiles first. */
    private fun prune() {
        try {
            val files = diskDir.walkTopDown().filter { it.isFile }.toList()
            var total = 0L
            for (file in files) total += file.length()
            if (total <= DISK_LIMIT) return
            for (file in files.sortedBy { it.lastModified() }) {
                if (total <= DISK_LIMIT * 3 / 4) break
                total -= file.length()
                file.delete()
            }
        } catch (ex: Exception) {
            // The cache is only a convenience
        }
    }

    companion object {
        const val DEFAULT_ZOOM = 15
        const val MIN_ZOOM = 4
        const val MAX_ZOOM = 18

        private const val TILE_URL = "https://tile.openstreetmap.org"
        private const val CREDIT = "© OpenStreetMap"
        private const val CORNER_DP = 18f
        private const val RETRY_MS = 20_000L
        private const val DISK_LIMIT = 80L * 1024 * 1024
    }
}
