package com.gkdbrain.app.subscription

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * 模块5：订阅合并器（纯函数，无网络/Android 依赖）。
 *
 * 把新 rule group 合并进现有订阅 JSON，保留用户已有规则：
 * - 同名规则组（按 name）合并 rules；
 * - 否则在对应 app 下追加新规则组，key 取「现有最大 key + 1」；
 * - 目标 app 不存在时自动创建。
 *
 * 输入/输出均为 GKD RawSubscription 的 JSON 结构（字段驼峰），不做任何字段臆造。
 */
object SubscriptionMerger {

    /** 向订阅的指定 app 追加一个规则组，返回合并后的新订阅 */
    fun appendGroup(subscription: JsonObject, appId: String, group: JsonObject): JsonObject {
        val apps = (subscription["apps"] as? JsonArray) ?: JsonArray(emptyList())
        val appElements = apps.toList()
        val index = appElements.indexOfFirst { appIdOf(it) == appId }

        val updatedApps: List<JsonElement>
        if (index >= 0) {
            val app = appElements[index] as JsonObject
            val groups = (app["groups"] as? JsonArray) ?: JsonArray(emptyList())
            updatedApps = appElements.toMutableList().also {
                it[index] = JsonObject(
                    app.toMutableMap().apply { this["groups"] = mergeGroup(groups, group) },
                )
            }
        } else {
            val newApp = buildJsonObject {
                put("id", appId)
                put("groups", mergeGroup(JsonArray(emptyList()), group))
            }
            updatedApps = appElements + newApp
        }

        return JsonObject(
            subscription.toMutableMap().apply { this["apps"] = JsonArray(updatedApps) },
        )
    }

    private fun mergeGroup(groups: JsonArray, group: JsonObject): JsonArray {
        val list = groups.toList()
        val groupName = stringOf(group, "name")

        // 同名规则组合并 rules
        if (groupName != null) {
            val index = list.indexOfFirst { stringOf(it as? JsonObject, "name") == groupName }
            if (index >= 0) {
                val existing = list[index] as JsonObject
                val existingRules = (existing["rules"] as? JsonArray) ?: JsonArray(emptyList())
                val newRules = (group["rules"] as? JsonArray) ?: JsonArray(emptyList())
                val merged = JsonObject(
                    existing.toMutableMap().apply {
                        this["rules"] = JsonArray(existingRules.toList() + newRules.toList())
                    },
                )
                return JsonArray(list.toMutableList().also { it[index] = merged })
            }
        }

        // 新规则组：分配不冲突的 key
        val maxKey = list.mapNotNull { intOf(it as? JsonObject, "key") }.maxOrNull() ?: -1
        val newGroup = JsonObject(group.toMutableMap().apply { put("key", JsonPrimitive(maxKey + 1)) })
        return JsonArray(list + newGroup)
    }

    private fun appIdOf(element: JsonElement): String? = stringOf(element as? JsonObject, "id")

    private fun stringOf(obj: JsonObject?, key: String): String? =
        (obj?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun intOf(obj: JsonObject?, key: String): Int? =
        (obj?.get(key) as? JsonPrimitive)?.intOrNull
}
