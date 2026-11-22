package com.gkdbrain.app.history

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 历史记录存储：创建过的弹窗规则，最新在前（挤出式）。
 * 用 SharedPreferences 持久化为 JSON。
 */
object HistoryStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private const val PREFS = "gkd_history"
    private const val KEY = "entries"

    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val raw = p.getString(KEY, null) ?: return
        _entries.value = runCatching {
            json.decodeFromString<List<HistoryEntry>>(raw)
        }.getOrDefault(emptyList())
    }

    /**
     * 新增一条记录，插到最前面；若历史里已有相同弹窗（同 appId+selector），
     * 则更新该条并置顶（不重复添加）。返回实际存储的 entry（含正确 id）。
     */
    fun add(entry: HistoryEntry): HistoryEntry {
        val existing =
            _entries.value.find { it.appId == entry.appId && it.selector == entry.selector }
        val final =
            if (existing != null) {
                entry.copy(id = existing.id, createdAt = existing.createdAt)
            } else {
                entry
            }
        _entries.value = listOf(final) + _entries.value.filter { it.id != final.id }
        persist()
        return final
    }

    fun update(entry: HistoryEntry) {
        _entries.value = _entries.value.map { if (it.id == entry.id) entry else it }
        persist()
    }

    /** 把指定记录挪到最前（弹窗再次出现时置顶） */
    fun touch(id: Long) {
        val list = _entries.value
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        val entry = list[idx]
        _entries.value = listOf(entry) + list.filter { it.id != id }
        persist()
    }

    /** 删除单条记录 */
    fun remove(id: Long) {
        _entries.value = _entries.value.filter { it.id != id }
        persist()
    }

    /** 清空所有记录 */
    fun clear() {
        _entries.value = emptyList()
        persist()
    }

    fun get(id: Long): HistoryEntry? = _entries.value.find { it.id == id }

    private fun persist() {
        prefs?.edit()?.putString(KEY, json.encodeToString(_entries.value))?.apply()
    }
}
