package com.carhud.display

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class BluetoothServerManager(private val onStatus: (String) -> Unit = {}) {
    companion object { val SERVICE_UUID: UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66") }
    @Volatile var onDataReceived: ((String) -> Unit)? = null
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var clientSocket: BluetoothSocket? = null
    private val running = AtomicBoolean(false)
    private val writeLock = Any()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread({
            while (running.get()) {
                try {
                    val adapter = BluetoothAdapter.getDefaultAdapter() ?: throw IllegalStateException("Bluetooth is unavailable")
                    onStatus("Waiting for phone connection")
                    val server = adapter.listenUsingRfcommWithServiceRecord("CarHUD", SERVICE_UUID)
                    serverSocket = server
                    val client = server.accept()
                    clientSocket = client
                    try { server.close() } catch (_: Exception) { }
                    serverSocket = null
                    onStatus("Connected to ${client.remoteDevice.name ?: "phone"}")
                    val reader = BufferedReader(InputStreamReader(client.inputStream, StandardCharsets.UTF_8))
                    while (running.get()) {
                        val line = reader.readLine() ?: break
                        onDataReceived?.invoke(line)
                    }
                } catch (e: Exception) {
                    if (running.get()) onStatus("Bluetooth: ${e.localizedMessage ?: "reconnecting"}")
                } finally {
                    try { clientSocket?.close() } catch (_: Exception) { }
                    clientSocket = null
                    try { serverSocket?.close() } catch (_: Exception) { }
                    serverSocket = null
                }
            }
        }, "CarHUD-Bluetooth-Server").start()
    }

    fun sendQuickReply(notifKey: String, message: String) {
        val client = clientSocket ?: return
        try {
            val json = JSONObject().put("type", "REPLY").put("key", notifKey).put("message", message).toString()
            synchronized(writeLock) { client.outputStream.write((json + "\n").toByteArray(StandardCharsets.UTF_8)); client.outputStream.flush() }
        } catch (e: Exception) { onStatus("Reply could not be sent: ${e.localizedMessage ?: "connection lost"}") }
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) { }
        try { clientSocket?.close() } catch (_: Exception) { }
    }
}
