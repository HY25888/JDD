package com.gkdbrain.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel(val label: String) {
    DEBUG("D"),
    INFO("I"),
    WARN("W"),
    ERROR("E"),
}

data class LogEntry(
    val time: Long,
    val level: LogLevel,
    val message: String,
) {
    fun timeLabel(): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(time))
}

/**
 * 应用内运行日志（同时输出到 Logcat，TAG = GkdBrain）。
 * 用于排查「规则未写入 GKD」等链路问题，可在主界面实时查看。
 */
object AppLog {

    private const val TAG = "GkdBrain"
    private const val MAX_ENTRIES = 300

    /** 日志总开关：开启后才记录（内存 + Logcat），默认关闭 */
    @Volatile
    var enabled: Boolean = false

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun d(message: String) = log(LogLevel.DEBUG, message)
    fun i(message: String) = log(LogLevel.INFO, message)
    fun w(message: String) = log(LogLevel.WARN, message)
    fun e(message: String) = log(LogLevel.ERROR, message)

    /** 清空内存日志（「刷新」按钮） */
    fun clear() {
        _entries.value = emptyList()
    }

    /** 把当前内存日志导出为文件（「分享」按钮），返回文件句柄 */
    fun exportToFile(context: Context): File {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        val name = "gkdbrain_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".log"
        val file = File(dir, name)
        val content = _entries.value.joinToString("\n") {
            "${it.timeLabel()} [${it.level.label}] ${it.message}"
        }
        file.writeText(content)
        return file
    }

    private fun log(level: LogLevel, message: String) {
        if (!enabled) return
        when (level) {
            LogLevel.DEBUG -> Log.d(TAG, message)
            LogLevel.INFO -> Log.i(TAG, message)
            LogLevel.WARN -> Log.w(TAG, message)
            LogLevel.ERROR -> Log.e(TAG, message)
        }
        val entry = LogEntry(System.currentTimeMillis(), level, message)
        _entries.value = (_entries.value + entry).takeLast(MAX_ENTRIES)
    }
}
