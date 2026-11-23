package io.github.timotheegrollier.flx4pocket

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Shows four beats of the current grid and a playhead derived from the actual
 * sample position, so the UI corroborates what the audio engine is doing.
 */
class BeatBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(31, 41, 55)
        style = Paint.Style.FILL
    }
    private val beatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(55, 65, 81)
        strokeWidth = 2f
    }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        style = Paint.Style.FILL
    }

    private var accent = Color.CYAN
    private var phase = 0.0
    private var beatIndex = 0L
    private var playing = false

    fun setState(phase: Double, beatIndex: Long, playing: Boolean, accent: Int) {
        this.phase = phase.coerceIn(0.0, 0.999)
        this.beatIndex = beatIndex
        this.playing = playing
        this.accent = accent
        activePaint.color = accent
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawRoundRect(0f, 0f, w, h, 3f, 3f, backgroundPaint)

        val inBar = ((beatIndex % 4) + 4) % 4
        for (i in 0 until 4) {
            val x = w * i / 4f
            if (i == inBar.toInt() && playing) {
                activePaint.alpha = 70
                canvas.drawRect(x + 1f, 1f, x + w / 4f - 1f, h - 1f, activePaint)
                activePaint.alpha = 255
            }
            if (i > 0) canvas.drawLine(x, 1f, x, h - 1f, beatPaint)
        }

        val playhead = ((inBar + phase) / 4.0 * w).toFloat().coerceIn(1f, w - 1f)
        activePaint.alpha = if (playing) 255 else 150
        canvas.drawRect(playhead - 1.5f, 0f, playhead + 1.5f, h, activePaint)
        activePaint.alpha = 255
    }
}
