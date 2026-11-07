package com.example.autotapper

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/** 設定値の保存 */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun interval(c: Context): Long = sp(c).getLong("interval", 100L)

    fun save(c: Context, interval: Long) {
        sp(c).edit().putLong("interval", interval).apply()
    }
}

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var editInterval: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        editInterval = findViewById(R.id.editInterval)
        editInterval.setText(Prefs.interval(this).toString())

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
    }

    override fun onPause() {
        super.onPause()
        saveSettings()
    }

    private fun saveSettings() {
        // 10ms 未満は端末に負荷がかかるだけなので下限を設ける
        val interval = (editInterval.text.toString().toLongOrNull() ?: 100L).coerceAtLeast(10L)
        Prefs.save(this, interval)
    }
}
