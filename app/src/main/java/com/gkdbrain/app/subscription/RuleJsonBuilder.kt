package com.gkdbrain.app.subscription

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 模块5：规则 JSON 构造器。
 *
 * 把模块4 生成的选择器，套入 GKD 订阅规则（rule group）的 JSON 结构。
 * 字段名与 GKD v1.13.x 的 RawSubscriptionParser 保持一致（驼峰，规则组字段参考其 jsonToGroupRaw）。
 *
 * 关键字段约定（以实际解析器为准，不臆造）：
 * - 规则组：name（必填）、key（必填，由 [SubscriptionMerger] 统一分配）、rules（数组）
 * - 规则：matches（选择器数组）、action（动作，默认 click）、delay（点击前延时，解析时等价于 actionDelay）
 */
object RuleJsonBuilder {

    const val ACTION_CLICK = "click"

    /** 构造单条规则 */
    fun buildRule(
        matches: String,
        action: String = ACTION_CLICK,
        delayMs: Long? = null,
        actionMaximum: Int? = null,
        actionCdMs: Long? = null,
        name: String? = null,
    ): JsonObject = buildJsonObject {
        put("matches", buildJsonArray { add(matches) })
        put("action", action)
        if (delayMs != null) put("delay", delayMs)
        if (actionMaximum != null) put("actionMaximum", actionMaximum)
        if (actionCdMs != null) put("actionCd", actionCdMs)
        if (name != null) put("name", name)
    }

    /** 构造一个规则组（不含 key，key 由 GKD 端自动分配） */
    fun buildGroup(
        groupName: String,
        matches: String,
        action: String = ACTION_CLICK,
        delayMs: Long? = null,
        actionMaximum: Int? = null,
        actionCdMs: Long? = null,
        desc: String? = null,
    ): JsonObject = buildJsonObject {
        put("name", groupName)
        if (desc != null) put("desc", desc)
        put(
            "rules",
            buildJsonArray { add(buildRule(matches, action, delayMs, actionMaximum, actionCdMs)) },
        )
    }
}
