package io.github.timotheegrollier.flx4pocket

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class VuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(31, 41, 55)
    }
    private val leftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(34, 211, 238)
    }
    private val rightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(244, 114, 182)
    }

    private var left = 0f
    private var right = 0f

    fun setLevels(left: Float, right: Float) {
        this.left = left.coerceIn(0f, 1f)
        this.right = right.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val barHeight = (h - 6f) / 2f
        if (w <= 0f || barHeight <= 0f) return
        canvas.drawRoundRect(0f, 0f, w, barHeight, 2f, 2f, trackPaint)
        canvas.drawRoundRect(0f, barHeight + 6f, w, h, 2f, 2f, trackPaint)
        canvas.drawRoundRect(0f, 0f, w * left, barHeight, 2f, 2f, leftPaint)
        canvas.drawRoundRect(0f, barHeight + 6f, w * right, h, 2f, 2f, rightPaint)
    }
}
