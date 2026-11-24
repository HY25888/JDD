package com.gkdbrain.app.monitor

import android.view.accessibility.AccessibilityNodeInfo
import com.gkdbrain.app.CapturePipeline
import com.gkdbrain.app.LearningMode
import com.gkdbrain.app.history.HistoryStore

/**
 * 常驻出现检测：监听窗口事件，检测历史记录里的弹窗是否出现，出现即置顶。
 * 仅在学习模式开启时激活；关闭则不监听，避免后台遍历节点树耗电。
 * 注意：磁贴捕获流程中不置顶（历史里已有则不置顶，新弹窗由 add 创建时置顶）。
 */
object HistoryTracker {

    private var lastCheck = 0L

    fun onWindowEvent(root: AccessibilityNodeInfo?) {
        if (!LearningMode.enabled) return // 学习模式关闭时不激活，避免后台耗电
        if (root == null) return
        if (CapturePipeline.isCapturing) return // 磁贴流程中不置顶
        val now = System.currentTimeMillis()
        if (now - lastCheck < 500) return
        lastCheck = now
        for (entry in HistoryStore.entries.value) {
            if (SelectorMatcher.exists(root, entry.selector)) {
                HistoryStore.touch(entry.id)
                return
            }
        }
    }
}
