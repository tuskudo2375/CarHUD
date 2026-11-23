package com.carhud.companion

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
import android.widget.*

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var deviceList: ListView
    private var btClient: BluetoothClientManager? = null
    private val permissionRequest = 44
    private var devices: List<BluetoothDevice> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        status = TextView(this).apply { textSize = 18f; text = "Bluetooth: not connected" }
        root.addView(status, ViewGroup.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(Button(this).apply {
            text = "Open notification access settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        })
        root.addView(TextView(this).apply { text = "Paired Bluetooth devices"; textSize = 18f; setPadding(0, 20, 0, 8) })
        deviceList = ListView(this)
        root.addView(deviceList, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), permissionRequest)
        } else refreshDevices()
        deviceList.setOnItemClickListener { _, _, position, _ -> connect(devices[position]) }
    }

    private fun refreshDevices() {
        try {
            devices = BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.sortedBy { it.name ?: it.address }?.toList().orEmpty()
            deviceList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, devices.map { "${it.name ?: "Unknown"}\n${it.address}" })
            if (devices.isEmpty()) status.text = "Pair the head unit in Android Bluetooth settings first."
        } catch (_: SecurityException) { status.text = "Bluetooth permission is required." }
    }

    private fun connect(device: BluetoothDevice) {
        btClient?.close()
        val manager = BluetoothClientManager(device) { message -> runOnUiThread { status.text = message } }
        btClient = manager
        manager.onReplyReceived = { key, message -> runOnUiThread { CarNotificationService.instance?.executeQuickReply(key, message) } }
        CarNotificationService.onJsonReady = { manager.sendJson(it) }
        manager.connect()
    }

    override fun onDestroy() {
        CarNotificationService.onJsonReady = null
        btClient?.close()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequest) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) refreshDevices()
            else status.text = "Allow Bluetooth access to list paired devices."
        }
    }
}
