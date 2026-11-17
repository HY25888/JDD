package com.gkdbrain.app.http

import android.content.Context
import com.gkdbrain.app.snapshot.GkdSnapshot
import com.gkdbrain.app.snapshot.SnapshotParser
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** HTTP 操作结果 */
sealed interface GkdHttpResult<out T> {
    data class Success<T>(val value: T) : GkdHttpResult<T>
    data class Failure(val reason: String) : GkdHttpResult<Nothing>
}

/**
 * 模块5：GKD 本地订阅 HTTP 客户端。
 *
 * 通过 GKD 调试服务（默认 127.0.0.1:8888）向「本地订阅」（subsId = -2, LOCAL）追加应用规则。
 *
 * 接口（与 GKD v1.13.x InspectionServer/SubscriptionRoutes 对齐，不臆造）：
 * - POST /api/captureSnapshot   -> ComplexSnapshot JSON（完整节点树）
 * - POST /api/appendRules       {subsId, groupType=2(App), appId, source} -> 追加应用规则
 *   source 为「规则组 JSON 文本」，GKD 端自动分配 key、校验选择器后追加到该 app 下。
 *
 * 响应统一 HTTP 200，成功带响应头 `GKD-RPC-Status: ok`，失败为 `error` + RpcError JSON。
 */
object GkdHttpClient {

    const val DEFAULT_HOST = "127.0.0.1"
    const val DEFAULT_PORT = 8888

    /** li.gkd.db.SubscriptionIds.LOCAL（本地订阅，GKD 默认存在）；-1 是 LOCAL_HTTP 内存订阅（默认不存在） */
    const val LOCAL_SUBS_ID = -2L

    /** li.gkd.db.RuleGroupType.App */
    private const val GROUP_TYPE_APP = 2

    private const val RPC_HEADER = "GKD-RPC-Status"

    private const val PREFS = "gkd_http_config"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"

    /** 当前调试服务地址（可编辑，主界面持久化） */
    @Volatile
    var host: String = DEFAULT_HOST
        private set

    @Volatile
    var port: Int = DEFAULT_PORT
        private set

    /** 从 SharedPreferences 载入已保存的 host/port */
    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        host = prefs.getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        port = prefs.getInt(KEY_PORT, DEFAULT_PORT)
    }

    /** 保存 host/port（校验通过后调用） */
    fun save(context: Context, newHost: String, newPort: Int) {
        host = newHost
        port = newPort
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_HOST, host)
            .putInt(KEY_PORT, port)
            .apply()
    }

    /** 还原默认地址，返回还原后的 (host, port) */
    fun reset(context: Context): Pair<String, Int> {
        host = DEFAULT_HOST
        port = DEFAULT_PORT
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_HOST, host)
            .putInt(KEY_PORT, port)
            .apply()
        return host to port
    }

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }
    private val mediaType = "application/json".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /**
     * 触发 GKD 捕获当前屏幕快照，返回完整节点树 [GkdSnapshot]。
     * 接口：POST /api/captureSnapshot（无参，响应为 ComplexSnapshot JSON）。
     */
    suspend fun captureSnapshot(): GkdHttpResult<GkdSnapshot> =
        withContext(Dispatchers.IO) {
            when (val result = execute(post("/api/captureSnapshot", "{}"))) {
                is GkdHttpResult.Failure -> result
                is GkdHttpResult.Success -> runCatching {
                    SnapshotParser.json.decodeFromJsonElement<GkdSnapshot>(result.value)
                }.fold(
                    onSuccess = { GkdHttpResult.Success(it) },
                    onFailure = { GkdHttpResult.Failure("快照解析失败: ${it.message}") },
                )
            }
        }

    /**
     * 追加应用规则到本地订阅（GKD 负责校验选择器、分配 key、合并到 app 下）。
     * 返回新分配的第一个规则组 key（后续用 replaceRuleGroup 修改它）。
     */
    suspend fun appendRules(
        appId: String,
        group: JsonObject,
    ): GkdHttpResult<Int> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("subsId", LOCAL_SUBS_ID)
            put("groupType", GROUP_TYPE_APP)
            put("appId", appId)
            put("source", group.toString())
        }.toString()
        when (val result = execute(post("/api/appendRules", body))) {
            is GkdHttpResult.Failure -> result
            is GkdHttpResult.Success -> {
                val key = (result.value as? JsonArray)
                    ?.firstOrNull()
                    ?.let { it as? JsonObject }
                    ?.get("key")
                    ?.let { it as? JsonPrimitive }
                    ?.content
                    ?.toIntOrNull()
                if (key == null) GkdHttpResult.Failure("appendRules 响应缺少 key")
                else GkdHttpResult.Success(key)
            }
        }
    }

    /**
     * 用新的规则组 JSON 替换指定 key 的规则组（学习完成后把「连续点击」改成「一次性/延时」）。
     */
    suspend fun replaceRuleGroup(
        appId: String,
        groupKey: Int,
        group: JsonObject,
    ): GkdHttpResult<Unit> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("subsId", LOCAL_SUBS_ID)
            put(
                "target",
                buildJsonObject {
                    put("groupType", GROUP_TYPE_APP)
                    put("appId", appId)
                    put("groupKey", groupKey)
                },
            )
            put("source", group.toString())
        }.toString()
        when (val result = execute(post("/api/replaceRuleGroup", body))) {
            is GkdHttpResult.Failure -> result
            is GkdHttpResult.Success -> GkdHttpResult.Success(Unit)
        }
    }

    /** 核心入口：把 [group] 追加为 [appId] 的应用规则，返回分配的 key */
    suspend fun writeRule(
        appId: String,
        group: JsonObject,
    ): GkdHttpResult<Int> = appendRules(appId, group)

    private fun post(path: String, body: String): Request =
        Request.Builder()
            .url("http://$host:$port$path")
            .post(body.toRequestBody(mediaType))
            .build()

    private fun execute(request: Request): GkdHttpResult<JsonElement> =
        try {
            client.newCall(request).execute().use { response ->
                val rpcStatus = response.header(RPC_HEADER)
                val raw = response.body?.string().orEmpty()
                if (rpcStatus == "error") {
                    GkdHttpResult.Failure(parseError(raw))
                } else {
                    val element = runCatching { json.parseToJsonElement(raw) }.getOrNull()
                        ?: return@use GkdHttpResult.Failure("响应解析失败")
                    GkdHttpResult.Success(element)
                }
            }
        } catch (e: Exception) {
            GkdHttpResult.Failure(e.message ?: "网络请求失败")
        }

    private fun parseError(raw: String): String {
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        val code = obj?.get("code")?.let { (it as? JsonPrimitive)?.content }
        val message = obj?.get("message")?.let { (it as? JsonPrimitive)?.content }
        return listOfNotNull(code, message).joinToString(": ").ifEmpty { raw }
    }
}
