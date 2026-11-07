package com.example.autotapper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.min

/** 連打ボタンの見た目。中心の点がタップされる位置。待機中はオレンジ、連打中は緑 */
class TargetView(context: Context, var number: Int) : View(context) {

    private val density = resources.displayMetrics.density

    private val idleColor = Color.rgb(255, 61, 0)
    private val activeColor = Color.rgb(0, 200, 83)

    var active = false
        set(value) {
            field = value
            invalidate()
        }

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 12 * density
        isFakeBoldText = true
        setShadowLayer(2 * density, 0f, 0f, Color.BLACK)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val c = if (active) activeColor else idleColor
        ring.color = c
        dot.color = c
        fill.color = Color.argb(if (active) 110 else 70, Color.red(c), Color.green(c), Color.blue(c))

        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - ring.strokeWidth

        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, ring)

        // 十字線（中心付近は空ける）
        val gap = 5 * density
        val len = r * 0.55f
        canvas.drawLine(cx - len, cy, cx - gap, cy, ring)
        canvas.drawLine(cx + gap, cy, cx + len, cy, ring)
        canvas.drawLine(cx, cy - len, cx, cy - gap, ring)
        canvas.drawLine(cx, cy + gap, cx, cy + len, ring)
        canvas.drawCircle(cx, cy, 2.5f * density, dot)

        canvas.drawText(number.toString(), cx + r * 0.6f, cy - r * 0.35f, label)
    }
}
