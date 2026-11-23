package com.mapsbridge.headunit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        overlayManager = OverlayNotifManager(this) { key, message -> sendQuickReply(key, message) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(status))
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
                val payload = message.optString("payload")
                Thread({
                    val resolved = UrlResolver.extractDestination(payload)
                    val query = resolved.query
                    val fallbackUrl = resolved.fallbackUrl
                    when {
                        !query.isNullOrBlank() -> launchGoogleMapsNavigation(query)
                        !fallbackUrl.isNullOrBlank() -> openUrlInGoogleMaps(fallbackUrl)
                        else -> updateStatus("Không nhận diện được địa điểm")
                    }
                }, "MapsBridge-Resolve-URL").start()
            }
            "NOTIF" -> overlayManager.show(
                key = message.optString("key"),
                sender = message.optString("title"),
                message = message.optString("text"),
                canReply = message.optBoolean("canReply")
            )
        }
    }

    private fun launchGoogleMapsNavigation(query: String) {
        if (query.trimStart().startsWith("http://", ignoreCase = true) ||
            query.trimStart().startsWith("https://", ignoreCase = true)) {
            openUrlInGoogleMaps(query)
            return
        }
        try {
            val uri = Uri.parse("google.navigation:q=${Uri.encode(query)}")
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

    private fun sendQuickReply(key: String, text: String) {
        val peer = clientSocket ?: return
        if (!peer.isConnected) return
        try {
            val json = JSONObject().put("type", "REPLY").put("key", key).put("message", text).toString()
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
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
