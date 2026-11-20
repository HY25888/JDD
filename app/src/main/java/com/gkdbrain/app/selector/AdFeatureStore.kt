package com.gkdbrain.app.selector

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 按 App 记住的广告特征：广告容器 vid + 关闭按钮 vid */
@Serializable
data class AdFeature(
    val containerVid: String? = null,
    val closeVid: String? = null,
)

/**
 * 广告特征库：按 App（appId）记住「广告容器 vid + 关闭按钮 vid」，
 * 用于生成带上下文的选择器（容器 vid 限定），避免裸 vid 误点普通组件。
 */
object AdFeatureStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private const val PREFS = "gkd_ad_features"
    private const val KEY = "features"

    private var prefs: SharedPreferences? = null
    private var features: MutableMap<String, AdFeature> = mutableMapOf()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val raw = p.getString(KEY, null) ?: return
        features = runCatching {
            json.decodeFromString<MutableMap<String, AdFeature>>(raw)
        }.getOrDefault(mutableMapOf())
    }

    fun remember(appId: String, containerVid: String?, closeVid: String?) {
        features[appId] = AdFeature(containerVid, closeVid)
        persist()
    }

    fun get(appId: String): AdFeature? = features[appId]

    private fun persist() {
        prefs?.edit()?.putString(KEY, json.encodeToString(features))?.apply()
    }
}
