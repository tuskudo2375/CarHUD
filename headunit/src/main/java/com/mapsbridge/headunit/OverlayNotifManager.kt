package com.mapsbridge.headunit

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray

class OverlayNotifManager(
    context: Context,
    private val onActionClick: (String, Int) -> Unit
) {
    private val appContext = context.applicationContext
    private val themedContext = ContextThemeWrapper(
        appContext,
        androidx.appcompat.R.style.Theme_AppCompat_NoActionBar
    )
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var dismissAction: Runnable? = null
    private var currentKey: String? = null

    @SuppressLint("InflateParams")
    fun show(key: String, appName: String, iconBase64: String, title: String, text: String, actions: JSONArray) {
        handler.post {
            try {
                if (!Settings.canDrawOverlays(appContext)) return@post
                dismissOnMain()
                val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_notification, null)
                view.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#EB1A1A1A"))
                    cornerRadius = dpToPx(14).toFloat()
                }
                currentKey = key
                view.findViewById<TextView>(R.id.tvAppName).text = appName.ifBlank { "Thông báo" }
                view.findViewById<TextView>(R.id.tvNotifSender).text = title.ifBlank { "Thông báo" }
                view.findViewById<TextView>(R.id.tvNotifMessage).text = text
                view.findViewById<Button>(R.id.btnClose).setOnClickListener { dismiss() }

                val iconView = view.findViewById<ImageView>(R.id.ivAppIcon)
                val iconBitmap = decodeIcon(iconBase64)
                if (iconBitmap == null) {
                    iconView.visibility = View.GONE
                } else {
                    iconView.setImageBitmap(iconBitmap)
                    iconView.visibility = View.VISIBLE
                }

                val actionContainer = view.findViewById<LinearLayout>(R.id.layoutActions)
                actionContainer.removeAllViews()
                for (position in 0 until actions.length()) {
                    val action = actions.optJSONObject(position) ?: continue
                    val actionIndex = action.optInt("index", position)
                    val actionTitle = action.optString("title").trim()
                    if (actionTitle.isNotEmpty()) {
                        actionContainer.addView(createActionButton(actionTitle) {
                            performAction(key, actionIndex)
                        })
                    }
                }
                actionContainer.visibility = if (actions.length() > 0) View.VISIBLE else View.GONE

                val params = WindowManager.LayoutParams(
                    dpToPx(320), WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = dpToPx(12)
                }
                windowManager.addView(view, params)
                overlay = view
                dismissAction = Runnable { dismissOnMain() }.also { handler.postDelayed(it, 8_000L) }
            } catch (_: Throwable) {
                overlay = null
                currentKey = null
            }
        }
    }

    private fun decodeIcon(iconBase64: String) = try {
        if (iconBase64.isBlank()) null
        else {
            val bytes = Base64.decode(iconBase64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    } catch (_: Throwable) {
        null
    }

    private fun createActionButton(text: String, onClick: () -> Unit): Button = Button(themedContext).apply {
        this.text = text
        textSize = 12f
        isAllCaps = false
        minHeight = 0
        minWidth = 0
        setPadding(dpToPx(12), 0, dpToPx(12), 0)
        setTextColor(Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(0xFF165A6B.toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dpToPx(32)
        ).apply { marginEnd = dpToPx(6) }
        setOnClickListener { onClick() }
    }

    private fun performAction(key: String, actionIndex: Int) {
        if (currentKey != key) return
        try { onActionClick(key, actionIndex) } catch (_: Throwable) { }
        dismissOnMain()
    }

    fun dismiss() {
        handler.post { dismissOnMain() }
    }

    private fun dismissOnMain() {
        dismissAction?.let(handler::removeCallbacks)
        dismissAction = null
        overlay?.let { view ->
            try {
                if (view.isAttachedToWindow) windowManager.removeViewImmediate(view)
            } catch (_: Exception) { }
        }
        overlay = null
        currentKey = null
    }

    private fun dpToPx(value: Int): Int = (value * appContext.resources.displayMetrics.density).toInt()
}
