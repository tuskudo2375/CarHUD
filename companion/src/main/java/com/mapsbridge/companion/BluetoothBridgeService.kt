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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class BluetoothBridgeService : Service() {
    companion object {
        private const val TAG = "MapsBridgeBt"
        private const val CHANNEL_ID = "mapsbridge_companion"
        private const val NOTIFICATION_ID = 7101
        private const val PREFS = "mapsbridge_prefs"
        private const val PREF_NOTE8_MAC = "note8_mac"
        private const val ACTION_SUBMIT_NAV = "com.mapsbridge.companion.SUBMIT_NAV"
        private const val EXTRA_RAW_TEXT = "raw_text"
        val SERVICE_UUID: UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66")

        @Volatile var instance: BluetoothBridgeService? = null
            private set

        fun submitNavigationShare(context: Context, rawText: String) {
            val serviceIntent = Intent(context, BluetoothBridgeService::class.java)
                .setAction(ACTION_SUBMIT_NAV)
                .putExtra(EXTRA_RAW_TEXT, rawText)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ContextCompat.startForegroundService(context, serviceIntent)
            else context.startService(serviceIntent)
        }
    }

    private val running = AtomicBoolean(false)
    private val reconnectImmediately = AtomicBoolean(false)
    private val writeLock = Any()
    private val socketMonitor = Object()
    private val pendingAcks = ConcurrentHashMap<String, CountDownLatch>()
    private val pendingReceipts = ConcurrentHashMap<String, CountDownLatch>()
    private val shareSequence = AtomicLong(0L)
    private val navigationExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "MapsBridge-Resolve-And-Send")
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var latestCmdId: String? = null
    @Volatile private var activeAckLatch: CountDownLatch? = null
    @Volatile private var connectionStatus = "Đang chờ kết nối Thiết bị Phụ"

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
        if (intent?.action == ACTION_SUBMIT_NAV) {
            intent.getStringExtra(EXTRA_RAW_TEXT)?.takeIf(String::isNotBlank)?.let(::submitNavigationShare)
        }
        return START_STICKY
    }

    fun submitNavigationShare(rawText: String) {
        activeAckLatch?.countDown()
        val requestSequence = shareSequence.incrementAndGet()
        synchronized(socketMonitor) { socketMonitor.notifyAll() }
        navigationExecutor.execute {
            try {
                val resolved = UrlResolver.extractDestination(rawText)
                val cmdId = System.currentTimeMillis().toString()
                if (requestSequence != shareSequence.get()) return@execute
                latestCmdId = cmdId
                val navJson = JSONObject()
                    .put("type", "NAV_SHARE")
                    .put("id", cmdId)
                    .put("query", resolved.query.orEmpty())
                    .put("fallbackUrl", resolved.fallbackUrl.orEmpty())
                    .toString()

                var executed = false
                var attempts = 0
                while (attempts < 20 && !executed && latestCmdId == cmdId && requestSequence == shareSequence.get()) {
                    attempts++
                    val latch = CountDownLatch(1)
                    val receiptLatch = CountDownLatch(1)
                    activeAckLatch = latch
                    pendingAcks[cmdId] = latch
                    pendingReceipts[cmdId] = receiptLatch
                    val connected = awaitConnectedSocket(1_000L, requestSequence)
                    if (requestSequence != shareSequence.get() || latestCmdId != cmdId) {
                        pendingAcks.remove(cmdId, latch)
                        pendingReceipts.remove(cmdId, receiptLatch)
                        if (activeAckLatch === latch) activeAckLatch = null
                        break
                    }
                    if (!connected) forceReconnect()
                    if (connected && sendJson(navJson)) {
                        executed = latch.await(1_000L, TimeUnit.MILLISECONDS)
                        if (!executed && receiptLatch.await(0L, TimeUnit.MILLISECONDS)) {
                            executed = latch.await(1_000L, TimeUnit.MILLISECONDS)
                        }
                    }
                    pendingAcks.remove(cmdId, latch)
                    pendingReceipts.remove(cmdId, receiptLatch)
                    if (activeAckLatch === latch) activeAckLatch = null
                    if (!executed && latestCmdId == cmdId && requestSequence == shareSequence.get()) {
                        forceReconnect()
                        Thread.sleep(200L)
                    }
                }
                if (executed && latestCmdId == cmdId && requestSequence == shareSequence.get()) {
                    showToast("Máy Phụ đã mở dẫn đường!")
                } else if (!executed && latestCmdId == cmdId && requestSequence == shareSequence.get()) {
                    showToast("Không kết nối được Máy Phụ!")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Could not send navigation share", e)
                showToast("Không kết nối được Máy Phụ!")
            }
        }
    }

    fun sendJson(json: String): Boolean {
        synchronized(writeLock) {
            val current = socket
            if (current == null || !current.isConnected) return false
            return try {
                current.outputStream.write((json + "\n").toByteArray(StandardCharsets.UTF_8))
                current.outputStream.flush()
                true
            } catch (e: IOException) {
                Log.w(TAG, "Bluetooth write failed; closing socket to restart connection", e)
                closeSocketLocked(current)
                false
            } catch (e: Throwable) {
                Log.e(TAG, "Unexpected Bluetooth write failure", e)
                closeSocketLocked(current)
                false
            }
        }
    }

    private fun closeSocketLocked(current: BluetoothSocket) {
        reconnectImmediately.set(true)
        try { current.close() } catch (_: Throwable) { }
        if (socket === current) socket = null
        synchronized(socketMonitor) { socketMonitor.notifyAll() }
    }

    private fun awaitConnectedSocket(timeoutMs: Long, requestSequence: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(socketMonitor) {
            while (running.get() && requestSequence == shareSequence.get()) {
                val current = socket
                if (current != null && runCatching { current.isConnected }.getOrDefault(false)) return true
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return false
                try { socketMonitor.wait(left) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return false }
            }
        }
        return false
    }

    fun forceReconnect() {
        reconnectImmediately.set(true)
        synchronized(writeLock) {
            socket?.let(::closeSocketLocked)
        }
        worker?.interrupt()
        synchronized(socketMonitor) { socketMonitor.notifyAll() }
    }

    private fun connectionLoop() {
        while (running.get()) {
            val mac = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_NOTE8_MAC, null)
            if (mac.isNullOrBlank()) {
                updateStatus("Chưa chọn Thiết bị Phụ trong MapsBridge")
                sleepBeforeRetry()
                continue
            }
            var activeSocket: BluetoothSocket? = null
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter() ?: error("Bluetooth không khả dụng")
                val connectedSocket = adapter.getRemoteDevice(mac).createInsecureRfcommSocketToServiceRecord(SERVICE_UUID)
                activeSocket = connectedSocket
                updateStatus("Đang kết nối Thiết bị Phụ…")
                connectedSocket.connect()
                socket = connectedSocket
                synchronized(socketMonitor) { socketMonitor.notifyAll() }
                updateStatus("Đã kết nối Thiết bị Phụ")
                val reader = BufferedReader(InputStreamReader(connectedSocket.inputStream, StandardCharsets.UTF_8))
                val heartbeatRunning = AtomicBoolean(true)
                val heartbeat = Thread({
                    while (running.get() && heartbeatRunning.get() && connectedSocket.isConnected) {
                        try { Thread.sleep(1_500L) } catch (_: InterruptedException) { break }
                        if (running.get() && heartbeatRunning.get() && connectedSocket.isConnected) sendJson("{\"type\":\"PING\"}")
                    }
                }, "MapsBridge-Heartbeat").also { it.start() }
                try {
                    while (running.get() && connectedSocket.isConnected) {
                        val line = reader.readLine() ?: break
                        try {
                            val message = JSONObject(line)
                            when (message.optString("type")) {
                                "ACK_RECEIVED" -> pendingReceipts[message.optString("id")]?.countDown()
                                "ACK_EXECUTED" -> pendingAcks[message.optString("id")]?.countDown()
                                "ACTION_CLICK" -> CompanionNotifService.instance?.executeAction(
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
                if (running.get() && e !is InterruptedException) {
                    Log.i(TAG, "Bluetooth disconnected; retrying", e)
                    updateStatus("Mất kết nối, đang thử lại…")
                }
            } finally {
                synchronized(writeLock) {
                    try { activeSocket?.close() } catch (_: Throwable) { }
                    if (socket === activeSocket) socket = null
                }
                synchronized(socketMonitor) { socketMonitor.notifyAll() }
            }
            if (!reconnectImmediately.getAndSet(false)) sleepBeforeRetry()
        }
    }

    private fun sleepBeforeRetry() {
        try { Thread.sleep(500L) } catch (_: InterruptedException) { Thread.interrupted() }
    }

    private fun showToast(text: String) {
        mainHandler.post { Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show() }
    }

    private fun updateStatus(status: String) {
        connectionStatus = status
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "MapsBridge Chính", NotificationManager.IMPORTANCE_LOW)
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
            .setContentTitle("MapsBridge Chính đang chạy")
            .setContentText(text)
            .setContentIntent(openSetup)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        running.set(false)
        synchronized(writeLock) { try { socket?.close() } catch (_: Exception) { }; socket = null }
        synchronized(socketMonitor) { socketMonitor.notifyAll() }
        worker?.interrupt()
        navigationExecutor.shutdownNow()
        pendingAcks.values.forEach { it.countDown() }
        pendingAcks.clear()
        pendingReceipts.values.forEach { it.countDown() }
        pendingReceipts.clear()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
