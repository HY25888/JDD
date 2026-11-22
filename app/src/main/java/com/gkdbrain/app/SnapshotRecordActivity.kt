package com.gkdbrain.app

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/** 快照记录空间：查看、清空、保存到下载、删除快照记录 */
class SnapshotRecordActivity : AppCompatActivity() {

    private lateinit var listContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_snapshot_record)

        listContainer = findViewById(R.id.snapshot_list)
        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_clear_snapshots).setOnClickListener { confirmClear() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SnapshotRecordStore.records.collect { render() }
            }
        }
        render()
    }

    private fun render() {
        listContainer.removeAllViews()
        val records = SnapshotRecordStore.records.value
        if (records.isEmpty()) {
            listContainer.addView(
                TextView(this).apply {
                    text = "（暂无快照记录）"
                    textSize = 14f
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                },
            )
            return
        }
        for (record in records) {
            listContainer.addView(buildCard(record))
        }
    }

    private fun buildCard(record: SnapshotRecord): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFFF2F2F2.toInt())
            setPadding(16, 12, 16, 12)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = (12 * resources.displayMetrics.density).toInt() }
            setOnClickListener { showActions(record) }
        }
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(record.id))
        val summary = TextView(this).apply {
            text = "${record.appId}\n$time · ${record.nodeCount} 节点"
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        card.addView(summary)
        return card
    }

    private fun showActions(record: SnapshotRecord) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(record.appId)
            .setItems(arrayOf("保存到下载", "分享", "删除")) { _, which ->
                when (which) {
                    0 -> saveToDownloads(record)
                    1 -> share(record)
                    2 -> confirmDelete(record)
                }
            }
            .show()
    }

    private fun confirmDelete(record: SnapshotRecord) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("删除快照")
            .setMessage("确定删除该快照记录吗？")
            .setPositiveButton("删除") { _, _ ->
                SnapshotRecordStore.remove(record.id)
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmClear() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("清空快照")
            .setMessage("确定清空所有快照记录吗？")
            .setPositiveButton("清空") { _, _ ->
                SnapshotRecordStore.clear()
                Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun share(record: SnapshotRecord) {
        val file = SnapshotRecordStore.getFile(record)
        if (file == null || !file.exists()) {
            Toast.makeText(this, "快照文件不存在", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "com.gkdbrain.app.fileprovider", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "分享快照"))
    }

    private fun saveToDownloads(record: SnapshotRecord) {
        val src = SnapshotRecordStore.getFile(record)
        if (src == null || !src.exists()) {
            Toast.makeText(this, "快照文件不存在", Toast.LENGTH_SHORT).show()
            return
        }
        val bytes = runCatching { src.readBytes() }.getOrNull()
        if (bytes == null) {
            Toast.makeText(this, "读取快照失败", Toast.LENGTH_SHORT).show()
            return
        }
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(bytes, record)
        } else {
            saveDirect(bytes, record)
        }
        Toast.makeText(this, if (ok) "已保存到下载" else "保存失败", Toast.LENGTH_SHORT).show()
    }

    private fun saveViaMediaStore(bytes: ByteArray, record: SnapshotRecord): Boolean = try {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "snapshot_${record.id}.json")
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return false
        contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
        true
    } catch (e: Exception) {
        false
    }

    private fun saveDirect(bytes: ByteArray, record: SnapshotRecord): Boolean = try {
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        File(dir, "snapshot_${record.id}.json").writeBytes(bytes)
        true
    } catch (e: Exception) {
        false
    }
}
