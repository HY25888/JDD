package com.gkdbrain.app

import android.content.Context

/**
 * 学习模式开关（历史记录页控制）：
 * - 开启：生成「连续点击（0.5s 冷却）」规则，并进入弹窗监控学习；
 * - 关闭：生成「一次性点击」规则，不进入监控学习。
 */
object LearningMode {

    private const val PREFS = "gkd_settings"
    private const val KEY = "learn_mode"

    @Volatile
    var enabled = false
        private set

    fun load(context: Context) {
        enabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        enabled = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, value).apply()
    }
}
