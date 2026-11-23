package com.mapsbridge.headunit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class HeadUnitService : Service() {
    companion object {
        private const val TAG = "MapsBridgeServer"
        private const val CHANNEL_ID = "mapsbridge_headunit"
        private const val NOTIFICATION_ID = 7201
        val SERVICE_UUID: UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66")
    }

    private val running = AtomicBoolean(false)
    private val writeLock = Any()
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var clientSocket: BluetoothSocket? = null
    @Volatile private var serverThread: Thread? = null
    @Volatile private var status = "Đang chờ Xiaomi kết nối"
    private lateinit var overlayManager: OverlayNotifManager
    private var keepAwakeWindowManager: WindowManager? = null
    private var keepAwakeView: View? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        overlayManager = OverlayNotifManager(
            this,
            onActionClick = { key, actionIndex, replyText -> sendActionClick(key, actionIndex, replyText) },
            onReturnToMaps = ::openMapsHome
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(status))
        attachKeepAwakeOverlay()
        if (running.compareAndSet(false, true)) {
            serverThread = Thread(::serverLoop, "MapsBridge-RFCOMM-Server").also { it.start() }
        }
        return START_STICKY
    }

    private fun serverLoop() {
        while (running.get()) {
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter() ?: error("Bluetooth không khả dụng")
                if (!adapter.isEnabled) {
                    updateStatus("Hãy bật Bluetooth trên Note8")
                    Thread.sleep(2_000)
                    continue
                }
                updateStatus("Đang chờ Xiaomi kết nối")
                val listener = adapter.listenUsingRfcommWithServiceRecord("MapsBridge", SERVICE_UUID)
                serverSocket = listener
                val peer = listener.accept()
                clientSocket = peer
                try { listener.close() } catch (_: Exception) { }
                serverSocket = null
                updateStatus("Đã kết nối ${peer.remoteDevice.name ?: "thiết bị Bluetooth"}")
                val reader = BufferedReader(InputStreamReader(peer.inputStream, StandardCharsets.UTF_8))
                while (running.get() && peer.isConnected) {
                    val line = reader.readLine() ?: break
                    handleMessage(line)
                }
            } catch (e: Exception) {
                if (running.get()) {
                    Log.i(TAG, "RFCOMM disconnected; restarting listener", e)
                    updateStatus("Mất kết nối; đang mở lại Bluetooth")
                }
            } finally {
                try { clientSocket?.close() } catch (_: Exception) { }
                clientSocket = null
                try { serverSocket?.close() } catch (_: Exception) { }
                serverSocket = null
            }
        }
    }

    private fun handleMessage(line: String) {
        val message = try { JSONObject(line) } catch (e: Exception) {
            Log.w(TAG, "Ignoring malformed message", e)
            return
        }
        when (message.optString("type")) {
            "NAV_SHARE" -> {
                wakeScreen()
                val payload = message.optString("payload")
                Thread({
                    val resolved = UrlResolver.extractDestination(payload)
                    val query = resolved.query
                    val fallbackUrl = resolved.fallbackUrl
                    when {
                        !query.isNullOrBlank() -> launchNavLauncher(
                            NavLauncherActivity.EXTRA_NAV_URI,
                            "google.navigation:q=${Uri.encode(query)}&mode=l",
                            query
                        )
                        !fallbackUrl.isNullOrBlank() -> launchNavLauncher(
                            NavLauncherActivity.EXTRA_FALLBACK_URL,
                            fallbackUrl,
                            null
                        )
                        else -> updateStatus("Không nhận diện được địa điểm")
                    }
                }, "MapsBridge-Resolve-URL").start()
            }
            "NOTIF" -> {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (powerManager.isInteractive) {
                    overlayManager.show(
                        key = message.optString("key"),
                        appName = message.optString("appName"),
                        title = message.optString("title"),
                        text = message.optString("text"),
                        actions = message.optJSONArray("actions") ?: org.json.JSONArray()
                    )
                }
            }
        }
    }

    private fun launchNavLauncher(extraName: String, extraValue: String, queryForStatus: String?) {
        try {
            val launcherIntent = Intent(this, NavLauncherActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(extraName, extraValue)
            }
            startActivity(launcherIntent)
            updateStatus(
                if (queryForStatus != null) "Đang dẫn đường đến $queryForStatus"
                else "Đang mở liên kết trong Google Maps"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unable to launch Google Maps trampoline", e)
            updateStatus("Không mở được Google Maps")
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "MapsBridge:WakeLock"
            )
            wakeLock.acquire(5_000L)
        } catch (e: Exception) {
            Log.w(TAG, "Could not wake screen", e)
        }
    }

    private fun attachKeepAwakeOverlay() {
        if (keepAwakeView != null || !Settings.canDrawOverlays(this)) return
        val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = View(this).apply { alpha = 0f }
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        try {
            manager.addView(view, params)
            keepAwakeWindowManager = manager
            keepAwakeView = view
        } catch (e: Exception) {
            Log.w(TAG, "Could not attach keep-awake overlay", e)
        }
    }

    private fun detachKeepAwakeOverlay() {
        val view = keepAwakeView ?: return
        runCatching { keepAwakeWindowManager?.removeView(view) }
        keepAwakeView = null
        keepAwakeWindowManager = null
    }

    private fun launchGoogleMapsNavigation(query: String) {
        if (query.trimStart().startsWith("http://", ignoreCase = true) ||
            query.trimStart().startsWith("https://", ignoreCase = true)) {
            openUrlInGoogleMaps(query)
            return
        }
        try {
            val uri = Uri.parse("google.navigation:q=${Uri.encode(query)}&mode=l")
            val mapIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.google.android.apps.maps")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(mapIntent)
            updateStatus("Đang dẫn đường đến $query")
        } catch (e: Exception) {
            Log.e(TAG, "Unable to open Google Maps", e)
            updateStatus("Không mở được Google Maps")
        }
    }

    private fun openUrlInGoogleMaps(url: String) {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            updateStatus("Liên kết bản đồ không hợp lệ")
            return
        }
        try {
            val mapIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage("com.google.android.apps.maps")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(mapIntent)
            updateStatus("Đang mở liên kết trong Google Maps")
        } catch (e: Exception) {
            Log.e(TAG, "Unable to open shared URL in Google Maps", e)
            updateStatus("Không mở được liên kết trong Google Maps")
        }
    }

    private fun openMapsHome() {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")
                ?: error("Google Maps không được cài đặt")
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(launchIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to bring Google Maps to the foreground", e)
            updateStatus("Không mở được Google Maps")
        }
    }

    private fun sendActionClick(key: String, actionIndex: Int, replyText: String?) {
        val peer = clientSocket ?: return
        if (!peer.isConnected) return
        try {
            val json = JSONObject()
                .put("type", "ACTION_CLICK")
                .put("key", key)
                .put("actionIndex", actionIndex)
                .put("message", replyText ?: "")
                .toString()
            synchronized(writeLock) {
                peer.outputStream.write((json + "\n").toByteArray(StandardCharsets.UTF_8))
                peer.outputStream.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not send quick reply", e)
            updateStatus("Không gửi được trả lời Bluetooth")
        }
    }

    private fun updateStatus(text: String) {
        status = text
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "MapsBridge Note8", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openSetup = PendingIntent.getActivity(
            this, 0, Intent(this, HeadUnitSetupActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("MapsBridge đang chờ chia sẻ")
            .setContentText(text)
            .setContentIntent(openSetup)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) { }
        try { clientSocket?.close() } catch (_: Exception) { }
        serverThread?.interrupt()
        if (::overlayManager.isInitialized) overlayManager.dismiss()
        detachKeepAwakeOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
