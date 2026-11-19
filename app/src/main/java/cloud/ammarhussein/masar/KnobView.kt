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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The analog radio's tuning knob. Turn it with a finger: every click of the
 * knob (thirty degrees) moves one station along, clockwise for the next.
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
    private val rim = context.getColor(R.color.tile_stroke)
    private val idle = context.getColor(R.color.idle)

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f * 0.94f
        if (r <= 0f) return
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouch = touchAngle(event)
                pending = 0f
                parent?.requestDisallowInterceptTouchEvent(true)
            }
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
    }
}
