package io.github.timotheegrollier.flx4pocket

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Real-time spectrum display for one deck.
 *
 * The last [BAR_COUNT] bands of the deck's audio are drawn as vertical bars
 * scrolling horizontally across the view. [aboveOverlay] lets one of the two
 * stacked deck views draw its bars like the one overlapping it, so the two
 * spectra read as a single stack pushed across the screen.
 */
class BeatBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val BAR_COUNT = 32
        private const val DEFAULT_ACCENT = 0xFF36F9D9.toInt()
        private const val INACTIVE = 0xFF1F2937.toInt()
    }

    private val bars = FloatArray(BAR_COUNT)
    private var beatPulse = 0f

    /** When true the view draws its bars with the style of the stack below it. */
    var aboveOverlay: Boolean = false
        set(value) { field = value; invalidate() }

    var accent: Int = DEFAULT_ACCENT
        set(value) {
            field = value
            barPaint.color = value
            barPaint.alpha = 255
            pulsePaint.color = value
            overlayBg.color = value
            overlayBg.alpha = 20
            invalidate()
        }

    private var barPaint: Paint
    private var pulsePaint: Paint
    private var bgPaint: Paint
    private var overlayBg: Paint

    init {
        barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            color = accent
            alpha = 255
        }
        pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            color = accent
            alpha = 200
        }
        bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            color = INACTIVE
            alpha = 255
        }
        overlayBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            color = accent
            alpha = 20
        }
    }

    /**
     * Publishes the latest [BAR_COUNT] energy bands. Bars decay slowly so the
     * display keeps showing a bit of the recent history while new energy
     * scrolls across.
     */
    fun publishSpectrum(spectrum: FloatArray?) {
        if (spectrum == null || spectrum.size != BAR_COUNT) return
        for (i in bars.indices) {
            bars[i] = (spectrum[i].coerceAtLeast(0f) + bars[i]) * 0.5f
        }
        invalidate()
    }

    fun setState(phase: Double, beatIndex: Long, playing: Boolean, accentColor: Int) {
        if (accent != accentColor) accent = accentColor
        beatPulse = if (playing) 1f else 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        if (aboveOverlay) {
            canvas.drawRect(0f, 0f, w, h, overlayBg)
        }
        canvas.drawRoundRect(0f, 0f, w, h, 3f, 3f, bgPaint)

        val gap = w / (BAR_COUNT + 1f)
        val maxBar = (h - 2f) / 2f
        for (i in 0 until BAR_COUNT) {
            val value = bars[i]
            val barW = gap * 0.6f
            val x = gap * (i + 1f) - barW / 2f
            val barH = (value.coerceAtLeast(0.02f) * maxBar).coerceAtLeast(1f)
            canvas.drawRect(x, h - barH, x + barW, h - 1f, barPaint)
        }

        if (beatPulse > 0.05f) {
            pulsePaint.alpha = (120f + beatPulse * 135f).toInt()
            canvas.drawRect(0f, 0f, w, 2f, pulsePaint)
        }
    }
}
