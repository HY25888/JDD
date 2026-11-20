package com.gkdbrain.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.Rect
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.gkdbrain.app.history.HistoryStore
import com.gkdbrain.app.locate.ScreenParams
import com.gkdbrain.app.model.CapturedNode
import com.gkdbrain.app.monitor.HistoryTracker
import com.gkdbrain.app.monitor.MonitorManager
import com.gkdbrain.app.monitor.SelectorMatcher
import com.gkdbrain.app.selector.AdFeatureStore
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 模块 1：无障碍服务，监听用户点击。
 *
 * 职责：
 * - 监听 TYPE_VIEW_CLICKED 事件
 * - 记录被点击节点的 boundsInScreen、text、viewId、clickable 等信息
 * - 当处于"等待点击"状态时，把节点存入 ClickCaptureManager 并退出等待状态
 *
 * 注意：本服务只记录信息，不执行任何点击/改写操作。
 */
class GkdBrainAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> handleViewClicked(event)

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // 等待点击时窗口变化（用户切换/返回界面），取消等待
                if (ClickCaptureManager.waitingForClick.value) {
                    CapturePipeline.cancelWaiting()
                }
                if (MonitorManager.isMonitoring) {
                    MonitorManager.onWindowEvent(rootInActiveWindow)
                }
                HistoryTracker.onWindowEvent(rootInActiveWindow)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 学习监控：仅处于监控中时才检查
                if (MonitorManager.isMonitoring) {
                    MonitorManager.onWindowEvent(rootInActiveWindow)
                }
                // 常驻出现检测：历史弹窗出现即置顶
                HistoryTracker.onWindowEvent(rootInActiveWindow)
            }

            else -> Unit
        }
    }

    private val screenshotFileObservers = mutableListOf<FileObserver>()
    private val screenshotTimeout = Runnable {
        AppLog.i("截图监听超时（5 秒），已关闭")
        stopScreenshotWatch()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    // 等待点击时按返回，取消等待
                    if (ClickCaptureManager.waitingForClick.value) {
                        CapturePipeline.cancelWaiting()
                    }
                }
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    AppLog.i("检测到音量键按下: ${event.keyCode}")
                    val prefs = getSharedPreferences("gkd_settings", MODE_PRIVATE)
                    if (prefs.getBoolean("volume_snapshot_enabled", false)) {
                        AppLog.i("开始监听截图（5 秒窗口）")
                        startScreenshotWatch()
                    }
                }
            }
        }
        return super.onKeyEvent(event)
    }

    /** 按下音量键后 5 秒内监听截图目录，检测到新截图文件后延迟抓快照 */
    private fun startScreenshotWatch() {
        stopScreenshotWatch()
        val handler = Handler(Looper.getMainLooper())
        val dirs = listOf(
            File(Environment.getExternalStorageDirectory(), "DCIM/Screenshots"),
            File(Environment.getExternalStorageDirectory(), "Pictures/Screenshots"),
        )
        for (dir in dirs) {
            if (!dir.exists()) continue
            val observer = object : FileObserver(
                dir.absolutePath,
                FileObserver.CREATE or FileObserver.CLOSE_WRITE,
            ) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null) return
                    if (event and FileObserver.CREATE != 0 || event and FileObserver.CLOSE_WRITE != 0) {
                        stopScreenshotWatch()
                        handler.postDelayed({
                            CapturePipeline.prepareFromButton()
                            AppLog.i("截图后触发快照")
                        }, 500)
                    }
                }
            }
            observer.startWatching()
            screenshotFileObservers.add(observer)
            AppLog.i("监听截图目录: ${dir.absolutePath}")
        }
        handler.postDelayed(screenshotTimeout, 5000)
    }

    private fun stopScreenshotWatch() {
        screenshotFileObservers.forEach { it.stopWatching() }
        screenshotFileObservers.clear()
        Handler(Looper.getMainLooper()).removeCallbacks(screenshotTimeout)
    }

    private fun handleViewClicked(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()
        // 过滤自身 App 的点击，避免误识别为目标控件（不记日志，减少噪音）
        if (pkg == packageName) return

        val source = event.source
        if (source == null) {
            // source 为 null：用事件残留的 text/class 构造节点，走 text 反查（无坐标）
            val text = event.text?.firstOrNull()?.toString()?.trim()
            if (text.isNullOrEmpty()) {
                if (ClickCaptureManager.waitingForClick.value) {
                    AppLog.w("点击事件没有 source 且无 text，转自动查找关闭按钮")
                    CapturePipeline.fallbackToAuto()
                } else {
                    AppLog.w("点击事件没有 source 且无 text，pkg=$pkg class=${event.className}")
                }
                return
            }
            val node = CapturedNode(
                packageName = pkg,
                className = event.className?.toString(),
                text = text,
                viewIdResourceName = null,
                contentDescription = event.contentDescription?.toString(),
                isClickable = false,
                boundsLeft = 0, boundsTop = 0, boundsRight = 0, boundsBottom = 0,
                captureTime = System.currentTimeMillis(),
            )
            AppLog.i("检测到点击（无 source，用 text 反查）: $pkg / $text")
            if (ClickCaptureManager.waitingForClick.value) {
                ClickCaptureManager.capture(node)
                AppLog.i("★ 已捕获目标: $text")
                triggerPipeline(node)
            }
            return
        }

        // 点击弹窗时 source 就是弹窗节点，用它匹配历史并置顶（在 recycle 前）
        checkHistoryAppearance(source)

        val node: CapturedNode? = try {
            captureNode(event, source)
        } catch (t: Throwable) {
            AppLog.e("捕获节点失败: ${t.message}")
            null
        } finally {
            source.recycle()
        }
        if (node == null) return

        AppLog.i("检测到点击: ${node.packageName} / ${node.text} / (${node.centerX},${node.centerY})")

        if (ClickCaptureManager.waitingForClick.value) {
            ClickCaptureManager.capture(node)
            AppLog.i("★ 已捕获目标: ${node.text ?: node.className}")
            triggerPipeline(node)
        }
    }

    /** 捕获到目标后，串起「快照 → 反查 → 选择器 → 写规则」链路 */
    private fun triggerPipeline(node: CapturedNode) {
        val (width, height) = realScreenSize()
        val screen = ScreenParams(
            width = width,
            height = height,
            isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
        )
        CapturePipeline.run(node, screen)
    }

    /** 用点击到的节点匹配历史弹窗，命中就置顶（磁贴流程中不置顶） */
    private fun checkHistoryAppearance(node: AccessibilityNodeInfo) {
        if (CapturePipeline.isCapturing) return
        for (entry in HistoryStore.entries.value) {
            if (SelectorMatcher.matchesNode(node, entry.selector)) {
                HistoryStore.touch(entry.id)
                return
            }
        }
    }

    /** 真实屏幕尺寸（含系统栏），与 GKD 快照的 screenWidth/screenHeight 口径一致 */
    @Suppress("DEPRECATION")
    private fun realScreenSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val point = Point()
        wm.defaultDisplay.getRealSize(point)
        return point.x to point.y
    }

    private fun captureNode(event: AccessibilityEvent, node: AccessibilityNodeInfo): CapturedNode {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return CapturedNode(
            packageName = event.packageName?.toString(),
            className = node.className?.toString(),
            text = findText(node),
            viewIdResourceName = node.viewIdResourceName,
            contentDescription = node.contentDescription?.toString(),
            isClickable = node.isClickable,
            boundsLeft = bounds.left,
            boundsTop = bounds.top,
            boundsRight = bounds.right,
            boundsBottom = bounds.bottom,
            captureTime = System.currentTimeMillis()
        )
    }

    /** 向上查找最近的非空 text（点击的节点可能没有文字，文字在其父级容器内） */
    private fun findText(node: AccessibilityNodeInfo): String? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 4) {
            val text = current.text?.toString()
            if (!text.isNullOrBlank()) return text
            current = current.parent
            depth++
        }
        return null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        HistoryStore.init(applicationContext)
        LearningMode.load(applicationContext)
        AdFeatureStore.init(applicationContext)
        _connected.value = true
        AppLog.i("无障碍服务已连接")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        _connected.value = false
        AppLog.i("无障碍服务解绑")
        return true // 保留服务实例，重新绑定时走 onRebind，减少销毁重建抖动
    }

    override fun onInterrupt() {
        AppLog.w("无障碍服务被中断")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        /** 当前服务实例，供磁贴等服务外组件触发全局操作（如收起下拉面板） */
        @Volatile
        var instance: GkdBrainAccessibilityService? = null
            private set

        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()
    }
}
