package com.example.autotapper

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

/** 設定値の保存 */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun interval(c: Context): Long = sp(c).getLong("interval", 100L)
    fun useShizuku(c: Context): Boolean = sp(c).getBoolean("useShizuku", false)

    fun save(c: Context, interval: Long) {
        sp(c).edit().putLong("interval", interval).apply()
    }

    fun setUseShizuku(c: Context, value: Boolean) {
        sp(c).edit().putBoolean("useShizuku", value).apply()
    }
}

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var shizukuStatus: TextView
    private lateinit var chkShizuku: CheckBox
    private lateinit var editInterval: EditText
    private val main = Handler(Looper.getMainLooper())

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Shizukuの許可が得られませんでした", Toast.LENGTH_SHORT).show()
            chkShizuku.isChecked = false
            Prefs.setUseShizuku(this, false)
        }
        // 接続が完了するまで少しかかるので何度か表示を更新
        updateShizukuStatus()
        main.postDelayed({ updateShizukuStatus() }, 1000L)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ShizukuTapper.init()
        Shizuku.addRequestPermissionResultListener(permissionListener)

        statusText = findViewById(R.id.statusText)
        shizukuStatus = findViewById(R.id.shizukuStatus)
        chkShizuku = findViewById(R.id.chkShizuku)
        editInterval = findViewById(R.id.editInterval)
        editInterval.setText(Prefs.interval(this).toString())
        chkShizuku.isChecked = Prefs.useShizuku(this)

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        chkShizuku.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (!ShizukuTapper.isRunning()) {
                    Toast.makeText(this, "Shizukuアプリを開いてサービスを開始してください", Toast.LENGTH_LONG).show()
                    chkShizuku.isChecked = false
                    return@setOnCheckedChangeListener
                }
                Prefs.setUseShizuku(this, true)
                if (ShizukuTapper.hasPermission()) ShizukuTapper.bind() else ShizukuTapper.requestPermission()
            } else {
                Prefs.setUseShizuku(this, false)
            }
            updateShizukuStatus()
            main.postDelayed({ updateShizukuStatus() }, 1000L)
        }

        findViewById<Button>(R.id.btnAdd).setOnClickListener {
            saveSettings()
            val service = TapService.instance
            if (service == null) {
                Toast.makeText(this, "先にユーザー補助で AutoTapper をオンにしてください", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            service.addButton()
            Toast.makeText(this, "連打ボタンを追加しました。ドラッグで配置してください", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnClear).setOnClickListener {
            TapService.instance?.clearButtons()
        }
    }

    override fun onResume() {
        super.onResume()
        statusText.text = if (TapService.instance != null) {
            "✅ ユーザー補助：オン（準備OK）"
        } else {
            "⚠️ ユーザー補助：オフ"
        }
        if (Prefs.useShizuku(this)) ShizukuTapper.bind()
        updateShizukuStatus()
        main.postDelayed({ updateShizukuStatus() }, 1000L)
    }

    override fun onPause() {
        super.onPause()
        saveSettings()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }

    private fun updateShizukuStatus() {
        shizukuStatus.text = when {
            !Prefs.useShizuku(this) -> "Shizukuモード：オフ（通常のユーザー補助で連打）"
            !ShizukuTapper.isRunning() -> "⚠️ Shizukuが起動していません"
            !ShizukuTapper.hasPermission() -> "⚠️ Shizukuの許可待ち"
            !ShizukuTapper.isReady -> "Shizukuに接続中…"
            else -> "✅ Shizukuモード：準備OK"
        }
    }

    private fun saveSettings() {
        // 10ms 未満は端末に負荷がかかるだけなので下限を設ける
        val interval = (editInterval.text.toString().toLongOrNull() ?: 100L).coerceAtLeast(10L)
        Prefs.save(this, interval)
    }
}
