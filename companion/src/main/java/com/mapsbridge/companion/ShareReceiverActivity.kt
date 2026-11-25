package com.mapsbridge.companion

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sharedText = intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (sharedText.isNotEmpty()) {
            BluetoothBridgeService.submitNavigationShare(this, sharedText)
            Toast.makeText(this, "Đang gửi điểm đến sang Máy Phụ...", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Không tìm thấy địa điểm được chia sẻ", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
