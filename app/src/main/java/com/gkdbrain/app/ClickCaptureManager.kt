package com.gkdbrain.app

import com.gkdbrain.app.model.CapturedNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 模块 1：点击捕获状态管理器（进程内单例）。
 *
 * 无障碍服务与主界面运行在同一进程，通过 StateFlow 共享状态：
 * - waitingForClick：是否处于"等待下一次点击"状态
 * - lastCaptured：最近一次捕获到的节点
 */
object ClickCaptureManager {

    private val _waitingForClick = MutableStateFlow(false)
    val waitingForClick: StateFlow<Boolean> = _waitingForClick.asStateFlow()

    private val _lastCaptured = MutableStateFlow<CapturedNode?>(null)
    val lastCaptured: StateFlow<CapturedNode?> = _lastCaptured.asStateFlow()

    fun setWaitingForClick(waiting: Boolean) {
        _waitingForClick.value = waiting
    }

    fun capture(node: CapturedNode) {
        _lastCaptured.value = node
        _waitingForClick.value = false
    }
}
