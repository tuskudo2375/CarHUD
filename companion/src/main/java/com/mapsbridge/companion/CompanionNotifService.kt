package com.mapsbridge.companion

import android.app.Notification
import android.app.RemoteInput
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class CompanionNotifService : NotificationListenerService() {
    companion object {
        @Volatile var instance: CompanionNotifService? = null
            private set
        val replyActions = ConcurrentHashMap<String, Notification.Action>()
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
        if (sbn.packageName == packageName || sbn.packageName == "com.google.android.apps.maps") return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        val action = sbn.notification.actions.orEmpty().firstOrNull { !it.remoteInputs.isNullOrEmpty() }
        val canReply = action != null
        if (action != null) replyActions[sbn.key] = action
        val json = JSONObject()
            .put("type", "NOTIF")
            .put("key", sbn.key)
            .put("title", title)
            .put("text", text)
            .put("canReply", canReply)
            .toString()
        BluetoothBridgeService.instance?.sendJson(json)
    }

    fun executeQuickReply(key: String, message: String) {
        val action = replyActions[key] ?: return
        val inputs = action.remoteInputs.orEmpty()
        if (inputs.isEmpty()) return
        val input = inputs.first()
        val fillIn = android.content.Intent()
        RemoteInput.addResultsToIntent(inputs, fillIn, android.os.Bundle().apply {
            putCharSequence(input.resultKey, message)
        })
        try {
            action.actionIntent.send(this, 0, fillIn)
            replyActions.remove(key, action)
        } catch (_: android.app.PendingIntent.CanceledException) {
            replyActions.remove(key, action)
        }
    }
}
