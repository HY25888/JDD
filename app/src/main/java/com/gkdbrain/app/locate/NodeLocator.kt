package com.gkdbrain.app.locate

import com.gkdbrain.app.model.CapturedNode
import com.gkdbrain.app.snapshot.GkdSnapshot
import com.gkdbrain.app.snapshot.SnapshotNode

/** 命中方式 */
enum class MatchType {
    /** 文本（含 desc）匹配 */
    TEXT,

    /** viewId 匹配（文本缺失时回退） */
    VIEW_ID,

    /** 仅靠坐标（无文本且无 viewId 时的兜底） */
    COORDINATE_ONLY,
}

/** 反查结果：命中的节点 + 命中方式 */
data class NodeMatchResult(
    val node: SnapshotNode,
    val matchType: MatchType,
)

/** 定位结果 */
sealed interface LocateResult {
    data class Found(val result: NodeMatchResult) : LocateResult
    data class NotFound(val reason: String) : LocateResult
}

/**
 * 模块3：坐标 + 文本反查节点。
 *
 * 输入：点击坐标、预期文本（可选 viewId 兜底）。
 * 逻辑：
 *   1. 找出 bounds 包含坐标的所有节点
 *   2. 从中按文本（含 desc）筛选
 *   3. 无文本匹配时回退 viewId，仍无则保留全部候选
 *   4. 在候选中优先 clickable=true，再取面积最小（最深/最小节点）
 * 输出：目标节点的完整属性。
 */
object NodeLocator {

    /**
     * 核心匹配：坐标已在快照坐标系内。
     *
     * @param x 点击坐标 x
     * @param y 点击坐标 y
     * @param expectedText 模块1 捕获到的文本（可为空）
     * @param expectedViewId 模块1 捕获到的 viewIdResourceName（可为空）
     */
    fun locate(
        snapshot: GkdSnapshot,
        x: Int,
        y: Int,
        expectedText: String?,
        expectedViewId: String?,
    ): NodeMatchResult? {
        // 1. bounds 包含坐标的节点
        val containing = snapshot.nodes.filter { it.attr.contains(x, y) }
        if (containing.isEmpty()) return null

        // 2. 文本（含 desc）匹配
        val text = expectedText?.trim().orEmpty()
        val textMatched = if (text.isNotEmpty()) {
            containing.filter { it.attr.text == expectedText || it.attr.desc == expectedText }
        } else {
            emptyList()
        }

        // 3. 回退：viewId → 全部候选
        val viewId = expectedViewId?.trim().orEmpty()
        val (pool, matchType) = when {
            textMatched.isNotEmpty() -> textMatched to MatchType.TEXT
            viewId.isNotEmpty() && containing.any { it.attr.id == expectedViewId } ->
                containing.filter { it.attr.id == expectedViewId } to MatchType.VIEW_ID
            else -> containing to MatchType.COORDINATE_ONLY
        }

        // 4. 优先可点击，再取面积最小
        val best = pool.minWithOrNull(
            compareBy<SnapshotNode> { if (it.attr.clickable) 0 else 1 }
                .thenBy { it.attr.area },
        ) ?: return null

        return NodeMatchResult(best, matchType)
    }

    /**
     * 从模块1 捕获的点击节点反查快照节点。
     * 先做坐标一致性校验，再以捕获节点中心作为点击坐标匹配。
     */
    fun locateFromCapture(
        snapshot: GkdSnapshot,
        captured: CapturedNode,
        currentScreen: ScreenParams,
    ): LocateResult {
        val transform = computeCoordinateTransform(snapshot, currentScreen)
        if (!transform.matched) {
            return LocateResult.NotFound("坐标不一致：${transform.reason}")
        }
        val (x, y) = transform.transform(captured.centerX, captured.centerY)
        val result = locate(
            snapshot = snapshot,
            x = x,
            y = y,
            expectedText = captured.text,
            expectedViewId = captured.viewIdResourceName,
        ) ?: return LocateResult.NotFound("未找到包含点击坐标 ($x, $y) 的节点")
        return LocateResult.Found(result)
    }

    /** 无坐标反查：只用 text/desc 在快照里找匹配节点（source 为 null 时的兜底） */
    fun locateByText(snapshot: GkdSnapshot, text: String): NodeMatchResult? {
        val key = text.trim()
        if (key.isEmpty()) return null
        // 去掉末尾倒计时数字作为前缀，兼容「跳过 03」这类动态文本
        val prefix = key.replace(Regex("\\s*\\d+\\s*$"), "").trim()
        val matched = snapshot.nodes.filter { n ->
            val t = n.attr.text ?: n.attr.desc
            t != null && (t == key || (prefix.isNotEmpty() && t.startsWith(prefix)))
        }
        if (matched.isEmpty()) return null
        val best = matched.minWithOrNull(
            compareBy<SnapshotNode> { if (it.attr.clickable) 0 else 1 }
                .thenBy { it.attr.area },
        ) ?: return null
        return NodeMatchResult(best, MatchType.TEXT)
    }
}
