package com.gkdbrain.app.locate

import com.gkdbrain.app.snapshot.GkdSnapshot
import kotlin.math.abs

/** 实时屏幕参数（用于与快照的设备信息比对） */
data class ScreenParams(
    val width: Int,
    val height: Int,
    val isLandscape: Boolean,
)

/**
 * 坐标变换结果。
 * 当快照的屏幕参数与实时屏幕一致时为 identity；不一致时给出缩放系数或拒绝原因。
 */
data class CoordinateTransform(
    val matched: Boolean,
    val scaleX: Float,
    val scaleY: Float,
    val reason: String? = null,
) {
    fun transform(x: Int, y: Int): Pair<Int, Int> =
        ((x * scaleX).toInt()) to ((y * scaleY).toInt())

    companion object {
        val IDENTITY = CoordinateTransform(true, 1f, 1f, null)
    }
}

/**
 * 计算快照屏幕与实时屏幕之间的坐标变换。
 *
 * 规则：
 * 1. 宽高与方向完全一致 → identity（最常见，同一设备同方向抓拍）
 * 2. 宽高互换且方向相反（横竖屏旋转）→ 拒绝（旋转坐标变换复杂，需人工确认）
 * 3. 宽高不同但长宽比一致（分辨率缩放）→ 按比例缩放
 * 4. 其余（长宽比不一致）→ 拒绝
 */
fun computeCoordinateTransform(snapshot: GkdSnapshot, current: ScreenParams): CoordinateTransform {
    val sw = snapshot.screenWidth
    val sh = snapshot.screenHeight
    if (sw <= 0 || sh <= 0) {
        return CoordinateTransform(false, 1f, 1f, "快照缺少屏幕尺寸信息")
    }

    val sameSize = sw == current.width && sh == current.height &&
        snapshot.isLandscape == current.isLandscape
    if (sameSize) return CoordinateTransform.IDENTITY

    // 横竖屏旋转
    if (sw == current.height && sh == current.width &&
        snapshot.isLandscape != current.isLandscape
    ) {
        return CoordinateTransform(false, 1f, 1f, "屏幕方向不一致（横竖屏旋转）")
    }

    // 等比例缩放
    val sx = current.width.toFloat() / sw
    val sy = current.height.toFloat() / sh
    if (abs(sx - sy) < 0.02f) {
        return CoordinateTransform(true, sx, sy, "分辨率缩放 scaleX=$sx scaleY=$sy")
    }

    return CoordinateTransform(false, 1f, 1f, "屏幕宽高比例不一致")
}
