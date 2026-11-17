package com.gkdbrain.app.snapshot

import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * 模块2：GKD 快照 zip 解析器。
 *
 * 快照 zip 内含一个 `{id}.json`（节点树 JSON）和一个 `{id}.webp/png`（截图）。
 * 解析流程：在 zip 中定位 .json 条目 → 反序列化为 [GkdSnapshot]。
 */
object SnapshotParser {

    val json: Json = Json {
        ignoreUnknownKeys = true   // 兼容 GKD 未来新增字段
        isLenient = true
        coerceInputValues = true   // 缺失的非空字段用默认值兜底
    }

    /** 从快照 zip 文件解析（自动定位其中的 .json 条目） */
    fun parse(zipFile: File): GkdSnapshot = parseJson(readJsonEntry(zipFile))

    /** 直接解析 JSON 字符串（便于测试与后续 HTTP/网络场景） */
    fun parseJson(jsonString: String): GkdSnapshot =
        json.decodeFromString<GkdSnapshot>(jsonString)

    /** 解析 JSON 输入流 */
    fun parse(input: InputStream): GkdSnapshot =
        parseJson(input.readBytes().toString(Charsets.UTF_8))

    private fun readJsonEntry(zipFile: File): String {
        ZipFile(zipFile).use { zip ->
            val entry = zip.entries().asSequence()
                .firstOrNull { !it.isDirectory && it.name.endsWith(".json", ignoreCase = true) }
                ?: throw IllegalArgumentException("快照 zip 中未找到 .json 节点文件")
            return zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }
}
