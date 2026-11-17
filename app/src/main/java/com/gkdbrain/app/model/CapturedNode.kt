package com.gkdbrain.app.model

/**
 * 模块 1：被点击节点的完整快照信息。
 *
 * bounds 用 4 个 int 存储，便于后续模块做坐标比对、序列化和面积计算。
 */
data class CapturedNode(
    val packageName: String?,
    val className: String?,
    val text: String?,
    val viewIdResourceName: String?,
    val contentDescription: String?,
    val isClickable: Boolean,
    val boundsLeft: Int,
    val boundsTop: Int,
    val boundsRight: Int,
    val boundsBottom: Int,
    val captureTime: Long
) {
    val width: Int get() = boundsRight - boundsLeft
    val height: Int get() = boundsBottom - boundsTop
    val centerX: Int get() = (boundsLeft + boundsRight) / 2
    val centerY: Int get() = (boundsTop + boundsBottom) / 2
    val area: Int get() = width * height

    /** 是否包含屏幕上的某个坐标点 */
    fun contains(x: Int, y: Int): Boolean =
        x in boundsLeft..boundsRight && y in boundsTop..boundsBottom

    fun toSummary(): String = buildString {
        appendLine("包名: ${packageName ?: "-"}")
        appendLine("类名: ${className ?: "-"}")
        appendLine("文本: ${text ?: "-"}")
        appendLine("viewId: ${viewIdResourceName ?: "-"}")
        appendLine("描述: ${contentDescription ?: "-"}")
        appendLine("可点击: $isClickable")
        appendLine("bounds: [$boundsLeft, $boundsTop, $boundsRight, $boundsBottom]")
        appendLine("尺寸: ${width}x$height  中心: ($centerX, $centerY)")
    }
}
