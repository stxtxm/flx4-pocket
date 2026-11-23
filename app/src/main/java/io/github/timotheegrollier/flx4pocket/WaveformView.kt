package io.github.timotheegrollier.flx4pocket

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Colours shared by the two waveform views and the hot-cue pads. */
object WaveColors {
    const val LOW = 0xFF2F6BFF.toInt()
    const val MID = 0xFFFF9A2E.toInt()
    const val HIGH = 0xFFF4F7FB.toInt()
    const val CUE = 0xFFFF8A00.toInt()
    val HOT_CUES = intArrayOf(
        0xFF34D399.toInt(), 0xFF22D3EE.toInt(), 0xFF60A5FA.toInt(), 0xFFA78BFA.toInt(),
        0xFFF472B6.toInt(), 0xFFF87171.toInt(), 0xFFFBBF24.toInt(), 0xFFA3E635.toInt()
    )
}

/**
 * Scrolling three-band waveform of one deck with a fixed centre playhead,
 * beat grid, loop region, cue and hot-cue markers.
 *
 * The horizontal scale is in *heard* time, so two decks at the same BPM show
 * the same beat spacing whatever their pitch faders say. Dragging scratches
 * the deck; pinching zooms (reported through [onZoom] so both decks follow).
 */
class WaveformView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    var deck: Deck? = null
    var accent = 0xFFFFFFFF.toInt()

    /** Seconds of audio visible across the view. */
    var visibleSeconds = 8.0
    var onZoom: ((Double) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val barWidth = max(2f, 1.2f * density)
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = barWidth + 0.5f }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9f * density
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val triangle = Path()
    private var low = FloatArray(0)
    private var mid = FloatArray(0)
    private var high = FloatArray(0)

    // The mixer reports its position in bursts (one audio buffer at a time);
    // the view runs its own clock and only steers it towards the real one.
    private var shown = 0.0
    private var lastFrameNanos = 0L

    private var lastX = 0f
    private val pinch = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                onZoom?.invoke((visibleSeconds / detector.scaleFactor).coerceIn(2.0, 32.0))
                return true
            }
        }
    )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val bars = (w / barWidth).toInt() + 3
        low = FloatArray(bars * 4)
        mid = FloatArray(bars * 4)
        high = FloatArray(bars * 4)
    }

    override fun onDraw(canvas: Canvas) {
        postInvalidateOnAnimation() // free-running: redraw on every display frame
        val d = deck ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || !d.hasTrack()) return

        val now = System.nanoTime()
        val dt = ((now - lastFrameNanos) / 1e9).coerceIn(0.0, 0.1)
        lastFrameNanos = now
        val actual = d.positionSec
        val speed = d.speed
        shown += dt * speed
        val error = actual - shown
        if (speed == 0.0 || abs(error) > 0.25) shown = actual else shown += error * 0.15

        val centre = w / 2f
        val middle = h / 2f
        // Track seconds per pixel: heard seconds per pixel times playback rate.
        val secondsPerPixel = visibleSeconds / w * (d.tempoRate * d.syncFactor)
        val left = shown - centre * secondsPerPixel
        val right = shown + centre * secondsPerPixel

        drawLoop(canvas, d, left, secondsPerPixel, h)
        drawBands(canvas, d, left, secondsPerPixel, w, middle)
        drawGrid(canvas, d, left, right, secondsPerPixel, h)
        drawMarkers(canvas, d, left, right, secondsPerPixel, h)

        paint.style = Paint.Style.FILL
        paint.color = 0x66000000
        canvas.drawRect(centre - 2f * density, 0f, centre + 2f * density, h, paint)
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawRect(centre - 0.75f * density, 0f, centre + 0.75f * density, h, paint)
    }

    private fun drawBands(
        canvas: Canvas, d: Deck, left: Double, secondsPerPixel: Double, w: Float, middle: Float
    ) {
        val wave = d.waveform ?: return
        val size = wave.size
        // Bars are anchored to the track, not the screen: each one keeps its
        // height as it scrolls, so the waveform does not shimmer.
        val barSeconds = barWidth * secondsPerPixel
        val pointsPerBar = barSeconds * wave.pointsPerSecond
        val firstBar = floor(left / barSeconds).toLong()
        val bars = min(low.size / 4, (w / barWidth).toInt() + 2)
        val scale = (middle - 2f * density) / 255f
        var count = 0
        for (b in 0 until bars) {
            val bar = firstBar + b
            if (bar < 0) continue
            val from = (bar * pointsPerBar).toInt()
            if (from >= size) break
            val to = min(size, max(from + 1, ((bar + 1) * pointsPerBar).toInt()))
            var l = 0
            var m = 0
            var hi = 0
            for (i in from until to) {
                l = max(l, wave.low[i].toInt() and 0xFF)
                m = max(m, wave.mid[i].toInt() and 0xFF)
                hi = max(hi, wave.high[i].toInt() and 0xFF)
            }
            val x = ((bar * barSeconds - left) / secondsPerPixel).toFloat() + barWidth / 2f
            val o = count * 4
            // Stacked bands: bass is the body, mids and highs sit inside it.
            val total = min(255f, l + m * 0.5f + hi * 0.35f) * scale
            val midHeight = min(total, (m * 0.75f + hi * 0.35f) * scale)
            val highHeight = min(midHeight, hi * 0.6f * scale)
            low[o] = x; low[o + 1] = middle - total; low[o + 2] = x; low[o + 3] = middle + total
            mid[o] = x; mid[o + 1] = middle - midHeight; mid[o + 2] = x; mid[o + 3] = middle + midHeight
            high[o] = x; high[o + 1] = middle - highHeight; high[o + 2] = x; high[o + 3] = middle + highHeight
            count++
        }
        bandPaint.color = WaveColors.LOW
        canvas.drawLines(low, 0, count * 4, bandPaint)
        bandPaint.color = WaveColors.MID
        canvas.drawLines(mid, 0, count * 4, bandPaint)
        bandPaint.color = WaveColors.HIGH
        canvas.drawLines(high, 0, count * 4, bandPaint)
    }

    private fun drawGrid(
        canvas: Canvas, d: Deck, left: Double, right: Double, secondsPerPixel: Double, h: Float
    ) {
        val grid = d.grid
        if (!grid.valid) return
        val beat = grid.secondsPerBeat()
        val dense = beat / secondsPerPixel < 6f * density // zoomed far out: bars only
        var index = ceil((max(left, 0.0) - grid.anchorSec) / beat).toLong()
        paint.style = Paint.Style.FILL
        while (true) {
            val time = grid.anchorSec + index * beat
            if (time > right || time > d.durationSec) break
            val downbeat = Math.floorMod(index, 4L) == 0L
            if (downbeat || !dense) {
                val x = ((time - left) / secondsPerPixel).toFloat()
                paint.color = if (downbeat) 0xB0FFFFFF.toInt() else 0x48FFFFFF
                canvas.drawRect(x - density * 0.5f, 0f, x + density * 0.5f, h, paint)
                if (downbeat) {
                    paint.color = 0xFFFF4D4D.toInt()
                    canvas.drawRect(x - density, 0f, x + density, 4f * density, paint)
                    canvas.drawRect(x - density, h - 4f * density, x + density, h, paint)
                }
            }
            index++
        }
    }

    private fun drawLoop(canvas: Canvas, d: Deck, left: Double, secondsPerPixel: Double, h: Float) {
        val start = d.loopInSec
        val end = d.loopOutSec
        if (start < 0.0) return
        val x0 = ((start - left) / secondsPerPixel).toFloat()
        paint.style = Paint.Style.FILL
        if (end > start) {
            val x1 = ((end - left) / secondsPerPixel).toFloat()
            paint.color = accent
            paint.alpha = if (d.loopActive) 70 else 28
            canvas.drawRect(x0, 0f, x1, h, paint)
            paint.alpha = 255
            canvas.drawRect(x1 - density, 0f, x1, h, paint)
        }
        paint.color = accent
        canvas.drawRect(x0, 0f, x0 + density, h, paint)
    }

    private fun drawMarkers(
        canvas: Canvas, d: Deck, left: Double, right: Double, secondsPerPixel: Double, h: Float
    ) {
        paint.style = Paint.Style.FILL
        val cue = d.cuePointSec
        if (cue in left..right) {
            val x = ((cue - left) / secondsPerPixel).toFloat()
            val s = 6f * density
            triangle.rewind()
            triangle.moveTo(x - s, h)
            triangle.lineTo(x + s, h)
            triangle.lineTo(x, h - s * 1.3f)
            triangle.close()
            paint.color = WaveColors.CUE
            canvas.drawPath(triangle, paint)
        }
        val cues = d.hotCues
        val flag = 13f * density
        for (i in cues.indices) {
            val time = cues[i] ?: continue
            if (time < left || time > right) continue
            val x = ((time - left) / secondsPerPixel).toFloat()
            paint.color = WaveColors.HOT_CUES[i]
            canvas.drawRect(x - density * 0.75f, 0f, x + density * 0.75f, h, paint)
            canvas.drawRect(x, h - flag, x + flag, h, paint)
            textPaint.color = 0xFF0B0E13.toInt()
            canvas.drawText(HOT_CUE_LETTERS, i, i + 1, x + flag / 2f, h - flag * 0.22f, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val d = deck ?: return false
        pinch.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = event.x
                d.scratchBegin()
            }
            MotionEvent.ACTION_POINTER_DOWN -> d.scratchEnd() // second finger: zooming
            MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1 && width > 0) {
                val secondsPerPixel = visibleSeconds / width * (d.tempoRate * d.syncFactor)
                d.scratchMove((lastX - event.x) * secondsPerPixel)
                lastX = event.x
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> d.scratchEnd()
        }
        return true
    }

    companion object {
        const val HOT_CUE_LETTERS = "ABCDEFGH"
    }
}

/**
 * Whole-track overview: played part dimmed, loop, hot cues and playhead.
 * Touching it jumps to that point of the track (needle search).
 */
class OverviewView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    var deck: Deck? = null
    var accent = 0xFFFFFFFF.toInt()

    private val density = resources.displayMetrics.density
    private val paint = Paint()
    private var lines = FloatArray(0)
    private var cachedWave: Waveform? = null
    private var cachedSize = -1
    private var frame = 0

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        lines = FloatArray(max(0, w) * 4)
        cachedSize = -1
    }

    override fun onDraw(canvas: Canvas) {
        postInvalidateOnAnimation()
        val w = width
        val h = height.toFloat()
        paint.color = 0xFF121820.toInt()
        canvas.drawRect(0f, 0f, w.toFloat(), h, paint)
        val d = deck ?: return
        val duration = d.durationSec
        if (duration <= 0.0 || w <= 0) return

        val wave = d.waveform
        // Rebuilding the columns scans the whole track: do it a few times a
        // second while the analysis is still filling the waveform, then never.
        if (wave !== cachedWave || (wave != null && wave.size != cachedSize && (frame++ % 20 == 0 || wave.complete))) {
            cachedWave = wave
            cachedSize = wave?.size ?: -1
            rebuild(wave, duration, w, h)
        }
        val playhead = (d.positionSec / duration * w).toFloat()
        paint.strokeWidth = 1f
        paint.color = accent
        paint.alpha = 90
        val split = min(w, max(0, playhead.toInt())) * 4
        canvas.drawLines(lines, 0, split, paint)
        paint.alpha = 255
        canvas.drawLines(lines, split, lines.size - split, paint)

        if (d.loopInSec >= 0.0 && d.loopOutSec > d.loopInSec) {
            paint.color = accent
            paint.alpha = if (d.loopActive) 110 else 50
            canvas.drawRect(
                (d.loopInSec / duration * w).toFloat(), 0f,
                (d.loopOutSec / duration * w).toFloat(), h, paint
            )
            paint.alpha = 255
        }
        val cues = d.hotCues
        for (i in cues.indices) {
            val x = ((cues[i] ?: continue) / duration * w).toFloat()
            paint.color = WaveColors.HOT_CUES[i]
            canvas.drawRect(x - density, 0f, x + density, h * 0.4f, paint)
        }
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawRect(playhead - density, 0f, playhead + density, h, paint)
    }

    private fun rebuild(wave: Waveform?, duration: Double, w: Int, h: Float) {
        val middle = h / 2f
        val size = wave?.size ?: 0
        val pointsPerColumn = if (wave == null) 0.0 else duration * wave.pointsPerSecond / w
        for (x in 0 until w) {
            var peak = 0
            if (wave != null) {
                val from = (x * pointsPerColumn).toInt()
                val to = min(size, max(from + 1, ((x + 1) * pointsPerColumn).toInt()))
                for (i in from until to) {
                    peak = max(peak, max(wave.low[i].toInt() and 0xFF, wave.mid[i].toInt() and 0xFF))
                }
            }
            val half = max(0.5f, peak / 255f * (middle - 1f))
            val o = x * 4
            lines[o] = x + 0.5f
            lines[o + 1] = middle - half
            lines[o + 2] = x + 0.5f
            lines[o + 3] = middle + half
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val d = deck ?: return false
        if (width <= 0 || d.durationSec <= 0.0) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
            d.seekTo((event.x / width).coerceIn(0f, 1f) * d.durationSec)
        }
        return true
    }
}
