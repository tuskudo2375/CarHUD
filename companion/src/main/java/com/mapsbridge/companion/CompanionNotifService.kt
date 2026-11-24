package com.mapsbridge.companion

import android.app.Notification
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

class CompanionNotifService : NotificationListenerService() {
    companion object {
        @Volatile var instance: CompanionNotifService? = null
            private set
        val actionMap = ConcurrentHashMap<String, List<Notification.Action>>()
    }

    private val iconCache = ConcurrentHashMap<String, String>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            if (sbn.packageName == packageName ||
                sbn.packageName == "com.google.android.apps.maps" ||
                sbn.isOngoing ||
                (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

            val appInfo = packageManager.getApplicationInfo(sbn.packageName, 0)
            if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) return
            val appName = packageManager.getApplicationLabel(appInfo).toString()

            val extras = sbn.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            if (title.isBlank() && text.isBlank()) return

            val actions = sbn.notification.actions
                ?.filter { action ->
                    action.actionIntent != null &&
                        action.remoteInputs.isNullOrEmpty() &&
                        !action.title.isNullOrBlank()
                }
                ?.take(3)
                ?: emptyList()
            actionMap[sbn.key] = actions
            val actionArray = JSONArray()
            actions.forEachIndexed { index, action ->
                actionArray.put(JSONObject()
                    .put("index", index)
                    .put("title", action.title.toString()))
            }
            val json = JSONObject()
                .put("type", "NOTIF")
                .put("key", sbn.key)
                .put("appName", appName)
                .put("icon", getAppIconBase64(sbn.packageName))
                .put("title", title)
                .put("text", text)
                .put("actions", actionArray)
                .toString()
            BluetoothBridgeService.instance?.sendJson(json)
        } catch (_: Throwable) {
            // Ignore malformed or transient notification data instead of crashing the listener.
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        try {
            actionMap.remove(sbn.key)
            super.onNotificationRemoved(sbn)
        } catch (_: Throwable) { }
    }

    fun executeAction(notifKey: String, actionIndex: Int) {
        val action = actionMap[notifKey]?.getOrNull(actionIndex) ?: return
        try {
            action.actionIntent.send()
        } catch (_: Throwable) {
            // The notification may have disappeared before the action was selected.
        } finally {
            actionMap.remove(notifKey)
        }
    }

    private fun getAppIconBase64(pkg: String): String {
        iconCache[pkg]?.let { return it }
        var bitmap: Bitmap? = null
        return try {
            val drawable = packageManager.getApplicationIcon(pkg)
            val renderedBitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
            bitmap = renderedBitmap
            val canvas = Canvas(renderedBitmap)
            drawable.setBounds(0, 0, 48, 48)
            drawable.draw(canvas)
            val bytes = ByteArrayOutputStream().use { output ->
                renderedBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
            Base64.encodeToString(bytes, Base64.NO_WRAP).also { iconCache[pkg] = it }
        } catch (_: Throwable) {
            ""
        } finally {
            bitmap?.recycle()
        }
    }
}
