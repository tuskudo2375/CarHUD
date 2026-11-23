package com.mapsbridge.headunit

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.content.Context
import android.graphics.Color
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
import org.json.JSONArray

class OverlayNotifManager(
    context: Context,
    private val onActionClick: (String, Int, String?) -> Unit,
    private val onReturnToMaps: () -> Unit
) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var dismissAction: Runnable? = null
    private var currentKey: String? = null

    @SuppressLint("InflateParams")
    fun show(key: String, appName: String, title: String, text: String, actions: JSONArray) {
        handler.post {
            if (!Settings.canDrawOverlays(appContext)) return@post
            dismissOnMain()
            val view = LayoutInflater.from(appContext).inflate(R.layout.overlay_notification, null)
            currentKey = key
            view.findViewById<TextView>(R.id.tvAppName).text = appName.ifBlank { "Thông báo" }
            view.findViewById<TextView>(R.id.tvOverlaySender).text = title.ifBlank { "Thông báo" }
            view.findViewById<TextView>(R.id.tvOverlayMessage).text = text
            val actionContainer = view.findViewById<LinearLayout>(R.id.layoutActions)
            actionContainer.removeAllViews()
            view.findViewById<Button>(R.id.btnOverlayClose).setOnClickListener { dismiss() }
            view.findViewById<Button>(R.id.btnOverlayBackToMaps).setOnClickListener {
                onReturnToMaps()
                dismiss()
            }

            var replyActionsAdded = false
            for (position in 0 until actions.length()) {
                val action = actions.optJSONObject(position) ?: continue
                val actionIndex = action.optInt("index", position)
                if (action.optBoolean("isReply")) {
                    if (!replyActionsAdded) {
                        replyActionsAdded = true
                        actionContainer.addView(createActionButton("Đang lái xe, lát gọi lại!", 0xFF1E88E5.toInt()) {
                            performAction(key, actionIndex, "Đang lái xe, lát gọi lại!")
                        })
                        actionContainer.addView(createActionButton("OK, đang qua liền!", 0xFF43A047.toInt()) {
                            performAction(key, actionIndex, "OK, đang qua liền!")
                        })
                    }
                } else {
                    val actionTitle = action.optString("title").trim()
                    if (actionTitle.isNotEmpty()) {
                        actionContainer.addView(createActionButton(actionTitle, 0xFF165A6B.toInt()) {
                            performAction(key, actionIndex, null)
                        })
                    }
                }
            }
            val params = WindowManager.LayoutParams(
                dp(360), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
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

    private fun performAction(key: String, actionIndex: Int, replyText: String?) {
        if (currentKey != key) return
        onActionClick(key, actionIndex, replyText)
        dismiss()
    }

    private fun createActionButton(text: String, backgroundColor: Int, onClick: () -> Unit): Button =
        Button(appContext).apply {
            this.text = text
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(backgroundColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50)
            ).apply { topMargin = dp(6) }
            setOnClickListener { onClick() }
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
