package com.gkdbrain.app.monitor

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 简化选择器匹配器：在无障碍实时节点树中判断某选择器是否存在匹配节点。
 * 只支持本 App 生成器产出的几种形式：
 *   [text=".."]  [desc=".."]  [vid=".."]  @ClassName  parent > target
 * 与 GKD 的 vid 语义一致：viewIdResourceName 去掉 "{packageName}:id/" 前缀。
 */
object SelectorMatcher {

    /** 整棵树里是否存在匹配 selector 的节点 */
    fun exists(root: AccessibilityNodeInfo?, selector: String): Boolean {
        if (root == null) return false
        val (parentSelector, targetSelector) = splitRelation(selector)
        return findTarget(root, targetSelector) { node ->
            parentSelector == null || hasAncestor(node, parentSelector)
        }
    }

    /** 判断单个节点（含其祖先链）是否匹配 selector */
    fun matchesNode(node: AccessibilityNodeInfo, selector: String): Boolean {
        val (parentSelector, targetSelector) = splitRelation(selector)
        if (!matchesSingle(node, targetSelector)) return false
        return parentSelector == null || hasAncestor(node, parentSelector)
    }

    /** 把 "parent > target" 拆成 (parent, target)；无关系则 parent 为 null */
    private fun splitRelation(selector: String): Pair<String?, String> {
        val idx = selector.indexOf(" > ")
        return if (idx >= 0) {
            selector.substring(0, idx) to selector.substring(idx + 3)
        } else {
            null to selector
        }
    }

    /** 深度优先遍历，找到匹配 target 且满足 extra 条件的节点 */
    private fun findTarget(
        node: AccessibilityNodeInfo,
        targetSelector: String,
        extra: (AccessibilityNodeInfo) -> Boolean,
    ): Boolean {
        if (matchesSingle(node, targetSelector) && extra(node)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findTarget(child, targetSelector, extra)) return true
        }
        return false
    }

    /** 节点祖先链上是否存在匹配 parentSelector 的节点 */
    private fun hasAncestor(node: AccessibilityNodeInfo, parentSelector: String): Boolean {
        var p = node.parent
        while (p != null) {
            if (matchesSingle(p, parentSelector)) return true
            p = p.parent
        }
        return false
    }

    /** 单个限定是否匹配该节点 */
    private fun matchesSingle(node: AccessibilityNodeInfo, selector: String): Boolean {
        extractContains(selector, "text")?.let { return node.text?.toString()?.contains(it) == true }
        extractContains(selector, "desc")?.let { return node.contentDescription?.toString()?.contains(it) == true }
        extractPrefix(selector, "text")?.let { return node.text?.toString()?.startsWith(it) == true }
        extractPrefix(selector, "desc")?.let { return node.contentDescription?.toString()?.startsWith(it) == true }
        extractValue(selector, "text")?.let { return node.text?.toString() == it }
        extractValue(selector, "desc")?.let { return node.contentDescription?.toString() == it }
        extractValue(selector, "vid")?.let { return vidOf(node) == it }
        if (selector.startsWith("@")) {
            return node.className?.toString() == selector.substring(1)
        }
        return false
    }

    /** 解析 [key*="value"]（包含匹配）中的 value，不匹配返回 null */
    private fun extractContains(selector: String, key: String): String? {
        val prefix = "[$key*="
        if (!selector.startsWith(prefix) || !selector.endsWith("]")) return null
        val quoted = selector.substring(prefix.length, selector.length - 1)
        return unquote(quoted)
    }

    /** 解析 [key^="value"]（前缀匹配）中的 value，不匹配返回 null */
    private fun extractPrefix(selector: String, key: String): String? {
        val prefix = "[$key^="
        if (!selector.startsWith(prefix) || !selector.endsWith("]")) return null
        val quoted = selector.substring(prefix.length, selector.length - 1)
        return unquote(quoted)
    }

    /** 解析 [key="value"] 中的 value（反转义），不匹配返回 null */
    private fun extractValue(selector: String, key: String): String? {
        val prefix = "[$key="
        if (!selector.startsWith(prefix) || !selector.endsWith("]")) return null
        val quoted = selector.substring(prefix.length, selector.length - 1)
        return unquote(quoted)
    }

    /** 与 GKD AttrInfoExt 一致：id 去掉 "{packageName}:id/" 前缀，否则 null */
    private fun vidOf(node: AccessibilityNodeInfo): String? {
        val id = node.viewIdResourceName ?: return null
        val appId = node.packageName?.toString() ?: return null
        val prefix = "$appId:id/"
        return if (id.startsWith(prefix)) id.substring(prefix.length) else null
    }

    /** 反转义双引号字符串（与 SelectorGenerator.escape 互逆） */
    private fun unquote(quoted: String): String {
        val inner = quoted.removeSurrounding("\"")
        val sb = StringBuilder()
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (c == '\\' && i + 1 < inner.length) {
                val next = inner[i + 1]
                when (next) {
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    'x' -> {
                        if (i + 4 <= inner.length) {
                            sb.append(inner.substring(i + 2, i + 4).toInt(16).toChar())
                            i += 4
                        } else i++
                    }
                    'u' -> {
                        if (i + 6 <= inner.length) {
                            sb.append(inner.substring(i + 2, i + 6).toInt(16).toChar())
                            i += 6
                        } else i++
                    }
                    else -> { sb.append(next); i += 2 }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
