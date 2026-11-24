package com.mapsbridge.headunit

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

class HeadUnitSetupActivity : Activity() {
    companion object { private const val PERMISSION_REQUEST = 506 }

    private lateinit var tvLiveStatus: TextView
    private lateinit var overlayPermissionButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(24))
            setBackgroundColor(Color.BLACK)
        }
        root.addView(TextView(this).apply {
            text = "MapsBridge Note8"
            textSize = 25f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }, ViewGroup.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))

        tvLiveStatus = TextView(this).apply {
            text = HeadUnitService.currentStatus
            textSize = 23f
            setTextColor(Color.rgb(0, 229, 255))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), dp(22), dp(16), dp(22))
            setBackgroundColor(Color.rgb(18, 18, 18))
        }
        root.addView(tvLiveStatus, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(24)
            bottomMargin = dp(22)
        })

        overlayPermissionButton = Button(this).apply {
            text = "Cấp quyền Hiển thị trên ứng dụng khác"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        root.addView(overlayPermissionButton, buttonLayoutParams())

        root.addView(Button(this).apply {
            text = "Làm mới kết nối ngay"
            setOnClickListener {
                val service = HeadUnitService.instance
                if (service != null) service.forceRestartListener()
                else ContextCompat.startForegroundService(this@HeadUnitSetupActivity, Intent(this@HeadUnitSetupActivity, HeadUnitService::class.java))
            }
        }, buttonLayoutParams())

        root.addView(Button(this).apply {
            text = "Tắt hoàn toàn dịch vụ"
            setOnClickListener {
                stopService(Intent(this@HeadUnitSetupActivity, HeadUnitService::class.java))
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                tvLiveStatus.text = "Đã dừng dịch vụ"
                refreshOverlayPermissionButton()
            }
        }, buttonLayoutParams())

        setContentView(root)
        refreshOverlayPermissionButton()

        val permissions = missingRuntimePermissions()
        if (permissions.isNotEmpty()) {
            requestPermissions(permissions, PERMISSION_REQUEST)
        } else {
            ContextCompat.startForegroundService(this, Intent(this, HeadUnitService::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        HeadUnitService.onStatusChanged = { tvLiveStatus.text = it }
        if (::tvLiveStatus.isInitialized) tvLiveStatus.text = HeadUnitService.currentStatus
        refreshOverlayPermissionButton()
    }

    override fun onPause() {
        HeadUnitService.onStatusChanged = null
        super.onPause()
    }

    private fun refreshOverlayPermissionButton() {
        if (!::overlayPermissionButton.isInitialized) return
        val permissionMissing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)
        overlayPermissionButton.visibility = if (permissionMissing) View.VISIBLE else View.GONE
        if (permissionMissing) overlayPermissionButton.setBackgroundColor(Color.rgb(255, 202, 40))
    }

    private fun buttonLayoutParams() = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = dp(10)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun missingRuntimePermissions(): Array<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyArray()
        return buildList {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.BLUETOOTH_SCAN)
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }

    @Deprecated("Deprecated in Android")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST && missingRuntimePermissions().none {
                it == Manifest.permission.BLUETOOTH_CONNECT || it == Manifest.permission.BLUETOOTH_SCAN
            }) {
            ContextCompat.startForegroundService(this, Intent(this, HeadUnitService::class.java))
            tvLiveStatus.text = HeadUnitService.currentStatus
        } else if (requestCode == PERMISSION_REQUEST) {
            tvLiveStatus.text = "Bluetooth cần quyền truy cập để kết nối Xiaomi."
        }
    }
}
