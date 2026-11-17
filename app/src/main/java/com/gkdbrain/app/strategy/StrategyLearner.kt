package com.gkdbrain.app.strategy

/**
 * 模块6：策略学习器。
 *
 * 从历史点击事件中学习 GKD 规则的两种执行策略：
 * - 延时点击：弹窗出现到用户点击存在稳定延时 -> 输出 [LearnedStrategy.delayMs]（对应规则 `delay`/`actionDelay`）
 * - 连续点击：短时间内对同一特征连续多次点击 -> 输出 [LearnedStrategy.actionMaximum]（对应规则 `actionMaximum`）
 *
 * 特征键 = appId + 选择器（归一化）。字段语义与 GKD RawSubscriptionParser 对齐，不臆造：
 * `actionDelay`(delay) 为点击前延时毫秒，`actionMaximum` 为每次匹配窗口内最大执行次数。
 */
data class ClickEvent(
    /** 应用包名（特征键一部分） */
    val appId: String,
    /** 归一化选择器（模块4 生成，特征键一部分） */
    val selector: String,
    /** 点击时刻（毫秒时间戳） */
    val timestampMs: Long,
    /** 弹窗出现到点击的延时（毫秒），未知则传 null */
    val appearToClickDelayMs: Long? = null,
) {
    fun featureKey(): String = "$appId\u0000$selector"
}

/** 学习到的策略 */
data class LearnedStrategy(
    /** 点击前延时（毫秒），null 表示无需延时 */
    val delayMs: Long? = null,
    /** 每次匹配窗口内最大执行次数（>1 表示连续点击），null 表示单次点击 */
    val actionMaximum: Int? = null,
) {
    val isEmpty: Boolean get() = delayMs == null && actionMaximum == null
}

object StrategyLearner {

    /** 延时低于该值视为无需延时 */
    const val DELAY_THRESHOLD_MS = 300L

    /** 相邻点击间隔不超过该值视为同一「连点簇」 */
    const val MULTI_CLICK_WINDOW_MS = 1500L

    /** 至少观测到该次数才参与学习 */
    const val MIN_SAMPLES = 2

    private val lock = Any()
    private val events = HashMap<String, MutableList<ClickEvent>>()

    /** 记录一次点击观测 */
    fun record(event: ClickEvent) = synchronized(lock) {
        events.getOrPut(event.featureKey()) { mutableListOf() }.add(event)
    }

    /** 依据历史观测，给出 appId + 选择器对应的执行策略 */
    fun suggest(appId: String, selector: String): LearnedStrategy = synchronized(lock) {
        val list = events[featureKey(appId, selector)] ?: return@synchronized LearnedStrategy()
        LearnedStrategy(
            delayMs = learnDelay(list),
            actionMaximum = learnMultiClick(list),
        )
    }

    fun clear() = synchronized(lock) { events.clear() }

    fun size(): Int = synchronized(lock) { events.values.sumOf { it.size } }

    private fun featureKey(appId: String, selector: String): String = "$appId\u0000$selector"

    /** 延时学习：有效延时样本的中位数稳定且超过阈值 -> 输出延时 */
    private fun learnDelay(list: List<ClickEvent>): Long? {
        val delays = list.mapNotNull { it.appearToClickDelayMs }.filter { it > 0 }
        if (delays.size < MIN_SAMPLES) return null
        val median = median(delays)
        return if (median >= DELAY_THRESHOLD_MS) median else null
    }

    /** 连点学习：时间窗内连续点击的最大簇长度（>=2）作为最大执行次数 */
    private fun learnMultiClick(list: List<ClickEvent>): Int? {
        if (list.size < MIN_SAMPLES) return null
        val sorted = list.map { it.timestampMs }.sorted()
        var maxCluster = 1
        var current = 1
        for (i in 1 until sorted.size) {
            if (sorted[i] - sorted[i - 1] <= MULTI_CLICK_WINDOW_MS) {
                current++
                if (current > maxCluster) maxCluster = current
            } else {
                current = 1
            }
        }
        return if (maxCluster >= 2) maxCluster else null
    }

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
    }
}
