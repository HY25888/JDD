package com.gkdbrain.app.snapshot

import kotlinx.serialization.Serializable

/**
 * 快照中的一个无障碍节点（对应 GKD 的 NodeInfo）。
 *
 * id/pid 组成树结构：id 为节点序号（前序遍历），pid 为父节点 id（根为 -1）。
 * idQf/textQf 是 GKD 的 fastQuery 标志（表示 id/text 能否通过系统 API 唯一命中），
 * 我们自己的唯一性判断在模块4 中独立计算，这里仅原样保留。
 */
@Serializable
data class SnapshotNode(
    val id: Int = 0,
    val pid: Int = -1,
    val idQf: Boolean? = null,
    val textQf: Boolean? = null,
    val attr: NodeAttr = NodeAttr(),
)

/**
 * 节点属性（对应 GKD 的 AttrInfo）。
 *
 * 注意：真实快照格式里没有单独的 `bounds` 字段，坐标用 left/top/right/bottom 表示；
 * text/vid/clickable 等也都嵌套在 attr 对象下（与需求描述的扁平结构略有差异，以实际为准）。
 */
@Serializable
data class NodeAttr(
    /** viewIdResourceName 完整值，如 "com.example:id/skip" */
    val id: String? = null,
    /** 去掉 "包名:id/" 前缀后的值，如 "skip"；无该前缀时为 null */
    val vid: String? = null,
    /** className，如 "android.widget.Button" */
    val name: String? = null,
    val text: String? = null,
    /** contentDescription */
    val desc: String? = null,
    val clickable: Boolean = false,
    val focusable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean? = null,
    val editable: Boolean = false,
    val longClickable: Boolean = false,
    val visibleToUser: Boolean = true,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val childCount: Int = 0,
    val index: Int = 0,
    val depth: Int = 0,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val area: Int get() = width * height
    val hasArea: Boolean get() = right > left && bottom > top

    /** 判断屏幕坐标是否落在该节点 bounds 内 */
    fun contains(x: Int, y: Int): Boolean = x in left..right && y in top..bottom
}
