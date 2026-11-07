package com.example.autotapper

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import kotlin.math.hypot

/** 画面に置いた連打ボタンをまとめて管理する */
class ButtonManager(private val service: TapService) {

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val buttons = mutableListOf<TapButton>()

    val count: Int get() = buttons.size

    fun add() {
        val m = service.resources.displayMetrics
        val size = (64 * m.density).toInt()
        val offset = buttons.size * (28 * m.density).toInt()
        buttons += TapButton(
            service, wm, buttons.size + 1,
            x = m.widthPixels / 2 - size / 2 + offset,
            y = m.heightPixels / 2 - size / 2 + offset,
            size = size,
            onRemove = { remove(it) }
        )
    }

    private fun remove(button: TapButton) {
        button.destroy()
        buttons.remove(button)
        buttons.forEachIndexed { i, b -> b.view.number = i + 1; b.view.invalidate() }
    }

    fun stopAll() = buttons.forEach { it.stop() }

    fun clear() {
        buttons.forEach { it.destroy() }
        buttons.clear()
    }
}

/**
 * 1個の連打ボタン。
 * - タップ：連打開始（もう一度タップで停止）
 * - ドラッグ：移動
 * - 長押し：削除
 * ボタンの中心が連打される位置。連打の瞬間だけボタンを「タッチを受けない」状態にして、
 * 送ったタップが下のアプリに届くようにしている。
 */
@SuppressLint("ClickableViewAccessibility")
class TapButton(
    private val service: TapService,
    private val wm: WindowManager,
    number: Int,
    x: Int,
    y: Int,
    size: Int,
    private val onRemove: (TapButton) -> Unit
) {
    val view = TargetView(service, number)

    private val lp = WindowManager.LayoutParams(
        size, size,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        this.x = x
        this.y = y
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(service).scaledTouchSlop

    private var running = false
    private var destroyed = false
    private var interval = 100L
    private var maxTaps = 0
    private var tapsDone = 0

    // タッチ状態
    private var startX = 0
    private var startY = 0
    private var touchX = 0f
    private var touchY = 0f
    private var dragging = false
    private var longPressed = false
    private var ignoreUntilUp = false

    private val cycleRunnable = Runnable { cycle() }
    private val fireRunnable = Runnable { fire() }
    private val longPressRunnable = Runnable {
        longPressed = true
        Toast.makeText(service, "ボタン${view.number}を削除しました", Toast.LENGTH_SHORT).show()
        onRemove(this)
    }

    init {
        view.setOnTouchListener { _, e -> onTouch(e); true }
        wm.addView(view, lp)
    }

    private fun onTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (running) {
                    // 連打中は触れた瞬間に止める（UPを待つと連打のタップに割り込まれるため）
                    stop()
                    ignoreUntilUp = true
                    return
                }
                ignoreUntilUp = false
                dragging = false
                longPressed = false
                startX = lp.x; startY = lp.y
                touchX = e.rawX; touchY = e.rawY
                handler.postDelayed(longPressRunnable, 700L)
            }
            MotionEvent.ACTION_MOVE -> {
                if (ignoreUntilUp || longPressed) return
                val dx = e.rawX - touchX
                val dy = e.rawY - touchY
                if (!dragging && hypot(dx, dy) > slop) {
                    dragging = true
                    handler.removeCallbacks(longPressRunnable)
                }
                if (dragging) {
                    lp.x = startX + dx.toInt()
                    lp.y = startY + dy.toInt()
                    runCatching { wm.updateViewLayout(view, lp) }
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                if (!ignoreUntilUp && !dragging && !longPressed) start()
            }
            MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPressRunnable)
        }
    }

    private fun start() {
        interval = Prefs.interval(service).coerceAtLeast(10L)
        maxTaps = Prefs.maxTaps(service)
        tapsDone = 0
        running = true
        view.active = true
        cycle()
    }

    fun stop() {
        running = false
        handler.removeCallbacks(cycleRunnable)
        handler.removeCallbacks(fireRunnable)
        setTouchable(true)
        view.active = false
    }

    private fun cycle() {
        if (!running) return
        if (maxTaps > 0 && tapsDone >= maxTaps) {
            stop()
            return
        }
        setTouchable(false)
        // 画面側にフラグ変更が反映されるのを少し待ってからタップする
        handler.postDelayed(fireRunnable, 20L)
    }

    private fun fire() {
        if (!running) {
            setTouchable(true)
            return
        }
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val cx = loc[0] + view.width / 2f
        val cy = loc[1] + view.height / 2f
        tapsDone++
        service.tap(cx, cy) {
            setTouchable(true) // タップ間はボタンを触れる状態に戻す（停止できるように）
            if (running) handler.postDelayed(cycleRunnable, interval)
        }
    }

    private fun setTouchable(touchable: Boolean) {
        if (destroyed) return
        val newFlags = if (touchable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (newFlags == lp.flags) return
        lp.flags = newFlags
        runCatching { wm.updateViewLayout(view, lp) }
    }

    fun destroy() {
        stop()
        handler.removeCallbacks(longPressRunnable)
        destroyed = true
        runCatching { wm.removeView(view) }
    }
}
