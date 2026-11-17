package com.gkdbrain.app.subscription

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionMergerTest {

    private fun str(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun int(obj: JsonObject, key: String): Int? =
        (obj[key] as? JsonPrimitive)?.intOrNull

    private fun withKey(group: JsonObject, key: Int): JsonObject =
        JsonObject(group.toMutableMap().apply { put("key", JsonPrimitive(key)) })

    private fun emptySubscription() = buildJsonObject {
        put("id", -1L)
        put("name", "内存订阅")
        put("version", 0)
    }

    @Test
    fun `buildGroup生成正确结构`() {
        val g = RuleJsonBuilder.buildGroup("开屏广告", "[text=\"跳过\"]", delayMs = 1000)
        assertEquals("开屏广告", str(g, "name"))
        val rules = g["rules"] as JsonArray
        assertEquals(1, rules.size)
        val rule = rules[0] as JsonObject
        assertEquals("click", str(rule, "action"))
        assertEquals("[text=\"跳过\"]", ((rule["matches"] as JsonArray)[0] as JsonPrimitive).content)
        assertEquals("1000", (rule["delay"] as JsonPrimitive).content)
    }

    @Test
    fun `空订阅追加新app`() {
        val g = RuleJsonBuilder.buildGroup("开屏广告", "[text=\"跳过\"]")
        val merged = SubscriptionMerger.appendGroup(emptySubscription(), "com.example.app", g)
        val apps = merged["apps"] as JsonArray
        assertEquals(1, apps.size)
        val app = apps[0] as JsonObject
        assertEquals("com.example.app", str(app, "id"))
        val groups = app["groups"] as JsonArray
        assertEquals(1, groups.size)
        assertEquals(0, int(groups[0] as JsonObject, "key"))
    }

    @Test
    fun `已有app追加新group时key递增`() {
        val sub = buildJsonObject {
            put("id", -1L)
            put("name", "内存订阅")
            put("version", 0)
            put("apps", buildJsonArray {
                add(buildJsonObject {
                    put("id", "com.example.app")
                    put("groups", buildJsonArray {
                        add(withKey(RuleJsonBuilder.buildGroup("已有规则", "[vid=\"a\"]"), 5))
                    })
                })
            })
        }
        val g = RuleJsonBuilder.buildGroup("新规则", "[vid=\"b\"]")
        val merged = SubscriptionMerger.appendGroup(sub, "com.example.app", g)
        val groups = ((merged["apps"] as JsonArray)[0] as JsonObject)["groups"] as JsonArray
        assertEquals(2, groups.size)
        assertEquals(6, int(groups[1] as JsonObject, "key"))
    }

    @Test
    fun `同名group合并rules`() {
        val sub = buildJsonObject {
            put("id", -1L)
            put("name", "内存订阅")
            put("version", 0)
            put("apps", buildJsonArray {
                add(buildJsonObject {
                    put("id", "com.example.app")
                    put("groups", buildJsonArray { add(withKey(RuleJsonBuilder.buildGroup("开屏广告", "[vid=\"a\"]"), 0)) })
                })
            })
        }
        val g = RuleJsonBuilder.buildGroup("开屏广告", "[vid=\"b\"]")
        val merged = SubscriptionMerger.appendGroup(sub, "com.example.app", g)
        val groups = ((merged["apps"] as JsonArray)[0] as JsonObject)["groups"] as JsonArray
        assertEquals(1, groups.size)
        assertEquals(2, ((groups[0] as JsonObject)["rules"] as JsonArray).size)
        assertEquals(0, int(groups[0] as JsonObject, "key"))
    }

    @Test
    fun `保留其他app`() {
        val sub = buildJsonObject {
            put("id", -1L)
            put("name", "内存订阅")
            put("version", 0)
            put("apps", buildJsonArray {
                add(buildJsonObject {
                    put("id", "com.other.app")
                    put("groups", buildJsonArray { add(withKey(RuleJsonBuilder.buildGroup("其他", "[vid=\"x\"]"), 0)) })
                })
            })
        }
        val g = RuleJsonBuilder.buildGroup("开屏广告", "[vid=\"a\"]")
        val merged = SubscriptionMerger.appendGroup(sub, "com.example.app", g)
        val apps = merged["apps"] as JsonArray
        assertEquals(2, apps.size)
        assertEquals("com.other.app", str(apps[0] as JsonObject, "id"))
        assertEquals("com.example.app", str(apps[1] as JsonObject, "id"))
    }
}
