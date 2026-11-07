package com.example.autotapper

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import kotlin.math.hypot

/**
 * 画面に置いた連打ボタンをまとめて管理し、連打ループを回す。
 * 音量ボタン（下）を押している間 startTapping()、離したら stopTapping() が呼ばれる。
 * ボタンが複数あるときは 1→2→3… の順に繰り返しタップする。
 * 画面の向きが変わったら onScreenChanged() でボタンを画面内に配置し直す。
 */
class ButtonManager(private val service: TapService) {

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val buttons = mutableListOf<TapButton>()

    private var running = false
    private var index = 0
    private var interval = 100L
    private var useShizuku = false
    private val stepRunnable = Runnable { step() }

    private var lastW = 0
    private var lastH = 0

    val count: Int get() = buttons.size

    /** 今の画面の実サイズ（向きを反映） */
    private fun screenSize(): Point {
        val p = Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(p)
        return p
    }

    fun add() {
        if (running) return
        val s = screenSize()
        lastW = s.x; lastH = s.y
        val density = service.resources.displayMetrics.density
        val size = (64 * density).toInt()
        val offset = buttons.size * (28 * density).toInt()
        buttons += TapButton(
            service, wm, buttons.size + 1,
            x = s.x / 2 - size / 2 + offset,
            y = s.y / 2 - size / 2 + offset,
            size = size,
            screenW = s.x,
            screenH = s.y,
            onRemove = { remove(it) }
        )
    }

    /** 画面の向き・サイズが変わったときに呼ぶ。変わっていなければ何もしない */
    fun onScreenChanged() {
        if (buttons.isEmpty()) return
        val s = screenSize()
        if (s.x == lastW && s.y == lastH) return
        lastW = s.x; lastH = s.y
        buttons.forEach { it.applyScreen(s.x, s.y) }
    }

    private fun remove(button: TapButton) {
        button.destroy()
        buttons.remove(button)
        buttons.forEachIndexed { i, b -> b.view.number = i + 1; b.view.invalidate() }
    }

    fun startTapping() {
        if (running || buttons.isEmpty()) return
        onScreenChanged() // 念のため最新の画面に合わせる
        interval = Prefs.interval(service).coerceAtLeast(10L)
        useShizuku = Prefs.useShizuku(service)
        if (useShizuku && !ShizukuTapper.isReady) {
            ShizukuTapper.bind()
            Toast.makeText(service, "Shizukuに接続できないため、通常モードで連打します", Toast.LENGTH_SHORT).show()
            useShizuku = false
        }
        running = true
        index = 0
        // 連打中はボタンをすり抜け状態にして、タップが下のアプリに届くようにする
        buttons.forEach { it.setTouchable(false); it.view.active = true }
        // フラグ変更が画面に反映されるのを少し待ってから開始
        handler.postDelayed(stepRunnable, 30L)
    }

    fun stopTapping() {
        if (!running) return
        running = false
        handler.removeCallbacks(stepRunnable)
        buttons.forEach { it.setTouchable(true); it.view.active = false }
    }

    private fun step() {
        if (!running) return
        if (buttons.isEmpty()) {
            stopTapping()
            return
        }
        val p = buttons[index % buttons.size].center()
        index++
        val next = { if (running) handler.postDelayed(stepRunnable, interval) }
        if (useShizuku) ShizukuTapper.tap(p.x, p.y) { next() } else service.tap(p.x, p.y) { next() }
    }

    fun clear() {
        stopTapping()
        buttons.forEach { it.destroy() }
        buttons.clear()
    }
}

/**
 * 1個の連打ボタン（位置の目印）。
 * - ドラッグ：移動
 * - 長押し：削除
 * ボタンの中心が連打される位置。縦画面と横画面で別々に位置を覚える。
 */
@SuppressLint("ClickableViewAccessibility")
class TapButton(
    private val service: TapService,
    private val wm: WindowManager,
    number: Int,
    x: Int,
    y: Int,
    private val size: Int,
    private var screenW: Int,
    private var screenH: Int,
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    /** 縦(false)・横(true) それぞれで最後に置いた位置 */
    private val savedPos = HashMap<Boolean, Point>()
    private var landscape = screenW > screenH

    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(service).scaledTouchSlop
    private var destroyed = false

    private var startX = 0
    private var startY = 0
    private var touchX = 0f
    private var touchY = 0f
    private var dragging = false
    private var longPressed = false

    private val longPressRunnable = Runnable {
        longPressed = true
        Toast.makeText(service, "ボタン${view.number}を削除しました", Toast.LENGTH_SHORT).show()
        onRemove(this)
    }

    init {
        clampToScreen()
        view.setOnTouchListener { _, e -> onTouch(e); true }
        wm.addView(view, lp)
    }

    /** 画面サイズ・向きが変わったときに位置を合わせ直す */
    fun applyScreen(w: Int, h: Int) {
        if (destroyed) return
        val newLandscape = w > h
        if (newLandscape != landscape) {
            // 今の向きでの位置を覚えておき、新しい向きで前回の位置があればそこへ戻す
            savedPos[landscape] = Point(lp.x, lp.y)
            val saved = savedPos[newLandscape]
            if (saved != null) {
                lp.x = saved.x
                lp.y = saved.y
            } else {
                // 初めての向きなら、画面上の相対位置を保って移す
                val cx = (lp.x + size / 2f) / screenW
                val cy = (lp.y + size / 2f) / screenH
                lp.x = (cx * w - size / 2f).toInt()
                lp.y = (cy * h - size / 2f).toInt()
            }
            landscape = newLandscape
        }
        screenW = w
        screenH = h
        clampToScreen()
        runCatching { wm.updateViewLayout(view, lp) }
    }

    private fun clampToScreen() {
        lp.x = lp.x.coerceIn(0, (screenW - size).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (screenH - size).coerceAtLeast(0))
    }

    private fun onTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = false
                longPressed = false
                startX = lp.x; startY = lp.y
                touchX = e.rawX; touchY = e.rawY
                handler.postDelayed(longPressRunnable, 700L)
            }
            MotionEvent.ACTION_MOVE -> {
                if (longPressed) return
                val dx = e.rawX - touchX
                val dy = e.rawY - touchY
                if (!dragging && hypot(dx, dy) > slop) {
                    dragging = true
                    handler.removeCallbacks(longPressRunnable)
                }
                if (dragging) {
                    lp.x = startX + dx.toInt()
                    lp.y = startY + dy.toInt()
                    clampToScreen()
                    runCatching { wm.updateViewLayout(view, lp) }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                handler.removeCallbacks(longPressRunnable)
        }
    }

    /** ボタン中心の画面座標 */
    fun center(): PointF {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return PointF(loc[0] + view.width / 2f, loc[1] + view.height / 2f)
    }

    fun setTouchable(touchable: Boolean) {
        if (destroyed) return
        lp.flags = if (touchable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching { wm.updateViewLayout(view, lp) }
    }

    fun destroy() {
        handler.removeCallbacks(longPressRunnable)
        destroyed = true
        runCatching { wm.removeView(view) }
    }
}
