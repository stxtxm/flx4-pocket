package io.github.timotheegrollier.flx4pocket

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Sets the text only when it changed, so per-frame refreshes cost nothing. */
fun TextView.show(value: CharSequence) {
    if (text.toString() != value.toString()) text = value
}

/** Tints a button background (and picks a readable text colour) only on change. */
fun TextView.tint(background: Int, foreground: Int) {
    if (tag != background) {
        tag = background
        backgroundTintList = ColorStateList.valueOf(background)
    }
    if (currentTextColor != foreground) setTextColor(foreground)
}

/**
 * Runs [action] when the finger lands, not when it lifts, so PLAY or a loop
 * button acts on the beat it was pressed on. Accessibility clicks still work.
 */
@SuppressLint("ClickableViewAccessibility")
fun View.onPress(action: () -> Unit) {
    setOnClickListener { action() }
    setOnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.isPressed = true
                action()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.isPressed = false
        }
        true
    }
}

/**
 * Base of the touch knobs and faders: a 0..1 value changed by a relative drag
 * (a finger landing on the control never makes it jump) and reset to
 * [defaultValue] by a double tap.
 */
abstract class ControlView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var value = 0.5f
        private set
    var defaultValue = 0.5f
    var onChange: ((Float) -> Unit)? = null
    var accent = 0xFFFFFFFF.toInt()
        set(color) {
            field = color
            invalidate()
        }

    protected val density = resources.displayMetrics.density
    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var dragging = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastDown = 0L

    /** Mirrors engine state into the control; ignored while a finger is on it. */
    fun show(newValue: Float) {
        if (dragging || abs(newValue - value) < 0.0005f) return
        value = newValue.coerceIn(0f, 1f)
        invalidate()
    }

    /** Value change for a finger movement of ([dx], [dy]) pixels. */
    protected abstract fun delta(dx: Float, dy: Float): Float

    private fun commit(newValue: Float) {
        val clamped = newValue.coerceIn(0f, 1f)
        if (clamped == value) return
        value = clamped
        invalidate()
        onChange?.invoke(clamped)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                lastX = event.x
                lastY = event.y
                val now = SystemClock.uptimeMillis()
                if (now - lastDown < 300) commit(defaultValue)
                lastDown = now
            }
            MotionEvent.ACTION_MOVE -> {
                commit(value + delta(event.x - lastX, event.y - lastY))
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    protected companion object {
        const val TRACK = 0xFF2A3445.toInt()
        const val BODY = 0xFF1A2130.toInt()
        const val LABEL = 0xFF9AA6B8.toInt()
    }
}

/**
 * Rotary knob. The label drawn under it is the view's contentDescription.
 * [centered] knobs (EQ, filter, trim) draw their arc from 12 o'clock.
 */
class KnobView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    ControlView(context, attrs) {

    var centered = true

    override fun delta(dx: Float, dy: Float): Float = (dx - dy) / (150f * density)

    override fun onDraw(canvas: Canvas) {
        val labelHeight = 13f * density
        val radius = min(width / 2f, (height - labelHeight) / 2f) - 3f * density
        if (radius <= 0f) return
        val cx = width / 2f
        val cy = (height - labelHeight) / 2f
        val stroke = 3f * density

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = TRACK
        canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, 135f, 270f, false, paint)
        paint.color = accent
        if (centered) {
            canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, 270f, (value - 0.5f) * 270f, false, paint)
        } else {
            canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, 135f, value * 270f, false, paint)
        }

        paint.style = Paint.Style.FILL
        paint.color = BODY
        canvas.drawCircle(cx, cy, radius - stroke * 1.4f, paint)

        val angle = Math.toRadians(135.0 + value * 270.0)
        paint.style = Paint.Style.STROKE
        paint.color = 0xFFF2F5F7.toInt()
        paint.strokeWidth = 2f * density
        canvas.drawLine(
            cx + (cos(angle) * radius * 0.25).toFloat(), cy + (sin(angle) * radius * 0.25).toFloat(),
            cx + (cos(angle) * radius * 0.62).toFloat(), cy + (sin(angle) * radius * 0.62).toFloat(),
            paint
        )

        paint.style = Paint.Style.FILL
        paint.color = LABEL
        paint.textSize = 10f * density
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(contentDescription?.toString().orEmpty(), cx, height - 2f * density, paint)
    }
}

/**
 * Linear fader, vertical when taller than wide (1 at the top) and horizontal
 * otherwise (1 at the right). [centered] draws a centre detent mark.
 */
class FaderView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    ControlView(context, attrs) {

    var centered = false
    private val cap get() = 26f * density
    private val vertical get() = height > width

    override fun delta(dx: Float, dy: Float): Float {
        val travel = (if (vertical) height else width) - cap
        if (travel <= 0f) return 0f
        return if (vertical) -dy / travel else dx / travel
    }

    override fun onDraw(canvas: Canvas) {
        val groove = 4f * density
        val length = if (vertical) height.toFloat() else width.toFloat()
        val breadth = if (vertical) width.toFloat() else height.toFloat()
        val travel = length - cap
        if (travel <= 0f) return
        val mid = breadth / 2f
        val position = if (vertical) (1f - value) * travel else value * travel
        val capBreadth = min(breadth - 4f * density, 34f * density)

        paint.style = Paint.Style.FILL
        paint.color = TRACK
        rect(canvas, cap / 2f, mid - groove / 2f, length - cap / 2f, mid + groove / 2f, groove / 2f)
        if (centered) {
            rect(canvas, length / 2f - density, mid - capBreadth / 2f, length / 2f + density, mid + capBreadth / 2f, 0f)
        }

        paint.color = 0xFF3A465A.toInt()
        rect(canvas, position, mid - capBreadth / 2f, position + cap, mid + capBreadth / 2f, 4f * density)
        paint.color = accent
        rect(canvas, position + cap / 2f - density, mid - capBreadth / 2f, position + cap / 2f + density, mid + capBreadth / 2f, 0f)
    }

    /** Draws in (along, across) coordinates so one code path serves both orientations. */
    private fun rect(canvas: Canvas, a0: Float, b0: Float, a1: Float, b1: Float, radius: Float) {
        if (vertical) canvas.drawRoundRect(b0, a0, b1, a1, radius, radius, paint)
        else canvas.drawRoundRect(a0, b0, a1, b1, radius, radius, paint)
    }
}

/** Segmented peak meter along the view's longer side. */
class VuView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    private val paint = Paint()
    private var lit = 0

    fun setLevel(peak: Float) {
        // Square root spreads the useful range: -12 dB lands mid-scale.
        val segments = (sqrt(peak.coerceIn(0f, 1f)) * SEGMENTS + 0.5f).toInt()
        if (segments != lit) {
            lit = segments
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val vertical = height > width
        val length = (if (vertical) height else width).toFloat()
        val size = length / SEGMENTS
        val gap = resources.displayMetrics.density
        for (i in 0 until SEGMENTS) {
            paint.color = when {
                i >= lit -> 0xFF1E2736.toInt()
                i >= SEGMENTS - 1 -> 0xFFEF4444.toInt()
                i >= SEGMENTS - 3 -> 0xFFFBBF24.toInt()
                else -> 0xFF34D399.toInt()
            }
            if (vertical) {
                val bottom = length - i * size
                canvas.drawRect(0f, bottom - size + gap, width.toFloat(), bottom, paint)
            } else {
                canvas.drawRect(i * size, 0f, (i + 1) * size - gap, height.toFloat(), paint)
            }
        }
    }

    private companion object {
        const val SEGMENTS = 12
    }
}
