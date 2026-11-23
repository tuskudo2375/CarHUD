package com.mapsbridge.headunit

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class OverlayNotifManager(
    context: Context,
    private val onReply: (String, String) -> Unit
) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var dismissAction: Runnable? = null
    private var currentKey: String? = null

    @SuppressLint("InflateParams")
    fun show(key: String, sender: String, message: String, canReply: Boolean) {
        handler.post {
            if (!Settings.canDrawOverlays(appContext)) return@post
            dismissOnMain()
            val view = LayoutInflater.from(appContext).inflate(R.layout.overlay_notification, null)
            currentKey = key
            view.findViewById<TextView>(R.id.tvOverlaySender).text = sender.ifBlank { "Thông báo" }
            view.findViewById<TextView>(R.id.tvOverlayMessage).text = message
            view.findViewById<LinearLayout>(R.id.layoutOverlayReplies).visibility = if (canReply) View.VISIBLE else View.GONE
            view.findViewById<Button>(R.id.btnOverlayClose).setOnClickListener { dismiss() }
            view.findViewById<Button>(R.id.btnOverlayReply1).setOnClickListener { reply("Đang lái xe, lát gọi lại!") }
            view.findViewById<Button>(R.id.btnOverlayReply2).setOnClickListener { reply("OK, đang qua liền!") }

            val params = WindowManager.LayoutParams(
                dp(360), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = dp(16)
                y = dp(36)
            }
            try {
                windowManager.addView(view, params)
                overlay = view
                dismissAction = Runnable { dismissOnMain() }.also { handler.postDelayed(it, 10_000) }
            } catch (_: Exception) {
                overlay = null
                currentKey = null
            }
        }
    }

    private fun reply(text: String) {
        val key = currentKey ?: return
        onReply(key, text)
        dismiss()
    }

    fun dismiss() {
        handler.post { dismissOnMain() }
    }

    private fun dismissOnMain() {
        dismissAction?.let(handler::removeCallbacks)
        dismissAction = null
        overlay?.let { view -> runCatching { windowManager.removeView(view) } }
        overlay = null
        currentKey = null
    }

    private fun dp(value: Int): Int = (value * appContext.resources.displayMetrics.density).toInt()
}
