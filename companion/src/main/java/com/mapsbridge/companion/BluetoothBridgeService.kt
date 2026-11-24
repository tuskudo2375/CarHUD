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
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
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
    private val reconnectImmediately = AtomicBoolean(false)
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
        synchronized(writeLock) {
            try {
                val writer = OutputStreamWriter(current.outputStream, StandardCharsets.UTF_8)
                writer.write(json)
                writer.write("\n")
                writer.flush()
            } catch (e: IOException) {
                Log.w(TAG, "Bluetooth write failed; closing socket to restart connection", e)
                reconnectImmediately.set(true)
                try { current.close() } catch (_: Throwable) { }
                if (socket === current) socket = null
            } catch (e: Throwable) {
                Log.e(TAG, "Unexpected Bluetooth write failure", e)
                reconnectImmediately.set(true)
                try { current.close() } catch (_: Throwable) { }
                if (socket === current) socket = null
            }
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
                val connectedSocket = adapter.getRemoteDevice(mac).createRfcommSocketToServiceRecord(SERVICE_UUID)
                activeSocket = connectedSocket
                socket = connectedSocket
                updateStatus("Đang kết nối Note8…")
                connectedSocket.connect()
                updateStatus("Đã kết nối Note8")
                sendPendingNavigation()
                val reader = BufferedReader(InputStreamReader(connectedSocket.inputStream, StandardCharsets.UTF_8))
                val heartbeatRunning = AtomicBoolean(true)
                val heartbeat = Thread({
                    while (running.get() && heartbeatRunning.get() && connectedSocket.isConnected) {
                        try {
                            Thread.sleep(5_000)
                        } catch (_: InterruptedException) {
                            break
                        }
                        if (running.get() && heartbeatRunning.get() && connectedSocket.isConnected) {
                            sendJson("{\"type\":\"PING\"}")
                        }
                    }
                }, "MapsBridge-Heartbeat").also { it.start() }
                try {
                    while (running.get() && connectedSocket.isConnected) {
                        val line = reader.readLine() ?: break
                        try {
                            val message = JSONObject(line)
                            if (message.optString("type") == "ACTION_CLICK") {
                                CompanionNotifService.instance?.executeAction(
                                    notifKey = message.optString("key"),
                                    actionIndex = message.optInt("actionIndex", -1)
                                )
                            }
                        } catch (e: Throwable) {
                            Log.w(TAG, "Ignoring invalid Bluetooth message", e)
                        }
                    }
                } finally {
                    heartbeatRunning.set(false)
                    heartbeat.interrupt()
                }
            } catch (e: Throwable) {
                if (running.get()) {
                    Log.i(TAG, "Bluetooth disconnected; retrying", e)
                    updateStatus("Mất kết nối, đang thử lại…")
                }
            } finally {
                try { activeSocket?.close() } catch (_: Throwable) { }
                if (socket === activeSocket) socket = null
            }
            if (!reconnectImmediately.getAndSet(false)) sleepBeforeRetry()
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
