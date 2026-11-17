package com.gkdbrain.app.history

import kotlinx.serialization.Serializable

/** 弹窗学习状态 */
@Serializable
enum class LearnState {
    /** 待学习：首次生成「连续点击」规则后 */
    PENDING,

    /** 学习中：刷新后正在监控弹窗出现/消失 */
    LEARNING,

    /** 已学习：一次性点击（弹窗很快消失，无需延时） */
    CLICK_ONCE,

    /** 已学习：延时点击（弹窗存续较久，需 delay） */
    DELAYED,
}

/** 一条历史记录（创建过的弹窗） */
@Serializable
data class HistoryEntry(
    val id: Long,
    val appId: String,
    /** 生成的选择器（matches） */
    val selector: String,
    val groupName: String,
    /** GKD 中该规则组的 key（后续 replaceRuleGroup 用） */
    val groupKey: Int,
    val state: LearnState = LearnState.PENDING,
    /** 学习出的延时（仅 DELAYED 状态） */
    val delayMs: Long? = null,
    val createdAt: Long,
) {
    fun stateLabel(): String = when (state) {
        LearnState.PENDING -> "待学习"
        LearnState.LEARNING -> "学习中…"
        LearnState.CLICK_ONCE -> "一次性点击"
        LearnState.DELAYED -> "延时 ${delayMs ?: 0}ms"
    }
}
