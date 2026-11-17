package com.gkdbrain.app

import android.content.ComponentName
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.gkdbrain.app.history.HistoryEntry
import com.gkdbrain.app.history.HistoryStore
import com.gkdbrain.app.monitor.MonitorManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/** 历史记录独立空间：折叠卡片列表，每条可展开查看详情、右侧「刷新」重新学习 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var listContainer: LinearLayout
    private val expandedIds = mutableSetOf<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_history)

        listContainer = findViewById(R.id.history_list)
        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_clear_history).setOnClickListener { confirmClear() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                HistoryStore.entries.collect { render() }
            }
        }
        render()
    }

    private fun render() {
        listContainer.removeAllViews()
        val entries = HistoryStore.entries.value
        if (entries.isEmpty()) {
            listContainer.addView(
                TextView(this).apply {
                    text = "（暂无记录）"
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
        for (entry in entries) {
            listContainer.addView(buildCard(entry))
        }
    }

    private fun buildCard(entry: HistoryEntry): View {
        val expanded = expandedIds.contains(entry.id)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF2F2F2.toInt())
            setPadding(16, 12, 16, 12)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = (12 * resources.displayMetrics.density).toInt() }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener {
                if (expanded) expandedIds.remove(entry.id) else expandedIds.add(entry.id)
                render()
            }
        }

        val appIcon = ImageView(this).apply {
            val size = (36 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = (12 * resources.displayMetrics.density).toInt()
            }
            runCatching { packageManager.getApplicationIcon(entry.appId) }
                .onSuccess { setImageDrawable(it) }
        }
        val summary = TextView(this).apply {
            text = "[${entry.stateLabel()}] ${entry.appId}"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val expandIcon = TextView(this).apply {
            text = if (expanded) "▲" else "▼"
            textSize = 12f
            setPadding(12, 0, 12, 0)
        }
        val refreshBtn = Button(this).apply {
            text = "刷新"
            minWidth = 0
            setPadding(20, 0, 20, 0)
            setOnClickListener { refreshEntry(entry) }
        }
        val deleteBtn = Button(this).apply {
            text = "删除"
            minWidth = 0
            setPadding(20, 0, 20, 0)
            setOnClickListener { HistoryStore.remove(entry.id) }
        }

        header.addView(appIcon)
        header.addView(summary)
        header.addView(expandIcon)
        header.addView(refreshBtn)
        header.addView(deleteBtn)
        card.addView(header)

        if (expanded) {
            val detail = TextView(this).apply {
                text = buildDetail(entry)
                textSize = 12f
                setPadding(0, 12, 0, 0)
            }
            card.addView(detail)
        }
        return card
    }

    private fun buildDetail(entry: HistoryEntry): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(entry.createdAt))
        return "应用：${entry.appId}\n" +
            "选择器：${entry.selector}\n" +
            "规则组：${entry.groupName}\n" +
            "规则 key：${entry.groupKey}\n" +
            "创建时间：$time"
    }

    private fun confirmClear() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("清空历史记录")
            .setMessage("确定要清空所有历史记录吗？")
            .setPositiveButton("清空") { _, _ -> HistoryStore.clear() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshEntry(entry: HistoryEntry) {
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
            return
        }
        MonitorManager.startMonitoring(entry)
        Toast.makeText(this, "已开始监控，等弹窗下次出现", Toast.LENGTH_SHORT).show()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val component = ComponentName(this, GkdBrainAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any {
            it.equals(component.flattenToString(), true) ||
                it.equals(component.flattenToShortString(), true)
        }
    }
}
