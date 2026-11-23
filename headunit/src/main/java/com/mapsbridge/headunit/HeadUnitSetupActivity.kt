package com.mapsbridge.headunit

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

class HeadUnitSetupActivity : Activity() {
    companion object { private const val PERMISSION_REQUEST = 506 }
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        status = TextView(this).apply { text = "Cấp quyền rồi bật chế độ chờ nhận từ Xiaomi"; textSize = 17f }
        root.addView(status, ViewGroup.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(Button(this).apply {
            text = "1. Cấp quyền Hiển thị trên ứng dụng khác"
            setOnClickListener {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivity(intent)
            }
        })
        root.addView(Button(this).apply {
            text = "2. Bật chế độ chờ nhận từ Xiaomi"
            setOnClickListener { startHeadUnitService() }
        })
        root.addView(Button(this).apply {
            text = "Dừng dịch vụ (Cho phép tắt màn hình)"
            setOnClickListener {
                stopService(Intent(this@HeadUnitSetupActivity, HeadUnitService::class.java))
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                status.text = "Đã dừng dịch vụ. Màn hình được phép tắt."
            }
        })
        setContentView(root)
        val permissions = missingRuntimePermissions()
        if (permissions.isNotEmpty()) requestPermissions(permissions, PERMISSION_REQUEST)
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            status.text = if (Settings.canDrawOverlays(this)) "Đã cấp quyền lớp phủ. Bật dịch vụ để nhận địa điểm và thông báo." else "Hãy cấp quyền lớp phủ để hiện thông báo trên Google Maps."
        }
    }

    private fun missingRuntimePermissions(): Array<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyArray()
        return buildList {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.BLUETOOTH_SCAN)
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }

    private fun startHeadUnitService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            status.text = "Hãy cấp quyền hiển thị lớp phủ trước. Dịch vụ vẫn có thể mở Maps khi nhận địa điểm."
        }
        ContextCompat.startForegroundService(this, Intent(this, HeadUnitService::class.java))
        status.text = "MapsBridge đang chờ kết nối Bluetooth từ Xiaomi."
    }

    @Deprecated("Deprecated in Android")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) {
            status.text = if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) "Quyền Bluetooth và thông báo đã sẵn sàng." else "Cần quyền Bluetooth và thông báo để duy trì dịch vụ."
        }
    }
}
