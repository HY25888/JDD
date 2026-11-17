package com.gkdbrain.app.selector

import com.gkdbrain.app.snapshot.GkdSnapshot
import com.gkdbrain.app.snapshot.NodeAttr
import com.gkdbrain.app.snapshot.SnapshotNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectorGeneratorTest {

    private fun node(
        id: Int,
        pid: Int = -1,
        name: String? = "android.widget.FrameLayout",
        text: String? = null,
        desc: String? = null,
        vid: String? = null,
        clickable: Boolean = false,
    ) = SnapshotNode(
        id = id,
        pid = pid,
        attr = NodeAttr(
            id = vid?.let { "com.example.app:id/$it" },
            vid = vid,
            name = name,
            text = text,
            desc = desc,
            clickable = clickable,
        ),
    )

    private fun snapshot(vararg nodes: SnapshotNode) = GkdSnapshot(nodes = nodes.toList())

    private fun assertSuccess(result: SelectorResult, selector: String, kind: SelectorKind) {
        assertTrue("应为 Success，实际 $result", result is SelectorResult.Success)
        result as SelectorResult.Success
        assertEquals(selector, result.selector)
        assertEquals(kind, result.kind)
    }

    @Test
    fun `唯一文本生成text选择器`() {
        val s = snapshot(
            node(0),
            node(1, pid = 0, name = "android.widget.Button", text = "跳过", clickable = true),
        )
        val r = SelectorGenerator.generate(s, s.nodes[1])
        assertSuccess(r, "[text=\"跳过\"]", SelectorKind.UNIQUE_TEXT)
    }

    @Test
    fun `无文本时用唯一vid`() {
        val s = snapshot(
            node(0),
            node(1, pid = 0, name = "android.widget.ImageView", vid = "skip_icon", clickable = true),
        )
        val r = SelectorGenerator.generate(s, s.nodes[1])
        assertSuccess(r, "[vid=\"skip_icon\"]", SelectorKind.UNIQUE_VID)
    }

    @Test
    fun `文本非唯一时用父节点限定生成关系选择器`() {
        val s = snapshot(
            node(0),
            node(1, pid = 0, name = "android.widget.LinearLayout", vid = "ad_container"),
            node(2, pid = 1, name = "android.widget.Button", text = "跳过", clickable = true),
            node(3, pid = 0, name = "android.widget.LinearLayout", vid = "normal_container"),
            node(4, pid = 3, name = "android.widget.Button", text = "跳过", clickable = true),
        )
        val r = SelectorGenerator.generate(s, s.nodes[2])
        assertSuccess(r, "[vid=\"ad_container\"] > [text=\"跳过\"]", SelectorKind.RELATION)
    }

    @Test
    fun `不点击节点向上找可点击祖先`() {
        val s = snapshot(
            node(0),
            node(1, pid = 0, name = "android.widget.LinearLayout", vid = "ad_btn", clickable = true),
            node(2, pid = 1, name = "android.widget.TextView", text = "跳过", clickable = false),
        )
        val r = SelectorGenerator.generate(s, s.nodes[2])
        assertTrue("应为 Success", r is SelectorResult.Success)
        r as SelectorResult.Success
        assertEquals(1, r.targetNode.id)
        assertSuccess(r, "[vid=\"ad_btn\"]", SelectorKind.UNIQUE_VID)
    }

    @Test
    fun `无可点击祖先且无父节点时用className兜底`() {
        val s = snapshot(
            node(0, name = "android.widget.Button", clickable = true),
        )
        val r = SelectorGenerator.generate(s, s.nodes[0])
        assertSuccess(r, "@android.widget.Button", SelectorKind.CLASS_ONLY)
    }

    @Test
    fun `字符串转义引号与换行`() {
        val s = snapshot(
            node(0),
            node(1, pid = 0, name = "android.widget.Button", text = "跳过\"广告\nOK", clickable = true),
        )
        val r = SelectorGenerator.generate(s, s.nodes[1])
        assertSuccess(r, "[text=\"跳过\\\"广告\\nOK\"]", SelectorKind.UNIQUE_TEXT)
    }
}
