package cloud.ammarhussein.masar

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The analog radio's tuning scale. The stations of the list being played sit along
 * a lit scale that runs the full width and slides under a fixed red needle, as on an
 * old set: drag the scale and let go, and it settles on the nearest station and tunes
 * to it. A tap on a station's name goes straight there.
 *
 * Top to bottom: the station names (two rows, above the needle so it never cuts
 * through them), the needle, the scale with a marker per station, and the station
 * numbers (below the needle's tip, so it never hides them).
 */
class DialView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** The names along the scale, in order. Set with [show]. */
    var stations: List<String> = emptyList()
        private set

    /** The station tuned in, or -1 when what is playing is not on the scale. Set with [show]. */
    var selected = -1
        private set

    /**
     * Puts [names] on the scale with the needle on [at] (-1 for none). A new list jumps
     * straight to the station playing; on the same list the scale glides there.
     */
    fun show(names: List<String>, at: Int) {
        if (names != stations) {
            stations = names
            selected = at
            if (!dragging) {
                animator?.cancel()
                pos = if (at >= 0) at.toFloat() else pos.coerceIn(0f, maxOf(0, names.size - 1).toFloat())
            }
        } else if (at != selected) {
            selected = at
            if (at >= 0 && !dragging) glide(at.toFloat())
        }
        invalidate()
    }

    /** A short line in the corner ("Live", "Connecting…") and its colour. */
    var status = ""
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }
    var statusColor = 0
        set(v) {
            field = v
            invalidate()
        }
    var accent: Int = context.getColor(R.color.accent)
        set(v) {
            field = v
            invalidate()
        }
    /** Shown in the middle when there are no stations to put on the scale. */
    var emptyText = ""

    /** Called with a station's index when the scale comes to rest on it. */
    var onPick: ((Int) -> Unit)? = null

    // Where the needle is, as a station index; between two whole numbers while it moves.
    private var pos = 0f
    private var dragging = false
    private var moved = false
    private var downX = 0f
    private var downPos = 0f
    private var animator: ValueAnimator? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val clip = Path()
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val needle = context.getColor(R.color.bad)

    private fun spacing(): Float = width / 3.6f
    private fun xOf(index: Float): Float = width / 2f + (index - pos) * spacing()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        box.set(0f, 0f, w.toFloat(), h.toFloat())
        clip.reset()
        clip.addRoundRect(box, h * 0.07f, h * 0.07f, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        canvas.save()
        canvas.clipPath(clip)

        // The glass: warm and dark, lit from below like a bulb behind the scale.
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, 0f, 0f, h, GLASS_TOP, GLASS_BOTTOM, Shader.TileMode.CLAMP)
        canvas.drawRect(box, paint)
        paint.shader = RadialGradient(w / 2f, h * 1.25f, h * 1.1f, (accent and 0x00FFFFFF) or 0x44000000, 0, Shader.TileMode.CLAMP)
        canvas.drawRect(box, paint)
        paint.shader = null

        val base = h * BASE
        val n = stations.size
        val step = spacing() / TICKS

        text.textAlign = Paint.Align.LEFT
        text.typeface = regular
        text.textSize = h * 0.058f
        text.color = SCALE
        text.letterSpacing = 0.15f
        canvas.drawText("MASAR · INTERNET RADIO", h * 0.075f, h * 0.125f, text)
        text.letterSpacing = 0f
        if (status.isNotEmpty()) {
            text.textAlign = Paint.Align.RIGHT
            text.typeface = medium
            text.textSize = h * 0.066f
            text.color = statusColor
            canvas.drawText(status, w - h * 0.075f, h * 0.125f, text)
        }

        // The scale runs the full width whatever the number of stations; past either end of
        // the list its ticks are dimmed.
        paint.color = SCALE
        paint.strokeWidth = h * 0.008f
        canvas.drawLine(0f, base, w, base, paint)
        val from = floor((pos - w / 2f / spacing()) * TICKS).toInt()
        val to = ceil((pos + w / 2f / spacing()) * TICKS).toInt()
        val firstTick = -TICKS / 2
        val lastTick = maxOf(0, n - 1) * TICKS + TICKS / 2
        for (t in from..to) {
            val x = w / 2f + t * step - pos * spacing()
            val big = t % TICKS == 0
            val mid = t % 5 == 0
            val inside = n > 0 && t in firstTick..lastTick
            paint.strokeWidth = if (big) h * 0.012f else h * 0.005f
            paint.alpha = if (!inside) 60 else if (big) 255 else 150
            canvas.drawLine(x, base, x, base + h * (if (big && inside) 0.1f else if (mid) 0.06f else 0.035f), paint)
        }
        paint.alpha = 255

        if (n == 0) {
            text.textAlign = Paint.Align.CENTER
            text.typeface = regular
            text.textSize = h * 0.075f
            text.color = CREAM
            canvas.drawText(emptyText, w / 2f, h * 0.36f, text)
            drawNeedle(canvas, w, h, base)
            canvas.restore()
            return
        }

        // The stations: name on one of two rows (so neighbours don't run into each other),
        // a thin line down to a marker on the scale, and the number underneath.
        val nearest = pos.roundToInt()
        text.textAlign = Paint.Align.CENTER
        for (i in 0 until n) {
            val x = xOf(i.toFloat())
            if (x < -spacing() || x > w + spacing()) continue
            val on = i == nearest
            val y = h * (if (i % 2 == 0) ROW_LOW else ROW_HIGH)
            paint.color = SCALE
            paint.alpha = 110
            paint.strokeWidth = h * 0.004f
            canvas.drawLine(x, y + h * 0.03f, x, base, paint)
            paint.alpha = 255
            paint.color = if (on) accent else SCALE
            canvas.drawCircle(x, base, h * (if (on) 0.024f else 0.017f), paint)
            text.typeface = if (on) medium else regular
            text.textSize = h * (if (on) 0.088f else 0.07f)
            text.color = if (on) accent else CREAM
            val label = TextUtils.ellipsize(stations[i], text, spacing() * 1.7f, TextUtils.TruncateAt.END).toString()
            canvas.drawText(label, x, y, text)
            text.typeface = if (on) medium else regular
            text.textSize = h * 0.062f
            text.color = if (on) accent else SCALE
            canvas.drawText((i + 1).toString(), x, h * NUMBERS, text)
        }

        drawNeedle(canvas, w, h, base)
        canvas.restore()
    }

    /** The needle stays put in the middle; the scale moves under it. It starts below the names and stops above the numbers. */
    private fun drawNeedle(canvas: Canvas, w: Float, h: Float, base: Float) {
        paint.color = needle
        paint.strokeWidth = h * 0.014f
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(w / 2f, h * NEEDLE_TOP, w / 2f, base + h * 0.13f, paint)
        canvas.drawCircle(w / 2f, h * NEEDLE_TOP, h * 0.024f, paint)
        paint.strokeCap = Paint.Cap.BUTT
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val n = stations.size
        if (n == 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel()
                dragging = true
                moved = false
                downX = event.x
                downPos = pos
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                if (abs(dx) > height * 0.04f) moved = true
                if (moved) {
                    pos = (downPos - dx / spacing()).coerceIn(-0.35f, n - 1 + 0.35f)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                dragging = false
                // A tap goes to the station under the finger; a drag settles on the nearest.
                val target = (if (moved) pos else pos + (event.x - width / 2f) / spacing()).roundToInt().coerceIn(0, n - 1)
                glide(target.toFloat())
                if (target != selected) onPick?.invoke(target)
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                glide((if (selected >= 0) selected else pos.roundToInt().coerceIn(0, n - 1)).toFloat())
            }
        }
        return true
    }

    private fun glide(to: Float) {
        animator?.cancel()
        if (width == 0 || abs(to - pos) < 0.001f) {
            pos = to
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(pos, to).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                pos = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private companion object {
        const val TICKS = 20
        // Heights, as a share of the view's height.
        const val ROW_LOW = 0.40f
        const val ROW_HIGH = 0.28f
        const val NEEDLE_TOP = 0.47f
        const val BASE = 0.66f
        const val NUMBERS = 0.92f
        val GLASS_TOP = 0xFF2A2216.toInt()
        val GLASS_BOTTOM = 0xFF15110B.toInt()
        val SCALE = 0xFFC9A55A.toInt()
        val CREAM = 0xFFE6D3A3.toInt()
    }
}
