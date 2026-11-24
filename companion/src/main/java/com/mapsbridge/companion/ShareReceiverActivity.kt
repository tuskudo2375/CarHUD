package com.mapsbridge.companion

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import org.json.JSONObject

class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sharedText = intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (sharedText.isNotEmpty()) {
            val message = JSONObject().put("type", "NAV_SHARE").put("payload", sharedText).toString()
            BluetoothBridgeService.sendOrQueue(this, message)
            Toast.makeText(this, "Đã gửi điểm đến sang Máy Phụ!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Không tìm thấy địa điểm được chia sẻ", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
