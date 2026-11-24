package com.mapsbridge.headunit

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar

class HeadUnitSetupActivity : Activity() {
    companion object {
        private const val PERMISSION_REQUEST = 506
        private const val WALLPAPER_REQUEST = 507
        private const val PREFS_LAUNCHER = "launcher_prefs"
        private val TIKTOK_PACKAGES = listOf("com.ss.android.ugc.trill", "com.zhiliaoapp.musically")
    }

    private lateinit var statusPill: View
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var solarDateText: TextView
    private lateinit var lunarDateText: TextView
    private lateinit var wallpaperView: ImageView
    private var timeTickReceiverRegistered = false

    private val timeTickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_TIME_TICK) updateCalendarDates()
        }
    }

    private data class AppEntry(val label: String, val packageName: String, val resolveInfo: ResolveInfo)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyImmersiveMode()
        setContentView(R.layout.activity_launcher)

        statusPill = findViewById(R.id.statusPill)
        statusDot = findViewById(R.id.viewStatusDot)
        statusText = findViewById(R.id.tvLauncherStatus)
        solarDateText = findViewById(R.id.tvSolarDate)
        lunarDateText = findViewById(R.id.tvLunarDate)
        wallpaperView = findViewById(R.id.ivWallpaper)
        applyLiquidGlassStyle()
        loadSavedWallpaper()
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
        updateCalendarDates()
        if (!timeTickReceiverRegistered) {
            val filter = IntentFilter(Intent.ACTION_TIME_TICK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(timeTickReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(timeTickReceiver, filter)
            }
            timeTickReceiverRegistered = true
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    override fun onPause() {
        HeadUnitService.onConnectionStateChanged = null
        if (timeTickReceiverRegistered) {
            try { unregisterReceiver(timeTickReceiver) } catch (_: IllegalArgumentException) { }
            timeTickReceiverRegistered = false
        }
        super.onPause()
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != WALLPAPER_REQUEST || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val target = customWallpaperFile()
            contentResolver.openInputStream(uri).use { input ->
                if (input == null) throw IllegalStateException("Không đọc được ảnh đã chọn")
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            displayCustomWallpaper()
        } catch (_: Throwable) {
            Toast.makeText(this, "Không thể lưu hình nền đã chọn.", Toast.LENGTH_LONG).show()
        }
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

    private fun applyLiquidGlassStyle() {
        val density = resources.displayMetrics.density
        findViewById<View>(R.id.liquidGlassClock).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#45FFFFFF"), Color.parseColor("#1AFFFFFF"), Color.parseColor("#28FFFFFF"))
            ).apply {
                cornerRadius = 22f * density
                setStroke((1.2f * density).toInt().coerceAtLeast(1), Color.parseColor("#75FFFFFF"))
            }
            elevation = 6f * density
        }
    }

    private fun updateCalendarDates() {
        if (!::solarDateText.isInitialized || !::lunarDateText.isInitialized) return
        val calendar = Calendar.getInstance(java.util.TimeZone.getTimeZone("GMT+7"), java.util.Locale("vi", "VN"))
        solarDateText.text = LunarCalendarHelper.getSolarDateString(calendar)
        lunarDateText.text = LunarCalendarHelper.getLunarDateString(calendar)
    }

    private fun customWallpaperFile() = File(filesDir, "custom_wallpaper.jpg")

    private fun loadSavedWallpaper() {
        val file = customWallpaperFile()
        if (file.isFile && displayCustomWallpaper()) return
        wallpaperView.setImageResource(R.drawable.bg_launcher_demo)
    }

    private fun displayCustomWallpaper(): Boolean {
        val file = customWallpaperFile()
        if (!file.isFile) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        val targetWidth = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val targetHeight = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > targetWidth * 2 || bounds.outHeight / sampleSize > targetHeight * 2) {
            sampleSize *= 2
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }) ?: return false
        wallpaperView.setImageBitmap(bitmap)
        return true
    }

    private fun chooseWallpaper() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(intent, WALLPAPER_REQUEST)
    }

    private fun restoreDefaultWallpaper() {
        customWallpaperFile().delete()
        wallpaperView.setImageResource(R.drawable.bg_launcher_demo)
    }

    private fun setupDock() {
        renderDockSlots()
        findViewById<View>(R.id.dockApps).setOnClickListener { showAppDrawer() }
    }

    private fun renderDockSlots() {
        val containers = intArrayOf(R.id.dockMaps, R.id.dockTikTok, R.id.dockYouTube)
        val icons = intArrayOf(R.id.ivDockMaps, R.id.ivDockTikTok, R.id.ivDockYouTube)
        val labels = intArrayOf(R.id.tvDockLabel0, R.id.tvDockLabel1, R.id.tvDockLabel2)
        val prefs = getSharedPreferences(PREFS_LAUNCHER, MODE_PRIVATE)

        for (slot in 0..2) {
            val key = "dock_slot_$slot"
            val packageName = prefs.getString(key, null) ?: defaultDockPackage(slot).also {
                prefs.edit().putString(key, it).apply()
            }
            val iconView = findViewById<ImageView>(icons[slot])
            iconView.setImageResource(getVectorIconForPackage(packageName))
            findViewById<TextView>(labels[slot]).text =
                runCatching { packageLabel(packageName) }.getOrDefault(defaultDockLabel(slot))

            findViewById<View>(containers[slot]).apply {
                setOnClickListener { launchApp(packageName) }
                setOnLongClickListener {
                    showAppPickerForSlot(slot)
                    true
                }
            }
        }
    }

    private fun getVectorIconForPackage(pkg: String): Int = when (pkg) {
        "com.google.android.apps.maps" -> R.drawable.ic_vec_maps
        "com.ss.android.ugc.trill", "com.zhiliaoapp.musically" -> R.drawable.ic_vec_tiktok
        "com.google.android.youtube" -> R.drawable.ic_vec_youtube
        else -> R.drawable.ic_vec_app_generic
    }

    private fun defaultDockPackage(slot: Int): String = when (slot) {
        0 -> "com.google.android.apps.maps"
        1 -> findInstalledPackage(TIKTOK_PACKAGES) ?: TIKTOK_PACKAGES.first()
        else -> "com.google.android.youtube"
    }

    private fun defaultDockLabel(slot: Int): String = when (slot) {
        0 -> "Maps"
        1 -> "TikTok"
        else -> "YouTube"
    }

    private fun showMaintenanceOptions() {
        val options = arrayOf(
            "Cấp quyền Hiển thị trên ứng dụng khác",
            "Làm mới kết nối ngay",
            "Mở Cài đặt hệ thống",
            "Đổi hình nền",
            "Khôi phục hình nền mặc định",
            "Tùy chỉnh ứng dụng nhanh"
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
                    3 -> chooseWallpaper()
                    4 -> restoreDefaultWallpaper()
                    5 -> Toast.makeText(this, "Nhấn giữ biểu tượng ứng dụng để đổi lối tắt.", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun showAppDrawer() {
        showAppListDialog("Ứng dụng", queryLaunchableApps()) { app ->
            launchApp(app.packageName)
        }
    }

    private fun showAppPickerForSlot(slot: Int) {
        showAppListDialog("Chọn ứng dụng nhanh", queryLaunchableApps()) { app ->
            getSharedPreferences(PREFS_LAUNCHER, MODE_PRIVATE).edit()
                .putString("dock_slot_$slot", app.packageName)
                .apply()
            renderDockSlots()
            Toast.makeText(this, "Đã đổi lối tắt thành ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun queryLaunchableApps(): List<AppEntry> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(query, 0)
            .map { resolveInfo ->
                AppEntry(
                    label = resolveInfo.loadLabel(packageManager)?.toString().orEmpty(),
                    packageName = resolveInfo.activityInfo.packageName,
                    resolveInfo = resolveInfo
                )
            }
            .distinctBy { it.packageName }
            .sortedWith(compareBy<AppEntry> { appPriority(it.packageName) }.thenBy { it.label.lowercase() })
    }

    private fun showAppListDialog(title: String, apps: List<AppEntry>, onSelect: (AppEntry) -> Unit) {
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
            .setTitle(title)
            .setView(list)
            .setNegativeButton("Đóng", null)
            .create()
        list.setOnItemClickListener { _, _, position, _ ->
            onSelect(apps[position])
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
