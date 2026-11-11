package cloud.ammarhussein.masar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** The rev counter on the Car page: a three-quarter arc that fills with the engine speed. */
class GaugeView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** Engine speed in rpm, or null while the car isn't answering. */
    var value: Int? = null
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    var accent: Int = context.getColor(R.color.accent)
        set(v) {
            field = v
            invalidate()
        }

    var unit: String = "rpm"

    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val box = RectF()
    private val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val normal = Typeface.create("sans-serif", Typeface.NORMAL)

    private val track = context.getColor(R.color.tile_stroke)
    private val colorText = context.getColor(R.color.text)
    private val colorDim = context.getColor(R.color.text_dim)
    private val colorIdle = context.getColor(R.color.idle)
    private val colorBad = context.getColor(R.color.bad)

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f * 0.88f
        if (r <= 0f) return
        box.set(cx - r, cy - r, cx + r, cy + r)
        arc.strokeWidth = r * 0.11f

        arc.color = track
        canvas.drawArc(box, START, SWEEP, false, arc)
        // The last stretch is the red line.
        arc.color = colorBad
        arc.alpha = 90
        canvas.drawArc(box, START + SWEEP * RED_FROM / MAX, SWEEP * (MAX - RED_FROM) / MAX, false, arc)
        arc.alpha = 255

        val shown = value
        if (shown != null && shown > 0) {
            arc.color = accent
            canvas.drawArc(box, START, SWEEP * min(shown, MAX) / MAX, false, arc)
        }

        // 0 to 6 around the inside, in thousands.
        text.typeface = normal
        text.textSize = r * 0.14f
        for (i in 0..6) {
            val angle = Math.toRadians((START + SWEEP * i / 6f).toDouble())
            text.color = if (i == 6) colorBad else colorIdle
            canvas.drawText(
                i.toString(),
                cx + r * 0.74f * cos(angle).toFloat(),
                cy + r * 0.74f * sin(angle).toFloat() + text.textSize * 0.35f,
                text
            )
        }

        text.typeface = light
        text.textSize = r * 0.58f
        text.color = colorText
        canvas.drawText(shown?.toString() ?: "–", cx, cy + r * 0.12f, text)
        text.typeface = normal
        text.textSize = r * 0.16f
        text.color = colorDim
        canvas.drawText(unit, cx, cy + r * 0.38f, text)
    }

    companion object {
        private const val START = 135f
        private const val SWEEP = 270f
        private const val MAX = 6000
        private const val RED_FROM = 5200
    }
}
