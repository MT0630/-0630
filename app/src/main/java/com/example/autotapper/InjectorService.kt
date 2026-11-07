package com.example.autotapper

import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.lang.reflect.Method
import kotlin.system.exitProcess

/**
 * Shizuku によって shell 権限の別プロセスで起動されるサービス。
 * InputManager に直接タッチイベントを注入する。
 * ユーザー補助の dispatchGesture() と違い、本物の指のタッチで打ち消されない。
 * 本物のタッチパネルとは別の「仮想の指（別デバイス）」として送る。
 */
class InjectorService : IInjector.Stub() {

    companion object {
        /** 本物のタッチパネルと区別するための仮のデバイスID */
        private const val VIRTUAL_DEVICE_ID = 0x7A7A
        private const val INJECT_MODE_ASYNC = 0
    }

    private val target: Any
    private val injectMethod: Method

    init {
        // Android 14 以降は InputManagerGlobal、それより前は InputManager に注入メソッドがある
        val (t, m) = try {
            val cls = Class.forName("android.hardware.input.InputManagerGlobal")
            cls.getMethod("getInstance").invoke(null) to
                cls.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        } catch (_: Throwable) {
            val cls = Class.forName("android.hardware.input.InputManager")
            cls.getMethod("getInstance").invoke(null) to
                cls.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        }
        target = t!!
        injectMethod = m
    }

    override fun destroy() {
        exitProcess(0)
    }

    override fun check(): String = "ok uid=${Process.myUid()}"

    override fun tap(x: Float, y: Float, durationMs: Long): Boolean {
        val downTime = SystemClock.uptimeMillis()
        val ok1 = inject(event(downTime, downTime, MotionEvent.ACTION_DOWN, x, y))
        if (durationMs > 0) SystemClock.sleep(durationMs)
        val ok2 = inject(event(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y))
        return ok1 && ok2
    }

    private fun event(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
        })
        return MotionEvent.obtain(
            downTime, eventTime, action, 1, props, coords,
            0, 0, 1f, 1f, VIRTUAL_DEVICE_ID, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
    }

    private fun inject(e: MotionEvent): Boolean = try {
        injectMethod.invoke(target, e, INJECT_MODE_ASYNC) as Boolean
    } catch (_: Throwable) {
        false
    } finally {
        e.recycle()
    }
}
