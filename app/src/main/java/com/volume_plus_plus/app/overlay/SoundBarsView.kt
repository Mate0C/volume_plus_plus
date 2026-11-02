package com.volume_plus_plus.app.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.roundToInt

/**
 * The sound-bars glyph that ends the Android 17 collapsed panel: five round-ended bars of different
 * heights, the fourth the tallest. It is the panel's way into the full sheet, and it moves while
 * something is playing, the bars rising and falling out of step with each other, then settles back
 * to its resting shape when the sound stops.
 *
 * [isPlaying] is asked each time the animation comes round, so the bars stop of their own accord a
 * moment after the audio does, and it is asked once when the view reaches the screen.
 */
@SuppressLint("ViewConstructor")
class SoundBarsView(
    context: Context,
    private val color: Int,
    private val isPlaying: () -> Boolean,
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = color }
    private val rect = RectF()
    private var phase = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = CYCLE_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedFraction
            invalidate()
        }
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationRepeat(animation: android.animation.Animator) {
                if (!isPlaying()) stopBars()
            }
        })
    }

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value * OVERLAY_SCALE, resources.displayMetrics,
    )

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isPlaying()) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    private fun stopBars() {
        animator.cancel()
        phase = 0f
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = dp(SIZE_DP).roundToInt()
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val barW = dp(BAR_DP)
        val gap = dp(GAP_DP)
        val total = BASE_HEIGHTS.size * barW + (BASE_HEIGHTS.size - 1) * gap
        val maxH = dp(MAX_HEIGHT_DP)
        val cy = height / 2f
        var x = (width - total) / 2f
        val moving = animator.isRunning
        BASE_HEIGHTS.forEachIndexed { i, base ->
            // Each bar swings about its resting height, offset along the cycle so they never move as one.
            val swing = if (moving) 0.45f * sin(2.0 * PI * (phase + i * 0.17)).toFloat() else 0f
            val h = (maxH * (base + swing * base.coerceAtLeast(0.35f))).coerceIn(barW, maxH)
            rect.set(x, cy - h / 2f, x + barW, cy + h / 2f)
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint)
            x += barW + gap
        }
    }

    private companion object {
        /** The resting shape, as fractions of the tallest bar, sampled off the stock glyph. */
        val BASE_HEIGHTS = floatArrayOf(0.45f, 0.85f, 0.55f, 1f, 0.72f)
        const val SIZE_DP = 24f
        const val BAR_DP = 2.4f
        const val GAP_DP = 2.2f
        const val MAX_HEIGHT_DP = 14f
        const val CYCLE_MS = 900L
    }
}
