package com.gkdbrain.app.monitor

import android.view.accessibility.AccessibilityNodeInfo
import com.gkdbrain.app.AppLog
import com.gkdbrain.app.GkdBrainAccessibilityService
import com.gkdbrain.app.history.HistoryEntry
import com.gkdbrain.app.history.HistoryStore
import com.gkdbrain.app.history.LearnState
import com.gkdbrain.app.http.GkdHttpClient
import com.gkdbrain.app.http.GkdHttpResult
import com.gkdbrain.app.subscription.RuleJsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 弹窗监控器：刷新某条历史记录后，监听弹窗「出现/消失」，记录时间差，
 * 据此把 GKD 规则从「连续点击」改成「一次性点击」或「延时点击」，只改一次。
 */
object MonitorManager {

    /** 短/长分界：1.5 秒 */
    private const val THRESHOLD_MS = 1500L

    /** 延时封顶：10 秒 */
    private const val MAX_DELAY_MS = 10000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    private var activeTask: MonitorTask? = null
    private var pollJob: Job? = null

    val isMonitoring: Boolean get() = activeTask != null

    private class MonitorTask(
        val entry: HistoryEntry,
        var appearedAt: Long? = null,
        @Volatile var finished: Boolean = false,
    )

    /** 开始监控一条历史记录对应的弹窗 */
    fun startMonitoring(entry: HistoryEntry) {
        stopPolling()
        activeTask = MonitorTask(entry)
        HistoryStore.update(entry.copy(state = LearnState.LEARNING))
        AppLog.i("开始监控弹窗: ${entry.appId} / ${entry.selector}")
    }

    fun stopMonitoring() {
        activeTask = null
        stopPolling()
    }

    /** 无障碍服务收到窗口事件时调用（事件驱动，被动检测，基本不耗电） */
    fun onWindowEvent(root: AccessibilityNodeInfo?) {
        val task = activeTask ?: return
        if (task.finished) return
        val exists = SelectorMatcher.exists(root, task.entry.selector)
        val now = System.currentTimeMillis()
        if (exists && task.appearedAt == null) {
            task.appearedAt = now
            AppLog.i("监控：弹窗出现")
            HistoryStore.touch(task.entry.id)
            startPolling(task)
        } else if (!exists && task.appearedAt != null) {
            onDisappeared(task, now)
        }
    }

    /** 低频兜底：弹窗出现后每 500ms 确认一次是否还在，学完即停（不长期轮询） */
    private fun startPolling(task: MonitorTask) {
        stopPolling()
        pollJob = scope.launch {
            while (isActive) {
                delay(500)
                val root = GkdBrainAccessibilityService.instance?.rootInActiveWindow
                if (!SelectorMatcher.exists(root, task.entry.selector)) {
                    onDisappeared(task, System.currentTimeMillis())
                    break
                }
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun onDisappeared(task: MonitorTask, now: Long) {
        if (task.finished) return
        task.finished = true
        stopPolling()
        val appearedAt = task.appearedAt
        if (appearedAt == null) {
            stopMonitoring()
            return
        }
        val duration = now - appearedAt
        AppLog.i("监控：弹窗消失，存续 ${duration}ms")
        finishLearning(task, duration)
    }

    private fun finishLearning(task: MonitorTask, duration: Long) {
        val entry = task.entry
        val group =
            if (duration < THRESHOLD_MS) {
                // 短：一次性点击（无 delay、无 actionCd）
                RuleJsonBuilder.buildGroup(entry.groupName, entry.selector)
            } else {
                // 长：延时点击，延时 = 存续时间，封顶 10 秒
                RuleJsonBuilder.buildGroup(
                    entry.groupName,
                    entry.selector,
                    delayMs = minOf(duration, MAX_DELAY_MS),
                )
            }
        scope.launch {
            when (val r = GkdHttpClient.replaceRuleGroup(entry.appId, entry.groupKey, group)) {
                is GkdHttpResult.Failure -> {
                    AppLog.e("修改规则失败: ${r.reason}")
                }
                is GkdHttpResult.Success -> {
                    val state =
                        if (duration < THRESHOLD_MS) {
                            AppLog.i("★ 弹窗存续短，已改为一次性点击")
                            LearnState.CLICK_ONCE
                        } else {
                            val delay = minOf(duration, MAX_DELAY_MS)
                            AppLog.i("★ 弹窗存续长，已改为延时点击 ${delay}ms")
                            LearnState.DELAYED
                        }
                    HistoryStore.update(
                        entry.copy(
                            state = state,
                            delayMs = if (state == LearnState.DELAYED) minOf(duration, MAX_DELAY_MS) else null,
                        ),
                    )
                }
            }
            activeTask = null
        }
    }
}
