package com.gkdbrain.app.selector

import com.gkdbrain.app.snapshot.GkdSnapshot
import com.gkdbrain.app.snapshot.NodeAttr
import com.gkdbrain.app.snapshot.SnapshotNode

/** 选择器生成策略 */
enum class SelectorKind {
    /** 唯一文本（text/desc 在全树唯一） */
    UNIQUE_TEXT,

    /** 唯一 vid（vid 在全树唯一） */
    UNIQUE_VID,

    /** 关系选择器（用父节点限定） */
    RELATION,

    /** 仅 className（兜底，可能命中多个节点） */
    CLASS_ONLY,
}

/** 选择器生成结果 */
sealed interface SelectorResult {
    data class Success(
        val selector: String,
        val kind: SelectorKind,
        /** 最终用于生成选择器的节点（可能因向上找可点击祖先而改变） */
        val targetNode: SnapshotNode,
        val reason: String,
    ) : SelectorResult

    data class Failure(val reason: String) : SelectorResult
}

/**
 * 模块4：选择器生成器。
 *
 * 根据反查到的节点属性生成合法的 GKD 选择器字符串（matches）。
 *
 * 优先级：
 *   1. 唯一 vid `[vid="..."]`（vid 稳定且精准，优先）
 *   2. 唯一文本 `[text="..."]`（desc 次之）
 *   3. 关系选择器 `@ParentClass > [attr="..."]`（用父节点限定）
 *   4. 兜底 `@ClassName`
 *
 * 生成前校验文本/vid 唯一性；校验 clickable，为 false 时向上找最近可点击祖先，
 * 以该祖先作为选择器目标（GKD 点击的是可点击节点）。
 */
object SelectorGenerator {

    fun generate(snapshot: GkdSnapshot, located: SnapshotNode, vidOnly: Boolean = false): SelectorResult {
        val nodes = snapshot.nodes
        if (nodes.isEmpty()) return SelectorResult.Failure("快照无节点")
        val byId = nodes.associateBy { it.id }

        // 向上找最近可点击节点（含自身）
        val target = resolveClickable(byId, located)

        // source 为 null 时只用 vid（不用 text/desc/className，避免 GKD 识别不到）
        if (vidOnly) {
            return generateVidOnly(target, nodes, byId)
        }

        // 1. 关系选择器（优先：带祖先容器 vid 限定，用「空格=任意祖先」而非「> =直接父」，避免隔层匹配不到）
        val ancestorVid = findAncestorVidIn(byId, target)
        if (ancestorVid != null) {
            val targetQualifier = basicQualifier(target)
            if (targetQualifier != null) {
                return SelectorResult.Success(
                    "[vid=${escape(ancestorVid)}] $targetQualifier",
                    SelectorKind.RELATION,
                    target,
                    "祖先容器 vid 限定，带上下文避免误点普通组件",
                )
            }
        }

        // 2. 唯一 vid（无祖先容器时的兜底）
        target.attr.vid?.takeIf { it.isNotBlank() }?.let { vid ->
            if (nodes.count { it.attr.vid == vid } == 1) {
                return SelectorResult.Success(
                    "[vid=${escape(vid)}]",
                    SelectorKind.UNIQUE_VID,
                    target,
                    "vid 在全树唯一",
                )
            }
        }

        // 3. 唯一文本（倒计时文本用前缀匹配，普通文本用精确匹配）
        val rawText = target.attr.text
        val strippedText = stripCountdown(rawText)
        if (strippedText != null) {
            val count = nodes.count { stripCountdown(it.attr.text) == strippedText }
            if (count == 1) {
                val isCountdown = rawText != null && rawText != strippedText
                val selector = if (isCountdown) {
                    "[text*=${escape(strippedText)}]"
                } else {
                    "[text=${escape(strippedText)}]"
                }
                return SelectorResult.Success(
                    selector,
                    SelectorKind.UNIQUE_TEXT,
                    target,
                    "text 在全树唯一",
                )
            }
        }
        uniqueValue(target, nodes) { it.desc }?.let { value ->
            return SelectorResult.Success(
                "[desc=${escape(value)}]",
                SelectorKind.UNIQUE_TEXT,
                target,
                "desc 在全树唯一",
            )
        }

        // 4. 兜底：className
        target.attr.name?.takeIf { it.isNotBlank() }?.let { name ->
            return SelectorResult.Success(
                "@$name",
                SelectorKind.CLASS_ONLY,
                target,
                "仅 className，可能非唯一",
            )
        }

        return SelectorResult.Failure("目标节点无可用的 text/vid/className")
    }

    /** source 为 null 时只用 vid：先唯一 vid，再「祖先 vid > 目标 vid」，都没有则失败 */
    private fun generateVidOnly(
        target: SnapshotNode,
        nodes: List<SnapshotNode>,
        byId: Map<Int, SnapshotNode>,
    ): SelectorResult {
        val targetVid = target.attr.vid?.takeIf { it.isNotBlank() }
            ?: return SelectorResult.Failure("source null 且无可用 vid")
        // 1. 唯一 vid
        if (nodes.count { it.attr.vid == targetVid } == 1) {
            return SelectorResult.Success(
                "[vid=${escape(targetVid)}]",
                SelectorKind.UNIQUE_VID,
                target,
                "vid 在全树唯一",
            )
        }
        // 2. 关系选择器：祖先 vid + 目标 vid（空格=任意祖先，跳过与目标同名的祖先，避免自引用）
        var ancestor = byId[target.pid]
        while (ancestor != null && ancestor.id != target.id) {
            val ancestorVid = ancestor.attr.vid?.takeIf { it.isNotBlank() }
            if (ancestorVid != null && ancestorVid != targetVid) {
                return SelectorResult.Success(
                    "[vid=${escape(ancestorVid)}] [vid=${escape(targetVid)}]",
                    SelectorKind.RELATION,
                    target,
                    "祖先 vid + 目标 vid 限定",
                )
            }
            ancestor = byId[ancestor.pid]
        }
        return SelectorResult.Failure("source null 且无可用 vid")
    }

    /** 返回该属性在目标节点上的值，当且仅当该值在全树唯一；否则返回 null */
    private fun uniqueValue(
        target: SnapshotNode,
        nodes: List<SnapshotNode>,
        selector: (NodeAttr) -> String?,
    ): String? {
        val value = selector(target.attr)?.takeIf { it.isNotBlank() } ?: return null
        return if (nodes.count { selector(it.attr) == value } == 1) value else null
    }

    /** 去掉文本末尾的倒计时数字（如「退出 3」→「退出」、「跳过5」→「跳过」） */
    private fun stripCountdown(text: String?): String? {
        if (text == null) return null
        return text.replace(Regex("\\s*\\d+\\s*$"), "").trim().ifBlank { null }
    }

    /** 目标节点的最优先单属性限定：text（倒计时转包含匹配）→ desc → vid → className */
    private fun basicQualifier(node: SnapshotNode): String? {
        val rawText = node.attr.text
        val stripped = stripCountdown(rawText)
        if (stripped != null) {
            val isCountdown = rawText != null && rawText != stripped
            return if (isCountdown) "[text*=${escape(stripped)}]" else "[text=${escape(stripped)}]"
        }
        node.attr.desc?.takeIf { it.isNotBlank() }?.let { return "[desc=${escape(it)}]" }
        node.attr.vid?.takeIf { it.isNotBlank() }?.let { return "[vid=${escape(it)}]" }
        node.attr.name?.takeIf { it.isNotBlank() }?.let { return "@$it" }
        return null
    }

    /** 用作约束（父节点）的限定：vid → className */
    private fun constraintQualifier(node: SnapshotNode): String? {
        node.attr.vid?.takeIf { it.isNotBlank() }?.let { return "[vid=${escape(it)}]" }
        node.attr.name?.takeIf { it.isNotBlank() }?.let { return "@$it" }
        return null
    }

    /** 向上找最近「有意义」的祖先容器 vid（不含自身，且不等于目标自身 vid，避免自引用），无则返回 null */
    private fun findAncestorVidIn(byId: Map<Int, SnapshotNode>, node: SnapshotNode): String? {
        val selfVid = node.attr.vid
        var ancestor = byId[node.pid]
        while (ancestor != null && ancestor.id != node.id) {
            val vid = ancestor.attr.vid?.takeIf { it.isNotBlank() }
            if (vid != null && vid != selfVid) return vid
            ancestor = byId[ancestor.pid]
        }
        return null
    }

    /** 供外部（CapturePipeline）提取「广告容器 vid」，用于按 App 记住特征 */
    fun findAncestorVid(nodes: List<SnapshotNode>, node: SnapshotNode): String? =
        findAncestorVidIn(nodes.associateBy { it.id }, node)

    /** 向上找最近可点击节点（含自身）；若整条祖先链都不可点击则返回原节点 */
    private fun resolveClickable(
        byId: Map<Int, SnapshotNode>,
        node: SnapshotNode,
    ): SnapshotNode {
        var cur = node
        while (!cur.attr.clickable) {
            val parent = byId[cur.pid] ?: break
            if (parent.id == cur.id) break
            cur = parent
        }
        return cur
    }

    /**
     * 按 GKD 选择器语法转义字符串字面量。
     * 与 gkd-selector 的 escapeString 保持一致：双引号包裹，转义 `"` `\n` `\r` `\t` `\b` `\`，
     * 控制字符用 `\xNN`，代理对保留原样。
     */
    private fun escape(value: String): String {
        val result = StringBuilder(value.length + 2)
        result.append('"')
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char in '\uD800'..'\uDBFF' && value.getOrNull(index + 1) in '\uDC00'..'\uDFFF') {
                result.append(char)
                result.append(value[index + 1])
                index += 2
                continue
            }
            val escapeChar = when (char) {
                '"' -> '"'
                '\n' -> 'n'
                '\r' -> 'r'
                '\t' -> 't'
                '\b' -> 'b'
                '\\' -> '\\'
                else -> null
            }
            if (escapeChar != null) {
                result.append('\\').append(escapeChar)
            } else {
                when (char.code) {
                    in 0..0xf -> result.append("\\x0").append(char.code.toString(16))
                    in 0x10..0x1f -> result.append("\\x").append(char.code.toString(16))
                    in 0xd800..0xdfff -> result.append("\\u").append(char.code.toString(16).padStart(4, '0'))
                    else -> result.append(char)
                }
            }
            index++
        }
        result.append('"')
        return result.toString()
    }
}
