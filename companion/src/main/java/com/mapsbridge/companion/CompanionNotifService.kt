package com.mapsbridge.companion

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class CompanionNotifService : NotificationListenerService() {
    companion object {
        @Volatile var instance: CompanionNotifService? = null
            private set
        val actionMap = ConcurrentHashMap<String, List<Notification.Action>>()
    }

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

            val appInfo = try {
                packageManager.getApplicationInfo(sbn.packageName, 0)
            } catch (_: Throwable) {
                return
            }
            if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) return
            val appName = packageManager.getApplicationLabel(appInfo).toString()

            val extras = sbn.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            if (title.isBlank() && text.isBlank()) return

            val actions = sbn.notification.actions?.toList()?.take(3) ?: emptyList()
            if (actions.isEmpty()) actionMap.remove(sbn.key) else actionMap[sbn.key] = actions
            val actionArray = JSONArray()
            actions.forEachIndexed { index, action ->
                actionArray.put(JSONObject()
                    .put("index", index)
                    .put("title", action.title?.toString().orEmpty())
                    .put("isReply", !action.remoteInputs.isNullOrEmpty()))
            }
            val json = JSONObject()
                .put("type", "NOTIF")
                .put("key", sbn.key)
                .put("appName", appName)
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

    fun executeAction(notifKey: String, actionIndex: Int, replyMessage: String?) {
        val action = actionMap[notifKey]?.getOrNull(actionIndex) ?: return
        try {
            val inputs = action.remoteInputs.orEmpty()
            if (inputs.isNotEmpty() && !replyMessage.isNullOrEmpty()) {
                val input = Intent()
                RemoteInput.addResultsToIntent(inputs, input, android.os.Bundle().apply {
                    putCharSequence(inputs.first().resultKey, replyMessage)
                })
                action.actionIntent.send(this, 0, input)
            } else {
                action.actionIntent.send()
            }
        } catch (_: Throwable) {
            // The notification may have disappeared before the action was selected.
        } finally {
            actionMap.remove(notifKey)
        }
    }
}
