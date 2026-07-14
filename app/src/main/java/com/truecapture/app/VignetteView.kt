package com.truecapture.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.hypot

// Shows a soft glow in the corners of the screen. It is used as a flash for the
// selfie camera, since the front camera has no real flash -> the screen itself
// lights up the shot. The colour comes from the settings menu.
class VignetteView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var glowColor = Color.WHITE

    fun setVignetteColor(color: Int) {
        glowColor = color
        buildShader()
        invalidate()
    }

    private fun buildShader() {
        if (width == 0 || height == 0) return
        val cx = width / 2f
        val cy = height / 2f
        val radius = hypot(cx, cy)
        // Clear in the middle, strongest towards the corners.
        val edge = Color.argb(
            0xDD,
            Color.red(glowColor),
            Color.green(glowColor),
            Color.blue(glowColor)
        )
        paint.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, edge),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildShader()
    }

    override fun onDraw(canvas: Canvas) {
        if (paint.shader != null) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }
}
