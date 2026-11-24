package com.mapsbridge.headunit

import android.app.KeyguardManager
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
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

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingLaunch: Runnable? = null

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

        launchMapsFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchMapsFromIntent(intent)
    }

    private fun launchMapsFromIntent(sourceIntent: Intent) {
        pendingLaunch?.let(mainHandler::removeCallbacks)
        val navUri = sourceIntent.getStringExtra(EXTRA_NAV_URI).orEmpty()
        val fallbackUrl = sourceIntent.getStringExtra(EXTRA_FALLBACK_URL).orEmpty()
        runCatching {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            activityManager.killBackgroundProcesses(MAPS_PACKAGE)
        }

        val launch = Runnable {
            try {
                when {
                    navUri.isNotBlank() -> startActivity(createMapsIntent(navUri))
                    fallbackUrl.isNotBlank() -> startActivity(createMapsIntent(fallbackUrl))
                }
            } finally {
                pendingLaunch = null
                finish()
            }
        }
        pendingLaunch = launch
        mainHandler.postDelayed(launch, 350L)
    }

    private fun createMapsIntent(uri: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            setPackage(MAPS_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
}
