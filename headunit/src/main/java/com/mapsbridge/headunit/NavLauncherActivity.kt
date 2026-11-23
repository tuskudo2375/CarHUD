package com.mapsbridge.headunit

import android.app.KeyguardManager
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

class NavLauncherActivity : Activity() {
    companion object {
        const val EXTRA_NAV_URI = "com.mapsbridge.headunit.EXTRA_NAV_URI"
        const val EXTRA_FALLBACK_URL = "com.mapsbridge.headunit.EXTRA_FALLBACK_URL"
        private const val MAPS_PACKAGE = "com.google.android.apps.maps"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        keyguardManager.requestDismissKeyguard(this, null)

        val navUri = intent.getStringExtra(EXTRA_NAV_URI).orEmpty()
        val fallbackUrl = intent.getStringExtra(EXTRA_FALLBACK_URL).orEmpty()
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                when {
                    navUri.isNotBlank() -> startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(navUri))
                            .setPackage(MAPS_PACKAGE)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    )
                    fallbackUrl.isNotBlank() -> startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl))
                            .setPackage(MAPS_PACKAGE)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            } finally {
                finish()
            }
        }, 300L)
    }
}
