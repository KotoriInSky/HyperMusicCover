package com.os4.musiccover.island

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton

/** Draw-only press feedback: does not overwrite the island morph's scale, alpha or listeners. */
internal class PressFeedbackButton(context: Context) : ImageButton(context) {
    private var press = 0f
    private var animator: ValueAnimator? = null

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        animator?.cancel()
        val target = if (pressed && isEnabled) 1f else 0f
        if (!isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) {
            press = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(press, target).apply {
            duration = if (pressed) 80L else 180L
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                press = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val save = canvas.save()
        val scale = 1f - 0.1f * press
        canvas.scale(scale, scale, width / 2f, height / 2f)
        super.onDraw(canvas)
        canvas.restoreToCount(save)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        press = 0f
        super.onDetachedFromWindow()
    }
}
