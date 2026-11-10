package cloud.ammarhussein.masar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A course-up compass: the dial turns so the direction of travel is always at
 * the top, under a fixed marker.
 *
 * The heading comes from GPS, so it is only known once the car has moved.
 */
class CompassView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** Degrees clockwise from north, or null while there is no heading. */
    var heading: Float? = null
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Shown under the dash while there is no heading. */
    var hint: String = ""

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = context.getColor(R.color.tile_stroke)
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        color = context.getColor(R.color.text)
    }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getColor(R.color.accent)
    }
    private val markerPath = Path()

    private val colorText = context.getColor(R.color.text)
    private val colorDim = context.getColor(R.color.text_dim)
    private val colorIdle = context.getColor(R.color.idle)
    private val colorAccent = context.getColor(R.color.accent)

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        // Leave room above the ring for the marker.
        val r = min(width, height) / 2f * 0.86f
        if (r <= 0f) return

        val shown = heading
        val turn = -(shown ?: 0f)

        ring.strokeWidth = r * 0.02f
        canvas.drawCircle(cx, cy, r, ring)

        // A tick every 15°, longer and brighter on the eight main directions.
        for (deg in 0 until 360 step 15) {
            val main = deg % 45 == 0
            val angle = Math.toRadians((deg + turn - 90f).toDouble())
            val dx = cos(angle).toFloat()
            val dy = sin(angle).toFloat()
            val inner = r * (if (main) 0.86f else 0.91f)
            val outer = r * 0.97f
            tick.color = if (main) colorDim else colorIdle
            tick.strokeWidth = r * (if (main) 0.022f else 0.012f)
            canvas.drawLine(cx + inner * dx, cy + inner * dy, cx + outer * dx, cy + outer * dy, tick)
        }

        // The letters ride the dial but stay upright.
        letter.textSize = r * 0.2f
        val letterShift = (letter.descent() + letter.ascent()) / 2f
        for (i in LETTERS.indices) {
            val angle = Math.toRadians((i * 90 + turn - 90f).toDouble())
            letter.color = if (i == 0) colorAccent else colorText
            canvas.drawText(
                LETTERS[i],
                cx + r * 0.7f * cos(angle).toFloat(),
                cy + r * 0.7f * sin(angle).toFloat() - letterShift,
                letter
            )
        }

        // Fixed marker at the top: where the car is pointing.
        val half = r * 0.1f
        markerPath.rewind()
        markerPath.moveTo(cx, cy - r * 0.9f)
        markerPath.lineTo(cx - half, cy - r * 1.13f)
        markerPath.lineTo(cx + half, cy - r * 1.13f)
        markerPath.close()
        canvas.drawPath(markerPath, marker)

        value.textSize = r * 0.4f
        letter.textSize = r * 0.15f
        if (shown == null) {
            canvas.drawText("–", cx, cy + r * 0.05f, value)
            letter.color = colorDim
            letter.textSize = r * 0.11f
            canvas.drawText(hint, cx, cy + r * 0.3f, letter)
        } else {
            val degrees = ((shown.roundToInt() % 360) + 360) % 360
            canvas.drawText("$degrees°", cx, cy + r * 0.1f, value)
            letter.color = colorAccent
            canvas.drawText(cardinal(shown), cx, cy + r * 0.34f, letter)
        }
    }

    companion object {
        private val LETTERS = arrayOf("N", "E", "S", "W")
        private val CARDINALS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

        /** "N", "NE", "E" … for a bearing in degrees. */
        fun cardinal(bearing: Float): String {
            val normal = ((bearing % 360f) + 360f) % 360f
            return CARDINALS[((normal + 22.5f) / 45f).toInt() % 8]
        }
    }
}
