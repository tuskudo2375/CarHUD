package com.mapsbridge.headunit

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class HeadUnitSetupActivity : Activity() {
    companion object {
        private const val PERMISSION_REQUEST = 506
        private val TIKTOK_PACKAGES = listOf("com.ss.android.ugc.trill", "com.zhiliaoapp.musically")
    }

    private lateinit var statusPill: View
    private lateinit var statusDot: View
    private lateinit var statusText: TextView

    private data class AppEntry(val label: String, val packageName: String, val resolveInfo: ResolveInfo)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveMode()
        setContentView(R.layout.activity_launcher)

        statusPill = findViewById(R.id.statusPill)
        statusDot = findViewById(R.id.viewStatusDot)
        statusText = findViewById(R.id.tvLauncherStatus)
        statusPill.setOnLongClickListener {
            showMaintenanceOptions()
            true
        }

        setupDock()
        renderConnectionState(HeadUnitService.isConnected)

        val permissions = missingRuntimePermissions()
        if (permissions.isNotEmpty()) {
            requestPermissions(permissions, PERMISSION_REQUEST)
        } else {
            startHeadUnitService()
        }
    }

    override fun onResume() {
        super.onResume()
        HeadUnitService.onConnectionStateChanged = { connected -> renderConnectionState(connected) }
        renderConnectionState(HeadUnitService.isConnected)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    override fun onPause() {
        HeadUnitService.onConnectionStateChanged = null
        super.onPause()
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        // Keep the selected home screen in place.
    }

    private fun applyImmersiveMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    private fun setupDock() {
        val mapsIcon = findViewById<ImageView>(R.id.ivDockMaps)
        val tiktokIcon = findViewById<ImageView>(R.id.ivDockTikTok)
        val youtubeIcon = findViewById<ImageView>(R.id.ivDockYouTube)
        val mapsDrawable = loadAppIcon("com.google.android.apps.maps")
        mapsIcon.setImageDrawable(mapsDrawable ?: getDrawable(android.R.drawable.ic_menu_mapmode))
        if (mapsDrawable == null) mapsIcon.setColorFilter(Color.WHITE)
        val tiktokPackage = findInstalledPackage(TIKTOK_PACKAGES)
        val tiktokDrawable = tiktokPackage?.let(::loadAppIcon)
        tiktokIcon.setImageDrawable(tiktokDrawable ?: getDrawable(android.R.drawable.ic_media_play))
        if (tiktokDrawable == null) tiktokIcon.setColorFilter(Color.WHITE)
        val youtubeDrawable = loadAppIcon("com.google.android.youtube")
        youtubeIcon.setImageDrawable(youtubeDrawable ?: getDrawable(android.R.drawable.ic_media_play))
        if (youtubeDrawable == null) youtubeIcon.setColorFilter(Color.WHITE)

        findViewById<View>(R.id.dockMaps).setOnClickListener { launchApp("com.google.android.apps.maps") }
        findViewById<View>(R.id.dockTikTok).setOnClickListener {
            if (tiktokPackage != null) launchApp(tiktokPackage) else showAppUnavailable("TikTok")
        }
        findViewById<View>(R.id.dockYouTube).setOnClickListener { launchApp("com.google.android.youtube") }
        findViewById<View>(R.id.dockApps).setOnClickListener { showAppDrawer() }
    }

    private fun showMaintenanceOptions() {
        val options = arrayOf(
            "Cấp quyền Hiển thị trên ứng dụng khác",
            "Làm mới kết nối ngay",
            "Mở Cài đặt hệ thống"
        )
        AlertDialog.Builder(this)
            .setTitle("Bảo trì MapsBridge")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    })
                    1 -> HeadUnitService.instance?.forceRestartListener()
                        ?: startHeadUnitService()
                    2 -> startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
            .show()
    }

    private fun showAppDrawer() {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(query, 0)
            .map { resolveInfo ->
                AppEntry(
                    label = resolveInfo.loadLabel(packageManager)?.toString().orEmpty(),
                    packageName = resolveInfo.activityInfo.packageName,
                    resolveInfo = resolveInfo
                )
            }
            .distinctBy { it.packageName }
            .sortedWith(compareBy<AppEntry> { appPriority(it.packageName) }.thenBy { it.label.lowercase() })

        val list = ListView(this)
        list.setBackgroundColor(Color.rgb(18, 18, 18))
        list.adapter = object : BaseAdapter() {
            override fun getCount(): Int = apps.size
            override fun getItem(position: Int): AppEntry = apps[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView as? LinearLayout ?: LinearLayout(this@HeadUnitSetupActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    addView(ImageView(this@HeadUnitSetupActivity).apply {
                        id = android.R.id.icon
                        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                    })
                    addView(TextView(this@HeadUnitSetupActivity).apply {
                        id = android.R.id.text1
                        textSize = 16f
                        setTextColor(Color.WHITE)
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(14)
                    })
                }
                val app = getItem(position)
                row.findViewById<ImageView>(android.R.id.icon).setImageDrawable(
                    runCatching { app.resolveInfo.loadIcon(packageManager) }.getOrNull()
                )
                row.findViewById<TextView>(android.R.id.text1).text = app.label
                return row
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Ứng dụng")
            .setView(list)
            .setNegativeButton("Đóng", null)
            .create()
        list.setOnItemClickListener { _, _, position, _ ->
            launchApp(apps[position].packageName)
            dialog.dismiss()
        }
        dialog.show()
        dialog.window?.setLayout(dp(340), dp(520))
    }

    private fun appPriority(packageName: String): Int = when {
        packageName == "com.google.android.apps.maps" -> 0
        packageName in TIKTOK_PACKAGES -> 1
        packageName == "com.google.android.youtube" -> 2
        else -> 3
    }

    private fun launchApp(packageName: String) {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent == null) {
                showAppUnavailable(packageLabel(packageName))
                return
            }
            startActivity(launchIntent)
        } catch (_: Throwable) {
            showAppUnavailable(packageLabel(packageName))
        }
    }

    private fun loadAppIcon(packageName: String) =
        runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull()

    private fun findInstalledPackage(candidates: List<String>): String? =
        candidates.firstOrNull { packageManager.getLaunchIntentForPackage(it) != null }

    private fun packageLabel(packageName: String): String =
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString() }
            .getOrDefault(packageName)

    private fun showAppUnavailable(label: String) {
        Toast.makeText(this, "$label chưa được cài đặt.", Toast.LENGTH_SHORT).show()
    }

    private fun renderConnectionState(connected: Boolean) {
        if (!::statusDot.isInitialized) return
        statusDot.setBackgroundResource(
            if (connected) R.drawable.launcher_status_connected else R.drawable.launcher_status_disconnected
        )
        statusText.text = if (connected) "Đã kết nối" else "Đang chờ..."
    }

    private fun startHeadUnitService() {
        ContextCompat.startForegroundService(this, Intent(this, HeadUnitService::class.java))
    }

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
            startHeadUnitService()
        } else if (requestCode == PERMISSION_REQUEST) {
            Toast.makeText(this, "Cần cấp quyền Bluetooth để kết nối Máy Chính.", Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

}
