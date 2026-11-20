package com.gkdbrain.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 悬浮「快照」按钮：点击立即抓快照，长按（按住 500ms 不放）立即关闭，可拖动。
 * 位置会持久化，下次开启仍在原位置出现。
 * 用 TYPE_APPLICATION_OVERLAY（需悬浮窗权限 SYSTEM_ALERT_WINDOW）。
 * 抓快照前会彻底移除按钮窗口，抓完再加回，避免按钮被 GKD 快照捕获。
 */
object SnapshotButtonManager {

    private const val PREFS = "gkd_settings"
    private const val KEY_X = "snapshot_btn_x"
    private const val KEY_Y = "snapshot_btn_y"
    private const val KEY_ENABLED = "snapshot_button_enabled"
    private const val LONG_PRESS_MS = 500L

    private var buttonView: TextView? = null
    private var wm: WindowManager? = null
    private var appContext: Context? = null

    private val handler = Handler(Looper.getMainLooper())
    private var startLpX = 0
    private var startLpY = 0
    private var startRawX = 0f
    private var startRawY = 0f
    private var isDragging = false
    private var longPressed = false
    private var touchSlop = 0

    private val longPressRunnable = Runnable {
        longPressed = true
        val ctx = appContext
        hide()
        ctx?.let { Toast.makeText(it, "快照按钮已关闭", Toast.LENGTH_SHORT).show() }
    }

    private val _isShowing = MutableStateFlow(false)
    val isShowingFlow: StateFlow<Boolean> = _isShowing.asStateFlow()

    val isShowing: Boolean get() = buttonView != null

    fun hasPermission(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** 悬浮球开关是否开启（持久化，进程重启后据此恢复） */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun show(context: Context) {
        if (isShowing) return
        if (!hasPermission(context)) return
        val app = context.applicationContext
        val manager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        touchSlop = ViewConfiguration.get(app).scaledTouchSlop

        val density = app.resources.displayMetrics.density
        val size = (56 * density).toInt()
        val margin = (24 * density).toInt()
        val screenW = app.resources.displayMetrics.widthPixels
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedX = prefs.getInt(KEY_X, -1)
        val savedY = prefs.getInt(KEY_Y, -1)

        val btn = TextView(app).apply {
            text = "快照"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E91E63"))
            }
        }
        val lp = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            format = PixelFormat.TRANSLUCENT
            width = size
            height = size
            gravity = Gravity.TOP or Gravity.START
            x = if (savedX >= 0) savedX else screenW - size - margin
            y = if (savedY >= 0) savedY else (300 * density).toInt()
        }
        btn.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val params = view.layoutParams as WindowManager.LayoutParams
                    startLpX = params.x
                    startLpY = params.y
                    startRawX = event.rawX
                    startRawY = event.rawY
                    isDragging = false
                    longPressed = false
                    handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        isDragging = true
                        handler.removeCallbacks(longPressRunnable)
                    }
                    if (isDragging) {
                        val params = view.layoutParams as WindowManager.LayoutParams
                        params.x = startLpX + dx.toInt()
                        params.y = startLpY + dy.toInt()
                        manager.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (isDragging) {
                        savePosition()
                    } else if (!longPressed) {
                        CapturePipeline.prepareFromButton()
                    }
                    true
                }
                else -> false
            }
        }
        manager.addView(btn, lp)
        buttonView = btn
        wm = manager
        appContext = app
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, true).apply()
        _isShowing.value = true
        AppLog.i("悬浮快照按钮已显示")
        // 开启悬浮球时顺带静默开启无障碍（与磁贴一致）
        if (GkdBrainAccessibilityService.instance == null &&
            ShizukuHelper.hasWriteSecureSettings(app)
        ) {
            thread {
                val ok = ShizukuHelper.restartAccessibility(app)
                AppLog.i(if (ok) "悬浮球开启：无障碍已静默重开" else "悬浮球开启：无障碍重开失败")
            }
        }
    }

    fun hide() {
        if (buttonView == null) return
        savePosition()
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(KEY_ENABLED, false)?.apply()
        buttonView?.let { wm?.removeView(it) }
        buttonView = null
        wm = null
        appContext = null
        _isShowing.value = false
        AppLog.i("悬浮快照按钮已关闭")
    }

    /** 抓快照前彻底移除按钮窗口（避免被捕获），抓完后加回 */
    fun setVisible(visible: Boolean) {
        val view = buttonView ?: return
        val manager = wm ?: return
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        if (visible) {
            if (view.parent == null) manager.addView(view, lp)
        } else {
            if (view.parent != null) manager.removeView(view)
        }
    }

    private fun savePosition() {
        val view = buttonView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_X, params.x)
            .putInt(KEY_Y, params.y)
            .apply()
    }
}
