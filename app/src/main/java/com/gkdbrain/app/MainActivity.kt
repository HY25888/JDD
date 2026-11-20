package com.gkdbrain.app

import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.gkdbrain.app.history.HistoryEntry
import com.gkdbrain.app.history.HistoryStore
import com.gkdbrain.app.http.GkdHttpClient
import com.gkdbrain.app.monitor.MonitorManager
import com.gkdbrain.app.selector.AdFeatureStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var captureHintText: TextView
    private lateinit var capturedInfoText: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var logSwitch: Switch
    private lateinit var btnToggleLog: Button
    private lateinit var editHost: EditText
    private lateinit var editPort: EditText
    private lateinit var historyEntry: LinearLayout
    private lateinit var historyCount: TextView
    private lateinit var mainScroll: ScrollView
    private lateinit var keepaliveHeader: LinearLayout
    private lateinit var keepaliveContainer: LinearLayout
    private lateinit var keepaliveToggle: TextView
    private lateinit var keepaliveBatteryStatus: TextView
    private lateinit var keepaliveAutostartStatus: TextView
    private lateinit var keepaliveShizukuStatus: TextView
    private lateinit var keepaliveWssStatus: TextView
    private lateinit var keepaliveSwitch: Switch
    private lateinit var snapshotButtonSwitch: Switch
    private lateinit var volumeSnapshotSwitch: Switch
    private lateinit var autoModeSwitch: Switch
    private lateinit var autoModeTileSwitch: Switch
    private lateinit var autoModeVolumeSwitch: Switch
    private lateinit var autoModeButtonSwitch: Switch
    private lateinit var autoModeSubContainer: LinearLayout
    private lateinit var hideFromRecentsSwitch: Switch
    private var keepaliveExpanded = false

    /** 日志是否展开（默认收起，仅显示 5 行） */
    private var logExpanded = false

    /** 保活状态实时刷新任务 */
    private var keepAliveRefreshJob: Job? = null

    private val prefs by lazy { getSharedPreferences("gkd_settings", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        captureHintText = findViewById(R.id.capture_hint_text)
        capturedInfoText = findViewById(R.id.captured_info_text)
        logText = findViewById(R.id.log_text)
        logScroll = findViewById(R.id.log_scroll)
        logSwitch = findViewById(R.id.log_switch)
        btnToggleLog = findViewById(R.id.btn_toggle_log)
        editHost = findViewById(R.id.edit_host)
        editPort = findViewById(R.id.edit_port)
        historyEntry = findViewById(R.id.history_entry)
        historyCount = findViewById(R.id.history_count)
        mainScroll = findViewById(R.id.main_scroll)
        keepaliveHeader = findViewById(R.id.keepalive_header)
        keepaliveContainer = findViewById(R.id.keepalive_container)
        keepaliveToggle = findViewById(R.id.keepalive_toggle)
        keepaliveBatteryStatus = findViewById(R.id.keepalive_battery_status)
        keepaliveAutostartStatus = findViewById(R.id.keepalive_autostart_status)
        keepaliveShizukuStatus = findViewById(R.id.keepalive_shizuku_status)
        keepaliveWssStatus = findViewById(R.id.keepalive_wss_status)
        keepaliveSwitch = findViewById(R.id.keepalive_switch)
        snapshotButtonSwitch = findViewById(R.id.snapshot_button_switch)
        volumeSnapshotSwitch = findViewById(R.id.volume_snapshot_switch)
        autoModeSwitch = findViewById(R.id.auto_mode_switch)
        autoModeTileSwitch = findViewById(R.id.auto_mode_tile_switch)
        autoModeVolumeSwitch = findViewById(R.id.auto_mode_volume_switch)
        autoModeButtonSwitch = findViewById(R.id.auto_mode_button_switch)
        autoModeSubContainer = findViewById(R.id.auto_mode_sub_container)
        hideFromRecentsSwitch = findViewById(R.id.hide_from_recents_switch)

        // 载入持久化配置
        GkdHttpClient.load(this)
        HistoryStore.init(this)
        LearningMode.load(this)
        AdFeatureStore.init(this)
        val logEnabled = prefs.getBoolean("log_enabled", false)
        AppLog.enabled = logEnabled
        logSwitch.isChecked = logEnabled

        // 调试服务地址回显
        editHost.setText(GkdHttpClient.host)
        editPort.setText(GkdHttpClient.port.toString())

        findViewById<Button>(R.id.btn_enable_service).setOnClickListener {
            openAccessibilitySettings()
        }
        findViewById<Button>(R.id.btn_add_tile).setOnClickListener {
            requestAddTile()
        }
        snapshotButtonSwitch.isChecked = SnapshotButtonManager.isEnabled(this)
        snapshotButtonSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked == SnapshotButtonManager.isShowing) return@setOnCheckedChangeListener
            if (checked) {
                if (SnapshotButtonManager.hasPermission(this)) {
                    SnapshotButtonManager.show(this)
                } else {
                    snapshotButtonSwitch.isChecked = false
                    Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show()
                    requestOverlayPermission()
                }
            } else {
                SnapshotButtonManager.hide()
            }
        }
        volumeSnapshotSwitch.isChecked = prefs.getBoolean("volume_snapshot_enabled", false)
        volumeSnapshotSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("volume_snapshot_enabled", checked).commit()
        }
        CapturePipeline.autoMode = prefs.getBoolean("auto_mode", true)
        CapturePipeline.autoModeTile = prefs.getBoolean("auto_mode_tile", true)
        CapturePipeline.autoModeVolume = prefs.getBoolean("auto_mode_volume", true)
        CapturePipeline.autoModeButton = prefs.getBoolean("auto_mode_button", true)
        autoModeSwitch.isChecked = CapturePipeline.autoMode
        autoModeTileSwitch.isChecked = CapturePipeline.autoModeTile
        autoModeVolumeSwitch.isChecked = CapturePipeline.autoModeVolume
        autoModeButtonSwitch.isChecked = CapturePipeline.autoModeButton
        applyAutoModeSubVisibility()
        autoModeSwitch.setOnCheckedChangeListener { _, checked ->
            CapturePipeline.autoMode = checked
            prefs.edit().putBoolean("auto_mode", checked).commit()
            if (checked) {
                setAutoSub(true, true, true)
            } else {
                setAutoSub(false, false, false)
            }
            applyAutoModeSubVisibility()
        }
        autoModeTileSwitch.setOnCheckedChangeListener { _, checked ->
            CapturePipeline.autoModeTile = checked
            prefs.edit().putBoolean("auto_mode_tile", checked).commit()
        }
        autoModeVolumeSwitch.setOnCheckedChangeListener { _, checked ->
            CapturePipeline.autoModeVolume = checked
            prefs.edit().putBoolean("auto_mode_volume", checked).commit()
        }
        autoModeButtonSwitch.setOnCheckedChangeListener { _, checked ->
            CapturePipeline.autoModeButton = checked
            prefs.edit().putBoolean("auto_mode_button", checked).commit()
        }
        // 隐藏后台卡片
        hideFromRecentsSwitch.isChecked = prefs.getBoolean("hide_from_recents", false)
        hideFromRecentsSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("hide_from_recents", checked).commit()
            applyHideFromRecents()
        }
        applyHideFromRecents()
        // 保活卡片（保持展开，不可折叠）
        findViewById<Button>(R.id.btn_keepalive_battery).setOnClickListener {
            openBatteryOptimizationSettings()
        }
        keepaliveSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("keep_alive_enabled", checked).apply()
            if (checked) KeepAliveService.start(this) else KeepAliveService.stop(this)
        }
        findViewById<Button>(R.id.btn_keepalive_autostart).setOnClickListener {
            openAutostartSettings()
        }
        findViewById<Button>(R.id.btn_keepalive_shizuku).setOnClickListener {
            requestShizuku()
        }
        findViewById<Button>(R.id.btn_keepalive_wss).setOnClickListener {
            grantWriteSecureSettings()
        }
        // 历史记录入口卡片：点击进入独立空间
        historyEntry.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        findViewById<Button>(R.id.btn_save_host).setOnClickListener {
            saveHostPort()
        }
        findViewById<Button>(R.id.btn_reset_host).setOnClickListener {
            resetHostPort()
        }
        findViewById<Button>(R.id.btn_refresh_log).setOnClickListener {
            AppLog.clear()
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_share_log).setOnClickListener {
            shareLog()
        }
        findViewById<Button>(R.id.btn_toggle_log).setOnClickListener {
            toggleLogExpand()
        }
        findViewById<Button>(R.id.btn_log_top).setOnClickListener {
            logScroll.post { logScroll.scrollTo(0, 0) }
        }
        findViewById<Button>(R.id.btn_log_bottom).setOnClickListener {
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
        findViewById<Button>(R.id.btn_about).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        // 日志区独立滚动：手指在日志区滑动时，只滚日志，不让外层页面跟着动
        logScroll.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.parent.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }

        logSwitch.setOnCheckedChangeListener { _, checked ->
            AppLog.enabled = checked
            prefs.edit().putBoolean("log_enabled", checked).apply()
        }

        // 监听捕获结果，实时刷新界面
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ClickCaptureManager.lastCaptured.collect { node ->
                    capturedInfoText.text = node?.toSummary() ?: "（尚未捕获任何节点）"
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ClickCaptureManager.waitingForClick.collect { waiting ->
                    captureHintText.text =
                        if (waiting) "等待下一次点击…请切换到目标 App 并点击控件" else "空闲"
                }
            }
        }
        // 实时刷新运行日志
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppLog.entries.collect { renderLog() }
            }
        }
        // 实时刷新历史记录
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                HistoryStore.entries.collect { renderHistory() }
            }
        }
        // 悬浮快照按钮状态同步（长按关闭后开关跟随）
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SnapshotButtonManager.isShowingFlow.collect { showing ->
                    if (snapshotButtonSwitch.isChecked != showing) {
                        snapshotButtonSwitch.isChecked = showing
                    }
                }
            }
        }
        // 无障碍连接状态实时刷新（系统里开/关无障碍时，主界面即时跟随）
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                GkdBrainAccessibilityService.connected.collect { connected ->
                    statusText.text = if (connected) "无障碍服务：已开启" else "无障碍服务：未开启"
                }
            }
        }

        // 初始渲染日志高度/文字
        applyLogExpandState()
        renderHistory()
        AppLog.i("App 启动")
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
        updateKeepAliveStatus()
        startKeepAliveRefresh()
        snapshotButtonSwitch.isChecked = SnapshotButtonManager.isEnabled(this)
        // 进程重启后按持久化状态恢复悬浮球
        if (SnapshotButtonManager.isEnabled(this) && !SnapshotButtonManager.isShowing) {
            SnapshotButtonManager.show(this)
        }
    }

    override fun onPause() {
        super.onPause()
        keepAliveRefreshJob?.cancel()
        keepAliveRefreshJob = null
    }

    /** 每 2 秒刷新一次保活状态，让电池优化白名单等状态实时跟随 */
    private fun startKeepAliveRefresh() {
        keepAliveRefreshJob?.cancel()
        keepAliveRefreshJob = lifecycleScope.launch {
            while (isActive) {
                delay(2000)
                updateKeepAliveStatus()
            }
        }
    }

    /** 渲染日志文字（收起 5 行 / 展开全部），滚动位置保持不变（在底部则跟随最新） */
    private fun renderLog() {
        val entries = AppLog.entries.value
        val lines = if (logExpanded) entries else entries.takeLast(5)
        val newText = lines.joinToString("\n") {
            "${it.timeLabel()} [${it.level.label}] ${it.message}"
        }.ifEmpty { "（暂无日志）" }
        if (newText == logText.text.toString()) return
        val atBottom = isLogAtBottom()
        val prevScrollY = logScroll.scrollY
        logText.text = newText
        logScroll.post {
            if (atBottom) logScroll.fullScroll(View.FOCUS_DOWN)
            else logScroll.scrollTo(0, prevScrollY)
        }
    }

    private fun isLogAtBottom(): Boolean {
        val diff = logText.height - (logScroll.height + logScroll.scrollY)
        return diff <= 4
    }

    /** 渲染历史记录入口卡片（仅显示条数，详情进独立空间） */
    private fun renderHistory() {
        historyCount.text = "共 ${HistoryStore.entries.value.size} 条"
    }

    private fun toggleLogExpand() {
        logExpanded = !logExpanded
        applyLogExpandState()
        renderLog()
    }

    /** 根据展开状态设置按钮文字与日志区高度 */
    private fun applyLogExpandState() {
        btnToggleLog.text = if (logExpanded) "收起" else "展开"
        setLogScrollHeightDp(if (logExpanded) 360 else 120)
    }

    private fun setLogScrollHeightDp(dp: Int) {
        val px = (dp * resources.displayMetrics.density).toInt()
        logScroll.layoutParams = logScroll.layoutParams.apply { height = px }
        logScroll.requestLayout()
    }

    private fun saveHostPort() {
        val host = editHost.text.toString().trim()
        val port = editPort.text.toString().trim().toIntOrNull()
        if (host.isEmpty()) {
            Toast.makeText(this, "主机地址不能为空", Toast.LENGTH_SHORT).show()
            return
        }
        if (port == null || port !in 1..65535) {
            Toast.makeText(this, "端口需为 1-65535 的整数", Toast.LENGTH_SHORT).show()
            return
        }
        GkdHttpClient.save(this, host, port)
        AppLog.i("调试服务地址已更新: $host:$port")
        Toast.makeText(this, "已保存 $host:$port", Toast.LENGTH_SHORT).show()
    }

    private fun resetHostPort() {
        val (host, port) = GkdHttpClient.reset(this)
        editHost.setText(host)
        editPort.setText(port.toString())
        AppLog.i("调试服务地址已还原: $host:$port")
        Toast.makeText(this, "已还原 $host:$port", Toast.LENGTH_SHORT).show()
    }

    /** 导出当前日志为文件并通过系统分享发送 */
    private fun shareLog() {
        val file = AppLog.exportToFile(this)
        val uri = FileProvider.getUriForFile(
            this, "com.gkdbrain.app.fileprovider", file,
        )
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "JDD 日志 - ${file.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "分享日志"))
        AppLog.i("已导出日志文件: ${file.name}")
    }

    /** 根据总开关显示/隐藏自动模式子开关 */
    private fun applyAutoModeSubVisibility() {
        autoModeSubContainer.visibility = if (autoModeSwitch.isChecked) View.VISIBLE else View.GONE
    }

    /** 批量设置三个子开关（总开关切换时用），listener 会同步状态与持久化 */
    private fun setAutoSub(tile: Boolean, volume: Boolean, button: Boolean) {
        autoModeTileSwitch.isChecked = tile
        autoModeVolumeSwitch.isChecked = volume
        autoModeButtonSwitch.isChecked = button
    }

    /** 隐藏/显示后台卡片（最近任务） */
    private fun applyHideFromRecents() {
        val hide = prefs.getBoolean("hide_from_recents", false)
        runCatching {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.appTasks.forEach { it.setExcludeFromRecents(hide) }
        }
    }

    /** 引导用户把「捕获」磁贴手动添加到快捷中心 */
    private fun requestAddTile() {
        Toast.makeText(
            this,
            "请下拉通知栏 → 点编辑（铅笔图标）→ 找到「JDD 捕获」拖入快捷中心",
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun updateServiceStatus() {
        val enabled = isAccessibilityEnabled()
        statusText.text = if (enabled) "无障碍服务：已开启" else "无障碍服务：未开启"
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

    /** 刷新保活卡片里的各项权限/服务状态 */
    private fun updateKeepAliveStatus() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoring = pm.isIgnoringBatteryOptimizations(packageName)
        keepaliveBatteryStatus.text = if (ignoring) "✓" else ""

        keepaliveAutostartStatus.text = when (autoStartMode()) {
            AppOpsManager.MODE_IGNORED -> "已关闭"
            else -> "需手动确认"
        }

        keepaliveShizukuStatus.text = when {
            !ShizukuHelper.isServiceAvailable() -> "未安装"
            !ShizukuHelper.isShizukuGranted() -> "未授权"
            else -> "已授权"
        }

        keepaliveWssStatus.text =
            if (ShizukuHelper.hasWriteSecureSettings(this)) "已授权" else "未授权"

        keepaliveSwitch.isChecked = KeepAliveService.isRunning
    }

    private fun requestShizuku() {
        if (ShizukuHelper.isShizukuGranted()) {
            Toast.makeText(this, "Shizuku 已授权", Toast.LENGTH_SHORT).show()
            updateKeepAliveStatus()
            return
        }
        if (!ShizukuHelper.isServiceAvailable()) {
            Toast.makeText(this, "请先安装并启动 Shizuku App", Toast.LENGTH_LONG).show()
            return
        }
        ShizukuHelper.requestShizukuPermission { granted ->
            runOnUiThread {
                updateKeepAliveStatus()
                Toast.makeText(
                    this,
                    if (granted) "Shizuku 授权成功" else "Shizuku 授权失败",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun grantWriteSecureSettings() {
        if (ShizukuHelper.hasWriteSecureSettings(this)) {
            Toast.makeText(this, "已拥有静默重开权限", Toast.LENGTH_SHORT).show()
            updateKeepAliveStatus()
            return
        }
        if (!ShizukuHelper.isShizukuGranted()) {
            Toast.makeText(this, "请先授权 Shizuku", Toast.LENGTH_LONG).show()
            return
        }
        ShizukuHelper.grantWriteSecureSettings(this) { granted, detail ->
            runOnUiThread {
                updateKeepAliveStatus()
                Toast.makeText(
                    this,
                    if (granted) "静默重开权限授权成功" else "授权失败：$detail",
                    if (granted) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /** 检测自启动 AppOps 模式（auto_start 在许多机型默认返回允许，无法可靠判定「已开启」，只识别明确关闭） */
    private fun autoStartMode(): Int = try {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        appOps.checkOpNoThrow("auto_start", applicationInfo.uid, packageName)
    } catch (e: Exception) {
        AppOpsManager.MODE_DEFAULT
    }

    private fun openBatteryOptimizationSettings() {
        // 优先原生直达（弹「允许忽略电池优化」对话框），无处理器则逐级回退，最终统一落到应用详情页
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        if (direct.resolveActivity(packageManager) != null) {
            runCatching { startActivity(direct) }
            return
        }
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        if (list.resolveActivity(packageManager) != null) {
            runCatching { startActivity(list) }
            return
        }
        // 最终统一兜底：应用详情页（所有手机都支持）
        val detail = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        runCatching { startActivity(detail) }
    }

    /** 跳转悬浮窗权限授权页 */
    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        runCatching { startActivity(intent) }
    }

    /** 跳转自启动管理（小米 MIUI 直达，其他厂商回退到应用详情页） */
    private fun openAutostartSettings() {
        val miui = Intent().apply {
            component = ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            )
        }
        val success = runCatching { startActivity(miui) }.isSuccess
        if (!success) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                },
            )
        }
    }
}
