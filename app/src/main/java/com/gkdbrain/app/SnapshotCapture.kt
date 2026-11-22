package com.gkdbrain.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.gkdbrain.app.snapshot.NodeAttr
import com.gkdbrain.app.snapshot.SnapshotNode

/**
 * 增强版快照：枚举当前屏幕上的所有窗口（含独立悬浮窗/弹窗），构建「多窗口节点树」。
 *
 * 收集方式：九宫格扫描 —— 把屏幕均分为 3×3 格，逐格做 bounds 剪枝搜索
 * （节点 bounds 不与本格相交的整棵子树直接跳过），每格独立限量。
 * 解决「巨型节点树（如 WebView/抽屉）按前 N 个一刀切截断，把屏幕其它位置的
 * 广告节点丢掉」的问题；单格超限不影响其它格子。
 */
object SnapshotCapture {

    /** 九宫格：屏幕划成 grid × grid 份，逐格剪枝搜索 */
    private const val GRID = 3

    /** 每格节点收集上限（防止单格内巨型子树导致 OOM） */
    private const val PER_GRID_NODES = 3000

    /** 增强版快照结果 */
    data class EnhancedSnapshot(
        val appId: String,
        val nodes: List<SnapshotNode>,
    )

    fun capture(service: AccessibilityService): EnhancedSnapshot? {
        val windows = runCatching { service.windows }.getOrNull() ?: return null
        if (windows.isEmpty()) return null
        val screen = screenRect(service, windows) ?: return null
        val grids = splitGrids(screen)
        val nodes = mutableListOf<SnapshotNode>()
        val idCounter = intArrayOf(0)
        val seen = HashMap<String, Int>() // 节点身份 → 已分配 id（跨格去重）
        val budgets = Array(grids.size) { intArrayOf(PER_GRID_NODES) }
        val counts = IntArray(grids.size)
        for (window in windows) {
            val root = runCatching { window.root }.getOrNull() ?: continue
            try {
                for ((gi, grid) in grids.withIndex()) {
                    val before = nodes.size
                    collect(root, -1, 0, grid, nodes, idCounter, seen, budgets[gi])
                    counts[gi] += nodes.size - before
                }
            } catch (t: Throwable) {
                AppLog.w("增强快照收集窗口失败: ${t.message}")
            } finally {
                runCatching { root.recycle() }
            }
        }
        if (nodes.isEmpty()) return null
        val stats = grids.indices.joinToString(" ") { "格${it + 1}=${counts[it]}" }
        AppLog.i("九宫格扫描（${grids.size} 格，每格上限 $PER_GRID_NODES）: $stats 共 ${nodes.size} 节点")
        val appId = service.rootInActiveWindow?.packageName?.toString().orEmpty()
        return EnhancedSnapshot(appId, nodes)
    }

    /** 屏幕区域：优先用屏幕尺寸，失败时退化为所有窗口 bounds 的并集 */
    private fun screenRect(service: AccessibilityService, windows: List<AccessibilityWindowInfo>): Rect? {
        runCatching {
            val dm = service.resources.displayMetrics
            if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                return Rect(0, 0, dm.widthPixels, dm.heightPixels)
            }
        }
        val rect = Rect()
        for (w in windows) {
            val b = Rect()
            w.getBoundsInScreen(b)
            rect.union(b)
        }
        return if (rect.isEmpty) null else rect
    }

    /** 把屏幕均分为 rows × cols 的格子，最后一行/列吃掉余数，保证覆盖全屏 */
    private fun splitGrids(screen: Rect, rows: Int = GRID, cols: Int = GRID): List<Rect> {
        if (screen.width() < cols || screen.height() < rows) return listOf(Rect(screen))
        val cellW = screen.width() / cols
        val cellH = screen.height() / rows
        val grids = mutableListOf<Rect>()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val left = screen.left + c * cellW
                val top = screen.top + r * cellH
                val right = if (c == cols - 1) screen.right else left + cellW
                val bottom = if (r == rows - 1) screen.bottom else top + cellH
                grids.add(Rect(left, top, right, bottom))
            }
        }
        return grids
    }

    /**
     * 验证用：枚举所有窗口，打印每个窗口的类型/包名/节点数/bounds，
     * 用于确认「独立悬浮窗广告」能否被 windows 枚举到。
     */
    fun logWindows(service: AccessibilityService) {
        val windows = runCatching { service.windows }.getOrNull()
        if (windows == null) {
            AppLog.w("窗口枚举失败（可能 flagRetrieveInteractiveWindows 未生效）")
            return
        }
        AppLog.i("=== 枚举窗口开始，共 ${windows.size} 个 ===")
        for ((i, w) in windows.withIndex()) {
            val typeName = when (w.type) {
                AccessibilityWindowInfo.TYPE_APPLICATION -> "APPLICATION"
                AccessibilityWindowInfo.TYPE_SYSTEM -> "SYSTEM"
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "INPUT_METHOD"
                AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY -> "MAGNIFICATION"
                AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "ACCESSIBILITY_OVERLAY"
                AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "SPLIT_SCREEN_DIVIDER"
                else -> "TYPE_${w.type}"
            }
            val root = runCatching { w.root }.getOrNull()
            val nodeCount = root?.let { countNodes(it) } ?: -1
            val pkg = root?.packageName?.toString() ?: "?"
            val bounds = root?.let {
                val r = Rect()
                it.getBoundsInScreen(r)
                "(${r.left},${r.top},${r.right},${r.bottom})"
            } ?: "?"
            AppLog.i("窗口[$i] type=$typeName pkg=$pkg 节点=$nodeCount bounds=$bounds")
            runCatching { root?.recycle() }
        }
        AppLog.i("=== 枚举窗口结束 ===")
    }

    private fun countNodes(node: AccessibilityNodeInfo): Int {
        var count = 1
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            try {
                count += countNodes(child)
            } finally {
                runCatching { child.recycle() }
            }
        }
        return count
    }

    /**
     * 带剪枝的深度收集：节点 bounds 不与 [grid] 相交的整棵子树直接跳过。
     * [seen] 记录节点身份 → id：同一节点与多个格子相交时只收集一次，
     * 并保证父子 id 关系一致（重复出现时用已分配的 id 作为父 id 继续下探）。
     */
    private fun collect(
        node: AccessibilityNodeInfo,
        pid: Int,
        depth: Int,
        grid: Rect,
        nodes: MutableList<SnapshotNode>,
        idCounter: IntArray,
        seen: MutableMap<String, Int>,
        budget: IntArray,
    ) {
        if (budget[0] <= 0) return
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!Rect.intersects(bounds, grid)) return // 剪枝：与本格不相交，整棵子树跳过
        val key = nodeKey(node, bounds)
        val selfId = seen[key] ?: run {
            val id = idCounter[0]++
            nodes.add(toSnapshotNode(node, id, pid, depth, bounds))
            budget[0]--
            seen[key] = id
            id
        }
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            try {
                collect(child, selfId, depth + 1, grid, nodes, idCounter, seen, budget)
            } finally {
                runCatching { child.recycle() }
            }
        }
    }

    /** 节点身份：位置 + 关键属性，用于跨格去重 */
    private fun nodeKey(node: AccessibilityNodeInfo, bounds: Rect): String =
        "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}|" +
            "${node.viewIdResourceName}|${node.text}|${node.contentDescription}|" +
            "${node.className}|${node.childCount}"

    private fun toSnapshotNode(
        node: AccessibilityNodeInfo,
        id: Int,
        pid: Int,
        depth: Int,
        bounds: Rect,
    ): SnapshotNode {
        val fullId = node.viewIdResourceName
        val vid = fullId?.substringAfter(":id/", fullId)?.takeIf { it != fullId }
        val attr = NodeAttr(
            id = fullId,
            vid = vid,
            name = node.className?.toString(),
            text = node.text?.toString(),
            desc = node.contentDescription?.toString(),
            clickable = node.isClickable,
            focusable = node.isFocusable,
            checkable = node.isCheckable,
            checked = node.isChecked,
            editable = node.isEditable,
            longClickable = node.isLongClickable,
            visibleToUser = node.isVisibleToUser,
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
            width = bounds.width(),
            height = bounds.height(),
            childCount = node.childCount,
            index = 0,
            depth = depth,
        )
        return SnapshotNode(id = id, pid = pid, attr = attr)
    }
}
