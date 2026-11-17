package com.gkdbrain.app.locate

import com.gkdbrain.app.model.CapturedNode
import com.gkdbrain.app.snapshot.SnapshotParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NodeLocatorTest {

    private lateinit var snapshot: com.gkdbrain.app.snapshot.GkdSnapshot

    @Before
    fun setUp() {
        val json = NodeLocatorTest::class.java.getResourceAsStream("/sample_snapshot.json")!!
            .readBytes().toString(Charsets.UTF_8)
        snapshot = SnapshotParser.parseJson(json)
    }

    @Test
    fun `文本加坐标匹配到跳过按钮`() {
        val r = NodeLocator.locate(snapshot, 900, 150, "跳过", "com.example.app:id/skip")
        assertNotNull(r)
        assertEquals(2, r!!.node.id)
        assertEquals(MatchType.TEXT, r.matchType)
        assertEquals("跳过", r.node.attr.text)
        assertTrue(r.node.attr.clickable)
    }

    @Test
    fun `无文本匹配时回退到坐标最小可点击节点`() {
        val r = NodeLocator.locate(snapshot, 900, 150, "不存在的文本", null)
        assertNotNull(r)
        assertEquals(2, r!!.node.id) // 唯一可点击且面积最小的节点
        assertEquals(MatchType.COORDINATE_ONLY, r.matchType)
    }

    @Test
    fun `viewId匹配回退`() {
        // 点击落在 ad_container 内，无文本，用 viewId 匹配
        val r = NodeLocator.locate(snapshot, 500, 150, null, "com.example.app:id/ad_container")
        assertNotNull(r)
        assertEquals(1, r!!.node.id)
        assertEquals(MatchType.VIEW_ID, r.matchType)
    }

    @Test
    fun `坐标不在任何节点内返回null`() {
        assertNull(NodeLocator.locate(snapshot, 99999, 99999, null, null))
    }

    @Test
    fun `坐标一致性_相同屏幕为identity`() {
        val t = computeCoordinateTransform(snapshot, ScreenParams(1080, 2400, false))
        assertTrue(t.matched)
        assertEquals(1f, t.scaleX, 0.001f)
        assertEquals(1f, t.scaleY, 0.001f)
    }

    @Test
    fun `坐标一致性_分辨率缩放`() {
        val t = computeCoordinateTransform(snapshot, ScreenParams(540, 1200, false))
        assertTrue(t.matched)
        assertEquals(0.5f, t.scaleX, 0.001f)
        assertEquals(0.5f, t.scaleY, 0.001f)
    }

    @Test
    fun `坐标一致性_横竖屏旋转拒绝`() {
        val t = computeCoordinateTransform(snapshot, ScreenParams(2400, 1080, true))
        assertFalse(t.matched)
    }

    @Test
    fun `locateFromCapture_完整链路`() {
        val captured = CapturedNode(
            packageName = "com.example.app",
            className = "android.widget.Button",
            text = "跳过",
            viewIdResourceName = "com.example.app:id/skip",
            contentDescription = "跳过广告",
            isClickable = true,
            boundsLeft = 840, boundsTop = 96, boundsRight = 1080, boundsBottom = 240,
            captureTime = 0,
        )
        val result = NodeLocator.locateFromCapture(snapshot, captured, ScreenParams(1080, 2400, false))
        assertTrue("应为 Found，实际 $result", result is LocateResult.Found)
        val found = (result as LocateResult.Found).result
        assertEquals(2, found.node.id)
        assertEquals(MatchType.TEXT, found.matchType)
    }
}
