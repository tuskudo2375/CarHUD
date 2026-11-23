package com.mapsbridge.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
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

class BluetoothBridgeService : Service() {
    companion object {
        private const val TAG = "MapsBridgeBt"
        private const val CHANNEL_ID = "mapsbridge_companion"
        private const val NOTIFICATION_ID = 7101
        private const val PREFS = "mapsbridge_prefs"
        private const val PREF_NOTE8_MAC = "note8_mac"
        val SERVICE_UUID: UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66")

        @Volatile var instance: BluetoothBridgeService? = null
            private set
        private val pendingLock = Any()
        @Volatile private var pendingNavShare: String? = null

        fun sendOrQueue(context: Context, json: String) {
            if (runCatching { JSONObject(json).optString("type") == "NAV_SHARE" }.getOrDefault(false)) {
                synchronized(pendingLock) { pendingNavShare = json }
            }
            val serviceIntent = Intent(context, BluetoothBridgeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(serviceIntent)
            else context.startService(serviceIntent)
            instance?.sendPendingNavigation()
        }
    }

    private val running = AtomicBoolean(false)
    private val writeLock = Any()
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var connectionStatus = "Đang chờ kết nối Note8"

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(connectionStatus))
        if (running.compareAndSet(false, true)) {
            worker = Thread(::connectionLoop, "MapsBridge-RFCOMM-Client").also { it.start() }
        }
        return START_STICKY
    }

    fun sendJson(json: String) {
        val current = socket
        if (current == null || !current.isConnected) {
            if (runCatching { JSONObject(json).optString("type") == "NAV_SHARE" }.getOrDefault(false)) {
                synchronized(pendingLock) { pendingNavShare = json }
            }
            return
        }
        try {
            synchronized(writeLock) {
                current.outputStream.write((json + "\n").toByteArray(StandardCharsets.UTF_8))
                current.outputStream.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Send failed", e)
        }
    }

    private fun sendPendingNavigation() {
        val queued = synchronized(pendingLock) {
            val value = pendingNavShare
            pendingNavShare = null
            value
        } ?: return
        sendJson(queued)
    }

    private fun connectionLoop() {
        while (running.get()) {
            val mac = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_NOTE8_MAC, null)
            if (mac.isNullOrBlank()) {
                updateStatus("Chưa chọn Note8 trong MapsBridge")
                sleepBeforeRetry()
                continue
            }
            var activeSocket: BluetoothSocket? = null
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter() ?: error("Bluetooth không khả dụng")
                activeSocket = adapter.getRemoteDevice(mac).createRfcommSocketToServiceRecord(SERVICE_UUID)
                socket = activeSocket
                updateStatus("Đang kết nối Note8…")
                activeSocket.connect()
                updateStatus("Đã kết nối Note8")
                sendPendingNavigation()
                val reader = BufferedReader(InputStreamReader(activeSocket.inputStream, StandardCharsets.UTF_8))
                while (running.get() && activeSocket.isConnected) {
                    val line = reader.readLine() ?: break
                    try {
                        val message = JSONObject(line)
                        if (message.optString("type") == "ACTION_CLICK") {
                            CompanionNotifService.instance?.executeAction(
                                notifKey = message.optString("key"),
                                actionIndex = message.optInt("actionIndex", -1),
                                replyMessage = message.optString("message").takeIf { it.isNotEmpty() }
                            )
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Ignoring invalid Bluetooth message", e)
                    }
                }
            } catch (e: Exception) {
                if (running.get()) {
                    Log.i(TAG, "Bluetooth disconnected; retrying", e)
                    updateStatus("Mất kết nối, đang thử lại…")
                }
            } finally {
                try { activeSocket?.close() } catch (_: Exception) { }
                if (socket === activeSocket) socket = null
            }
            sleepBeforeRetry()
        }
    }

    private fun sleepBeforeRetry() {
        try { Thread.sleep(3_000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    private fun updateStatus(status: String) {
        connectionStatus = status
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "MapsBridge Bluetooth", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openSetup = PendingIntent.getActivity(
            this, 0, Intent(this, SetupActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("MapsBridge đang chạy")
            .setContentText(text)
            .setContentIntent(openSetup)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        running.set(false)
        try { socket?.close() } catch (_: Exception) { }
        worker?.interrupt()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
