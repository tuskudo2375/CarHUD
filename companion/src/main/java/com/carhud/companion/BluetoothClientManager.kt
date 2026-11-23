package com.carhud.companion

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class BluetoothClientManager(private val device: BluetoothDevice, private val onStatus: (String) -> Unit = {}) {
    companion object { val SERVICE_UUID: UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66") }
    @Volatile var onReplyReceived: ((String, String) -> Unit)? = null
    @Volatile private var socket: BluetoothSocket? = null
    private val running = AtomicBoolean(false)
    private val writeLock = Any()

    fun connect() {
        if (!running.compareAndSet(false, true)) return
        Thread({
            try {
                onStatus("Connecting to ${device.name ?: "Bluetooth device"}…")
                val s = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                socket = s
                s.connect()
                onStatus("Connected to ${device.name ?: "Bluetooth device"}")
                val reader = BufferedReader(InputStreamReader(s.inputStream, StandardCharsets.UTF_8))
                while (running.get()) {
                    val line = reader.readLine() ?: break
                    try {
                        val json = JSONObject(line)
                        if (json.optString("type") == "REPLY") onReplyReceived?.invoke(json.optString("key"), json.optString("message"))
                    } catch (_: Exception) { }
                }
            } catch (e: Exception) {
                onStatus("Bluetooth connection ended: ${e.localizedMessage ?: "unavailable"}")
            } finally {
                running.set(false)
                try { socket?.close() } catch (_: Exception) { }
                socket = null
            }
        }, "CarHUD-Bluetooth-Client").start()
    }

    fun sendJson(jsonString: String) {
        val s = socket ?: return
        try { synchronized(writeLock) { s.outputStream.write((jsonString + "\n").toByteArray(StandardCharsets.UTF_8)); s.outputStream.flush() } }
        catch (e: Exception) { onStatus("Bluetooth send failed: ${e.localizedMessage ?: "connection lost"}") }
    }

    fun close() {
        running.set(false)
        try { socket?.close() } catch (_: Exception) { }
    }
}
