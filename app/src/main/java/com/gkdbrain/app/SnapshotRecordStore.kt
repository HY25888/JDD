package com.gkdbrain.app

import android.content.Context
import android.content.SharedPreferences
import com.gkdbrain.app.snapshot.SnapshotNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** 一条快照记录（元数据） */
@Serializable
data class SnapshotRecord(
    val id: Long,
    val appId: String,
    val nodeCount: Int,
    val fileName: String,
)

/**
 * 快照记录存储：保存增强版快照（节点 JSON）到内部目录，并维护记录列表。
 */
object SnapshotRecordStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private const val PREFS = "gkd_snapshot_records"
    private const val KEY = "records"

    private val _records = MutableStateFlow<List<SnapshotRecord>>(emptyList())
    val records: StateFlow<List<SnapshotRecord>> = _records.asStateFlow()

    private var prefs: SharedPreferences? = null
    private var dir: File? = null

    @Serializable
    private data class SnapshotData(val appId: String, val nodes: List<SnapshotNode>)

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        dir = File(context.filesDir, "snapshots").apply { mkdirs() }
        val raw = p.getString(KEY, null) ?: return
        _records.value = runCatching {
            json.decodeFromString<List<SnapshotRecord>>(raw)
        }.getOrDefault(emptyList())
    }

    fun save(appId: String, nodes: List<SnapshotNode>): SnapshotRecord? {
        val d = dir ?: return null
        val id = System.currentTimeMillis()
        val fileName = "$id.json"
        val file = File(d, fileName)
        runCatching { file.writeText(json.encodeToString(SnapshotData(appId, nodes))) }.getOrElse {
            AppLog.e("保存快照失败: ${it.message}")
            return null
        }
        val record = SnapshotRecord(id = id, appId = appId, nodeCount = nodes.size, fileName = fileName)
        _records.value = listOf(record) + _records.value
        persist()
        return record
    }

    fun remove(id: Long) {
        val record = _records.value.find { it.id == id } ?: return
        dir?.let { runCatching { File(it, record.fileName).delete() } }
        _records.value = _records.value.filter { it.id != id }
        persist()
    }

    fun clear() {
        dir?.let { d -> runCatching { d.listFiles()?.forEach { it.delete() } } }
        _records.value = emptyList()
        persist()
    }

    fun getFile(record: SnapshotRecord): File? = dir?.let { File(it, record.fileName) }

    private fun persist() {
        prefs?.edit()?.putString(KEY, json.encodeToString(_records.value))?.apply()
    }
}
