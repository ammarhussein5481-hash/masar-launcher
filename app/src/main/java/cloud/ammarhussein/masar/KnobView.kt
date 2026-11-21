package cloud.ammarhussein.masar

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The analog radio's tuning knob. Turn it with a finger: every click of the
 * knob (thirty degrees) moves one station along, clockwise for the next.
 * A ring of marks around it, with − and + at the ends, shows which way is which.
 */
class KnobView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var accent: Int = context.getColor(R.color.accent)
        set(v) {
            field = v
            invalidate()
        }

    /** Called with +1 or -1 for each click the knob is turned. */
    var onStep: ((Int) -> Unit)? = null

    private var angle = 25f
    private var lastTouch = 0f
    private var pending = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rim = context.getColor(R.color.tile_stroke)
    private val idle = context.getColor(R.color.idle)

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = min(width, height) / 2f * 0.98f
        if (outer <= 0f) return
        // The marks: fixed, a short one every click, over the top from lower left to lower right.
        paint.style = Paint.Style.STROKE
        paint.color = idle
        paint.strokeCap = Paint.Cap.ROUND
        var mark = -135f
        while (mark <= 135.1f) {
            val a = Math.toRadians((mark - 90f).toDouble())
            val long = mark == -135f || mark == 135f || mark == 0f
            paint.strokeWidth = outer * (if (long) 0.05f else 0.03f)
            val inner = outer * (if (long) 0.8f else 0.85f)
            canvas.drawLine(
                cx + cos(a).toFloat() * inner, cy + sin(a).toFloat() * inner,
                cx + cos(a).toFloat() * outer * 0.95f, cy + sin(a).toFloat() * outer * 0.95f, paint
            )
            mark += STEP
        }
        paint.strokeCap = Paint.Cap.BUTT
        // − back, + on, at the two ends of the marks.
        paint.style = Paint.Style.FILL
        label.color = idle
        label.textSize = outer * 0.3f
        val endY = cy + outer * 0.92f
        canvas.drawText("−", cx - outer * 0.78f, endY, label)
        canvas.drawText("+", cx + outer * 0.78f, endY, label)

        val r = outer * 0.74f
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.35f, r * 1.5f, 0xFF4A4F58.toInt(), 0xFF171B20.toInt(), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.color = rim
        paint.strokeWidth = r * 0.03f
        canvas.drawCircle(cx, cy, r, paint)
        // A dotted ring, turning with the knob so the movement shows.
        paint.color = idle
        paint.strokeWidth = r * 0.025f
        paint.pathEffect = DashPathEffect(floatArrayOf(r * 0.09f, r * 0.09f), 0f)
        canvas.save()
        canvas.rotate(angle, cx, cy)
        canvas.drawCircle(cx, cy, r * 0.8f, paint)
        canvas.restore()
        paint.pathEffect = null
        // The pointer.
        val a = Math.toRadians((angle - 90f).toDouble())
        paint.color = accent
        paint.strokeWidth = r * 0.085f
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(
            cx + cos(a).toFloat() * r * 0.42f, cy + sin(a).toFloat() * r * 0.42f,
            cx + cos(a).toFloat() * r * 0.74f, cy + sin(a).toFloat() * r * 0.74f, paint
        )
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun touchAngle(event: MotionEvent): Float =
        Math.toDegrees(atan2((event.y - height / 2f).toDouble(), (event.x - width / 2f).toDouble())).toFloat()

    /**
     * Grows the knob under the finger, so it is easy to see turning, and back on lift. It grows
     * from near its lower right, so at the corner of the screen it spreads left and up, on screen.
     */
    private fun zoom(on: Boolean) {
        pivotX = width * 0.8f
        pivotY = height * 0.6f
        val to = if (on) ZOOM else 1f
        animate().scaleX(to).scaleY(to).translationZ(if (on) 24f else 0f).setDuration(140).start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // The zoomed knob reaches past its own box: let the layouts around it draw it whole, up to
        // the page (knob column, keys row, analog look, page), so the page padding does not cut it.
        var p = parent
        repeat(4) {
            val group = p as? ViewGroup ?: return
            group.clipChildren = false
            group.clipToPadding = false
            p = group.parent
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouch = touchAngle(event)
                pending = 0f
                parent?.requestDisallowInterceptTouchEvent(true)
                zoom(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> zoom(false)
            MotionEvent.ACTION_MOVE -> {
                val now = touchAngle(event)
                var delta = now - lastTouch
                // Crossing from 180 to -180 degrees is a small move, not a full turn back.
                if (delta > 180f) delta -= 360f
                if (delta < -180f) delta += 360f
                lastTouch = now
                angle += delta
                pending += delta
                while (pending >= STEP) {
                    pending -= STEP
                    onStep?.invoke(1)
                }
                while (pending <= -STEP) {
                    pending += STEP
                    onStep?.invoke(-1)
                }
                invalidate()
            }
        }
        return true
    }

    private companion object {
        const val STEP = 30f
        const val ZOOM = 1.7f
    }
}
