package com.mapsbridge.companion

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.content.ContextCompat

class SetupActivity : Activity() {
    companion object { private const val PERMISSION_REQUEST = 405 }
    private lateinit var status: TextView
    private lateinit var deviceList: ListView
    private var devices: List<BluetoothDevice> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        status = TextView(this).apply { text = "Chọn Thiết bị Phụ đã ghép đôi để kết nối"; textSize = 17f }
        root.addView(status, ViewGroup.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(Button(this).apply {
            text = "1. Cấp quyền Thông báo"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        })
        root.addView(Button(this).apply {
            text = "2. Bật dịch vụ kết nối ngầm"
            setOnClickListener { startBridgeService() }
        })
        root.addView(TextView(this).apply { text = "Thiết bị Bluetooth đã ghép đôi"; textSize = 18f; setPadding(0, 16, 0, 8) })
        deviceList = ListView(this)
        root.addView(deviceList, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        deviceList.setOnItemClickListener { _, _, index, _ -> selectDevice(devices[index]) }
        if (missingRuntimePermissions().isNotEmpty()) requestPermissions(missingRuntimePermissions(), PERMISSION_REQUEST)
        else refreshDevices()
    }

    private fun missingRuntimePermissions(): Array<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyArray()
        return buildList {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.BLUETOOTH_SCAN)
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }

    private fun refreshDevices() {
        try {
            devices = BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.sortedBy { it.name ?: it.address }?.toList().orEmpty()
            deviceList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
                devices.map { "${displayDeviceName(it)}\n${it.address}" })
            if (devices.isEmpty()) status.text = "Hãy ghép đôi Thiết bị Phụ trong cài đặt Bluetooth trước."
        } catch (_: SecurityException) {
            status.text = "Cần cấp quyền Bluetooth để xem thiết bị đã ghép đôi."
        }
    }

    private fun selectDevice(device: BluetoothDevice) {
        try {
            getSharedPreferences("mapsbridge_prefs", MODE_PRIVATE).edit().putString("note8_mac", device.address).apply()
            status.text = "Đã lưu ${displayDeviceName(device)}; đang kết nối…"
            startBridgeService()
        } catch (_: SecurityException) {
            status.text = "Cần cấp quyền Bluetooth để chọn thiết bị."
        }
    }

    private fun startBridgeService() {
        val serviceIntent = Intent(this, BluetoothBridgeService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        status.text = "MapsBridge Chính đang chạy; chia sẻ địa điểm sang Máy Phụ."
    }

    private fun displayDeviceName(device: BluetoothDevice): String =
        (device.name ?: "Thiết bị Bluetooth")
            .replace(Regex("Note ?8", RegexOption.IGNORE_CASE), "Thiết bị Phụ")

    @Deprecated("Deprecated in Android")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) refreshDevices()
            else status.text = "Hãy cấp quyền Bluetooth và thông báo để duy trì kết nối."
        }
    }
}
