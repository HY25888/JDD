package com.gkdbrain.app

import android.service.quicksettings.TileService

/**
 * 快捷设置磁贴：点击后收起下拉 → 抓快照 → 等待第一次点击 → 自动写入规则。
 * 另外在磁贴显示时（下拉面板展开）自检无障碍：若已断开且持有 WRITE_SECURE_SETTINGS，则静默重开。
 */
class GkdBrainTileService : TileService() {

    private var lastFixTime = 0L

    override fun onStartListening() {
        super.onStartListening()
        val now = System.currentTimeMillis()
        if (now - lastFixTime > 3000) {
            lastFixTime = now
            fixAccessibility()
        }
    }

    /** 无障碍断开时静默重开（需 WRITE_SECURE_SETTINGS，来自 Shizuku/ADB 授权） */
    private fun fixAccessibility() {
        if (GkdBrainAccessibilityService.instance != null) return
        if (!ShizukuHelper.hasWriteSecureSettings(this)) return
        val ok = ShizukuHelper.restartAccessibility(this)
        AppLog.i(if (ok) "磁贴自检：无障碍已静默重开" else "磁贴自检：无障碍重开失败")
    }

    override fun onClick() {
        super.onClick()
        CapturePipeline.prepareFromTile()
    }
}
