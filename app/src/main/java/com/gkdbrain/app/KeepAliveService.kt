package com.gkdbrain.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlin.concurrent.thread

/**
 * 保活前台服务：通知栏常驻，降低进程被系统回收的概率，
 * 并定时自检无障碍（10 秒一次），断开且有权限时静默重开。
 */
class KeepAliveService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastRestartTime = 0L

    private val checkRunnable = object : Runnable {
        override fun run() {
            checkAccessibility()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        isRunning = true
        handler.postDelayed(checkRunnable, CHECK_INTERVAL_MS)
        AppLog.i("保活前台服务已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(checkRunnable)
        isRunning = false
        AppLog.i("保活前台服务已停止")
        super.onDestroy()
        // 保活开关还开着（非用户主动关闭）时，延迟自动重启，避免退后台被杀后无法恢复
        val prefs = getSharedPreferences("gkd_settings", MODE_PRIVATE)
        if (prefs.getBoolean("keep_alive_enabled", false)) {
            Handler(Looper.getMainLooper()).postDelayed({
                start(applicationContext)
            }, 3000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 定时自检：恢复悬浮球 + 无障碍断开且有 WRITE_SECURE_SETTINGS 时静默重开（10 秒一次，30 秒节流） */
    private fun checkAccessibility() {
        // 悬浮球开关开着但按钮不在（被系统回收）→ 恢复
        if (SnapshotButtonManager.isEnabled(this) && !SnapshotButtonManager.isShowing) {
            SnapshotButtonManager.show(this)
        }
        if (GkdBrainAccessibilityService.instance != null) return
        if (!ShizukuHelper.hasWriteSecureSettings(this)) return
        val now = System.currentTimeMillis()
        if (now - lastRestartTime < RESTART_THROTTLE_MS) return
        lastRestartTime = now
        thread {
            val ok = ShizukuHelper.restartAccessibility(this)
            AppLog.i(if (ok) "前台服务自检：无障碍已静默重开" else "前台服务自检：无障碍重开失败")
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(CHANNEL_ID, "保活", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                Notification.Builder(this)
            }
        return builder
            .setContentTitle("JDD 保活中")
            .setContentText("保持无障碍服务运行，防止被系统清理")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "keep_alive"
        private const val NOTIFICATION_ID = 1
        private const val CHECK_INTERVAL_MS = 10_000L
        private const val RESTART_THROTTLE_MS = 30_000L

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }
}
