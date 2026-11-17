package com.flyd.bgrec

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/**
 * 只有一个红色按钮：点一下开始后台录超广角，再点一下停止保存。
 */
class MainActivity : Activity() {

    private lateinit var btnRecord: Button
    private lateinit var tvStatus: TextView

    private val prefs by lazy { getSharedPreferences("bgrec", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnRecord = findViewById(R.id.btnRecord)
        tvStatus = findViewById(R.id.tvStatus)
        btnRecord.setOnClickListener { toggle() }

        askBatteryOnce()
    }

    override fun onResume() {
        super.onResume()
        RecState.bind { refresh(it) }
        refresh(RecState.status)
    }

    override fun onPause() {
        RecState.bind(null)
        super.onPause()
    }

    /** 第一次打开时顺手把电池优化白名单要一下，否则熄屏久了会被系统杀 */
    private fun askBatteryOnce() {
        if (prefs.getBoolean("battery_asked", false)) return
        prefs.edit().putBoolean("battery_asked", true).apply()
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) return
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (ignored: Throwable) {
        }
    }

    private fun toggle() {
        if (RecState.recording) {
            sendStop()
            tvStatus.text = "正在保存…"
            return
        }

        val need = arrayListOf<String>()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.CAMERA)
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), REQ_PERMISSIONS)
            return
        }
        startRecording()
    }

    private fun startRecording() {
        val audio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val i = Intent(this, RecorderService::class.java)
            .setAction(RecorderService.ACTION_START)
            .putExtra(RecorderService.EXTRA_AUDIO, audio)
        try {
            startForegroundService(i)
            tvStatus.text = "正在打开超广角…"
        } catch (e: Throwable) {
            Toast.makeText(this, "启动失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun sendStop() {
        try {
            startService(
                Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP)
            )
        } catch (e: Throwable) {
            Toast.makeText(this, "停止失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            Toast.makeText(this, "没有相机权限，无法录制", Toast.LENGTH_LONG).show()
        }
    }

    private fun refresh(text: String) {
        tvStatus.text = text
        if (RecState.recording) {
            btnRecord.setText(R.string.btn_recording)
            btnRecord.setBackgroundResource(R.drawable.bg_rec_circle_stop)
        } else {
            btnRecord.setText(R.string.btn_record)
            btnRecord.setBackgroundResource(R.drawable.bg_rec_circle)
        }
    }

    companion object {
        private const val REQ_PERMISSIONS = 1001
    }
}
