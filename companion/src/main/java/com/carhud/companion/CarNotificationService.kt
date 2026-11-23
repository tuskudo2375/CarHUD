package com.carhud.companion

import android.app.Notification
import android.app.Notification.Action
import android.app.RemoteInput
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class CarNotificationService : NotificationListenerService() {
    companion object {
        @Volatile var instance: CarNotificationService? = null
        @Volatile var onJsonReady: ((String) -> Unit)? = null
        val replyActions = ConcurrentHashMap<String, Action>()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        if (sbn.packageName == "com.google.android.apps.maps") {
            onJsonReady?.invoke(JSONObject().put("type", "NAV").put("title", title).put("text", text).toString())
            return
        }

        var canReply = false
        sbn.notification.actions.orEmpty().firstOrNull { action ->
            !action.remoteInputs.isNullOrEmpty()
        }?.let { action ->
            replyActions[sbn.key] = action
            canReply = true
        }
        onJsonReady?.invoke(JSONObject().put("type", "NOTIF").put("key", sbn.key)
            .put("app", sbn.packageName).put("title", title).put("text", text)
            .put("canReply", canReply).toString())
    }

    fun executeQuickReply(notifKey: String, replyMessage: String) {
        val action = replyActions[notifKey] ?: return
        val inputs = action.remoteInputs.orEmpty()
        if (inputs.isEmpty()) return
        val results = android.content.Intent()
        val remoteInput = inputs.first()
        RemoteInput.addResultsToIntent(inputs, results, android.os.Bundle().apply {
            putCharSequence(remoteInput.resultKey, replyMessage)
        })
        try {
            action.actionIntent.send(this, 0, results)
            replyActions.remove(notifKey, action)
        } catch (_: android.app.PendingIntent.CanceledException) {
            replyActions.remove(notifKey, action)
        }
    }
}
