package com.carhud.display

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

class HeadUnitActivity : Activity() {
    private lateinit var tvStatus: TextView
    private lateinit var tvNavTitle: TextView
    private lateinit var tvNavDetail: TextView
    private lateinit var tvNotifSender: TextView
    private lateinit var tvNotifMessage: TextView
    private lateinit var layoutQuickReply: LinearLayout
    private lateinit var btServer: BluetoothServerManager
    private var currentNotifKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_head_unit)
        tvStatus = findViewById(R.id.tvStatus)
        tvNavTitle = findViewById(R.id.tvNavTitle)
        tvNavDetail = findViewById(R.id.tvNavDetail)
        tvNotifSender = findViewById(R.id.tvNotifSender)
        tvNotifMessage = findViewById(R.id.tvNotifMessage)
        layoutQuickReply = findViewById(R.id.layoutQuickReply)
        enterImmersiveMode()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 52)
        } else startBluetooth()
        findViewById<Button>(R.id.btnReply1).setOnClickListener { sendReply("Đang lái xe, lát gọi lại nhé!") }
        findViewById<Button>(R.id.btnReply2).setOnClickListener { sendReply("OK, đang qua liền!") }
    }

    private fun startBluetooth() {
        if (::btServer.isInitialized) return
        btServer = BluetoothServerManager { status -> runOnUiThread { tvStatus.text = status } }
        btServer.onDataReceived = ::handleData
        btServer.start()
    }

    @Deprecated("Deprecated in Android")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 52) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startBluetooth()
            else tvStatus.text = "Allow Bluetooth access to start the server"
        }
    }

    private fun handleData(line: String) {
        val json = try { JSONObject(line) } catch (_: Exception) { return }
        runOnUiThread {
            when (json.optString("type")) {
                "NAV" -> {
                    tvNavTitle.text = json.optString("title")
                    tvNavDetail.text = json.optString("text")
                }
                "NOTIF" -> {
                    currentNotifKey = json.optString("key").takeIf { it.isNotEmpty() }
                    tvNotifSender.text = json.optString("title", json.optString("app"))
                    tvNotifMessage.text = json.optString("text")
                    layoutQuickReply.visibility = if (json.optBoolean("canReply")) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun sendReply(message: String) {
        val key = currentNotifKey ?: return
        btServer.sendQuickReply(key, message)
        layoutQuickReply.visibility = View.GONE
        tvNotifMessage.text = "$message\n\nReply sent"
    }

    private fun enterImmersiveMode() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onDestroy() {
        if (::btServer.isInitialized) btServer.stop()
        super.onDestroy()
    }
}
