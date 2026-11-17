package com.gkdbrain.app.snapshot

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotParserTest {

    private val sampleJson: String =
        SnapshotParserTest::class.java.getResourceAsStream("/sample_snapshot.json")!!
            .readBytes().toString(Charsets.UTF_8)

    @Test
    fun `解析JSON_节点数量与结构正确`() {
        val s = SnapshotParser.parseJson(sampleJson)

        // 顶层信息
        assertEquals("com.example.app", s.appId)
        assertEquals("com.example.app.MainActivity", s.activityId)
        assertEquals(1080, s.screenWidth)
        assertEquals(2400, s.screenHeight)
        assertEquals(false, s.isLandscape)
        assertEquals(3, s.nodes.size)

        // 设备信息
        assertEquals("Test Phone", s.device?.model)
        assertEquals(35, s.device?.sdkInt)

        // 目标节点（跳过按钮）
        val skip = s.nodes[2].attr
        assertEquals("跳过", skip.text)
        assertEquals("skip", skip.vid)
        assertEquals("com.example.app:id/skip", skip.id)
        assertTrue(skip.clickable)
        assertEquals(840, skip.left)
        assertEquals(96, skip.top)
        assertEquals(1080, skip.right)
        assertEquals(240, skip.bottom)
        assertEquals(240, skip.width)
        assertEquals(144, skip.height)

        // 树结构：pid 指向父节点
        assertEquals(-1, s.nodes[0].pid)
        assertEquals(0, s.nodes[1].pid)
        assertEquals(1, s.nodes[2].pid)
        assertNull(s.nodes[0].idQf) // 缺失的 nullable 字段应解析为 null

        // 输出节点结构示例
        println("===== 节点数量: ${s.nodes.size} =====")
        s.nodes.forEach { n ->
            val a = n.attr
            println(
                "节点${n.id} pid=${n.pid} name=${a.name} text=${a.text ?: "-"} " +
                    "vid=${a.vid ?: "-"} clickable=${a.clickable} " +
                    "bounds=[${a.left},${a.top},${a.right},${a.bottom}]"
            )
        }
    }

    @Test
    fun `解析Zip_自动定位json条目`() {
        // 构造一个真实结构的 zip：json + webp 截图
        val zipFile = File.createTempFile("snapshot", ".zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("1734099604908.json"))
            zos.write(sampleJson.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("1734099604908.webp"))
            zos.write(byteArrayOf(1, 2, 3, 4))
            zos.closeEntry()
        }

        val s = SnapshotParser.parse(zipFile)
        assertEquals(3, s.nodes.size)
        assertEquals("跳过", s.nodes[2].attr.text)
        zipFile.delete()
    }
}
