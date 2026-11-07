package com.example.autotapper

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

/** アプリ側から Shizuku のユーザーサービス（InjectorService）を使ってタップを送る */
object ShizukuTapper {

    const val REQUEST_CODE = 7070

    @Volatile
    private var injector: IInjector? = null
    private var initialized = false
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val args by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, InjectorService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("injector")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                injector = IInjector.Stub.asInterface(binder)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            injector = null
        }
    }

    /** 何度呼んでもよい。Shizuku の準備ができ次第、自動でサービスにつなぐ */
    fun init() {
        if (initialized) return
        initialized = true
        Shizuku.addBinderReceivedListenerSticky { bind() }
        Shizuku.addBinderDeadListener { injector = null }
        Shizuku.addRequestPermissionResultListener { _, result ->
            if (result == PackageManager.PERMISSION_GRANTED) bind()
        }
    }

    /** Shizuku アプリが起動していて使える状態か */
    fun isRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun hasPermission(): Boolean = try {
        isRunning() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun requestPermission() {
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
    }

    /** タップを送れる状態か */
    val isReady: Boolean get() = injector != null

    fun bind() {
        if (injector != null || !hasPermission()) return
        runCatching { Shizuku.bindUserService(args, connection) }
    }

    /** (x, y) をタップ。完了したらメインスレッドで onDone を呼ぶ */
    fun tap(x: Float, y: Float, onDone: () -> Unit) {
        val inj = injector
        if (inj == null) {
            onDone()
            return
        }
        executor.execute {
            runCatching { inj.tap(x, y, 10L) }
            main.post(onDone)
        }
    }
}
