package com.example.autotapper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * ユーザー補助サービス。
 * - 連打ボタンの表示（ButtonManager）
 * - 音量ボタン（下）の押下・解放の検知 → 連打の開始・停止
 * - dispatchGesture() による実際のタップ送信
 */
class TapService : AccessibilityService() {

    companion object {
        /** サービスが有効なときだけ非null */
        var instance: TapService? = null
            private set
    }

    private var manager: ButtonManager? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 画面の内容は読まないので何もしない
    }

    override fun onInterrupt() {
        manager?.stopTapping()
    }

    /**
     * 音量ボタン（下）を押している間だけ連打する。
     * 連打ボタンが1つも無いときは横取りせず、普通の音量操作として通す。
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        val m = manager
        if (m == null || m.count == 0) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) m.startTapping()
            KeyEvent.ACTION_UP -> m.stopTapping()
        }
        return true // 音量が下がらないように消費する
    }

    override fun onUnbind(intent: Intent?): Boolean {
        clearButtons()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearButtons()
        instance = null
        super.onDestroy()
    }

    /** 連打ボタンを1つ画面に追加する */
    fun addButton() {
        val m = manager ?: ButtonManager(this).also { manager = it }
        m.add()
    }

    /** 連打ボタンをすべて消す */
    fun clearButtons() {
        manager?.clear()
        manager = null
    }

    /** 画面座標 (x, y) を1回タップする。完了・失敗どちらでも onDone を呼ぶ */
    fun tap(x: Float, y: Float, onDone: () -> Unit) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 10L))
            .build()

        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = onDone()
            override fun onCancelled(gestureDescription: GestureDescription?) = onDone()
        }, null)

        if (!accepted) onDone()
    }
}
