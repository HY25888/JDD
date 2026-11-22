package com.gkdbrain.app

import com.gkdbrain.app.http.GkdHttpClient
import com.gkdbrain.app.http.GkdHttpResult
import com.gkdbrain.app.history.HistoryEntry
import com.gkdbrain.app.history.HistoryStore
import com.gkdbrain.app.history.LearnState
import com.gkdbrain.app.locate.LocateResult
import com.gkdbrain.app.locate.NodeLocator
import com.gkdbrain.app.locate.NodeMatchResult
import com.gkdbrain.app.locate.ScreenParams
import com.gkdbrain.app.model.CapturedNode
import com.gkdbrain.app.monitor.MonitorManager
import com.gkdbrain.app.selector.AdFeatureStore
import com.gkdbrain.app.selector.SelectorGenerator
import com.gkdbrain.app.selector.SelectorResult
import com.gkdbrain.app.snapshot.GkdSnapshot
import com.gkdbrain.app.snapshot.SnapshotNode
import com.gkdbrain.app.subscription.RuleJsonBuilder
import android.accessibilityservice.AccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 链路编排：捕获点击 → GKD 快照 → 反查节点 → 生成选择器 → 写入本地订阅。
 * 串联模块2/3/4/5，逐步打日志，供主界面「运行日志」排查。
 */
object CapturePipeline {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 磁贴流程预抓的快照（先抓快照，再等点击，弹窗尚存时反查更可靠） */
    @Volatile
    private var pendingSnapshot: GkdSnapshot? = null

    /** 是否处于磁贴捕获流程中（点磁贴 → 生成规则/超时 之间为 true），历史置顶需据此区分 */
    @Volatile
    var isCapturing = false
        private set

    /** 自动模式：激活快照后不等待手动点击，自动查找关闭/跳过按钮生成规则 */
    @Volatile
    var autoMode = false

    /** 自动模式下各触发方式的子开关（默认开）：磁贴 / 音量键截图 / 悬浮球 */
    @Volatile
    var autoModeTile = true

    @Volatile
    var autoModeVolume = true

    @Volatile
    var autoModeButton = true

    /** 最近一次激活等待点击的时间戳，用于 cancelWaiting 的时间保护 */
    @Volatile
    private var lastActivateTime = 0L

    /**
     * 磁贴触发：先收起下拉面板，等目标 App 恢复前台后抓快照，再进入「等待第一次点击」状态。
     */
    fun prepareFromTile() {
        scope.launch {
            isCapturing = true
            // 执行 back 收起下拉面板（借鉴 GKD SnapshotTileService 的做法）
            GkdBrainAccessibilityService.instance
                ?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            delay(500) // 先给面板一个收起时间
            waitForForegroundApp() // 等目标 App 回到前台（不再是 systemui/launcher），最多 3 秒
            when (val r = GkdHttpClient.captureSnapshot()) {
                is GkdHttpResult.Failure -> {
                    AppLog.e("快照捕获失败: ${r.reason}")
                    isCapturing = false
                }
                is GkdHttpResult.Success -> {
                    pendingSnapshot = r.value
                    if (autoMode && autoModeTile) {
                        AppLog.i("★ 已激活（自动模式·磁贴）：快照已抓取（${r.value.nodes.size} 节点），自动查找关闭按钮")
                        autoExecute(r.value)
                        isCapturing = false
                    } else {
                        ClickCaptureManager.setWaitingForClick(true)
                        lastActivateTime = System.currentTimeMillis()
                        AppLog.i("★ 已激活：快照已抓取（${r.value.nodes.size} 节点），6 秒内点击目标控件")
                        // 6 秒超时：超时或用户返回/上滑（没点）即放弃
                        delay(9000)
                        if (ClickCaptureManager.waitingForClick.value) {
                            pendingSnapshot = null
                            ClickCaptureManager.setWaitingForClick(false)
                            AppLog.w("等待点击超时（6 秒），已放弃")
                            isCapturing = false
                        }
                    }
                }
            }
        }
    }

    /** 悬浮按钮触发：立即抓快照（不收起面板、不延时） */
    fun prepareFromButton() {
        prepareImmediate(autoMode && autoModeButton, "悬浮按钮")
    }

    /** 音量键 + 手动截图触发 */
    fun prepareFromVolume() {
        prepareImmediate(autoMode && autoModeVolume, "音量键截图")
    }

    /** 立即抓快照的公共流程：抓前移除悬浮球，抓后恢复 */
    private fun prepareImmediate(auto: Boolean, sourceLabel: String) {
        scope.launch {
            isCapturing = true
            SnapshotButtonManager.setVisible(false) // 抓前移除按钮，避免被快照捕获
            val result = GkdHttpClient.captureSnapshot()
            SnapshotButtonManager.setVisible(true) // 抓完立即恢复按钮
            when (result) {
                is GkdHttpResult.Failure -> {
                    AppLog.e("快照捕获失败: ${result.reason}")
                    isCapturing = false
                }
                is GkdHttpResult.Success -> {
                    pendingSnapshot = result.value
                    if (auto) {
                        AppLog.i("★ 已激活（自动模式·$sourceLabel）：快照已抓取（${result.value.nodes.size} 节点），自动查找关闭按钮")
                        autoExecute(result.value)
                        isCapturing = false
                    } else {
                        ClickCaptureManager.setWaitingForClick(true)
                        lastActivateTime = System.currentTimeMillis()
                        AppLog.i("★ 已激活（$sourceLabel）：快照已抓取（${result.value.nodes.size} 节点），6 秒内点击目标控件")
                        delay(9000)
                        if (ClickCaptureManager.waitingForClick.value) {
                            pendingSnapshot = null
                            ClickCaptureManager.setWaitingForClick(false)
                            AppLog.w("等待点击超时（6 秒），已放弃")
                            isCapturing = false
                        }
                    }
                }
            }
        }
    }

    /** 取消等待点击（用户按返回/切换界面时调用） */
    fun cancelWaiting() {
        if (!isCapturing) return
        // 时间保护：刚激活（<2 秒）不取消，避免悬浮球恢复等窗口变化误触发
        if (System.currentTimeMillis() - lastActivateTime < 2000) return
        pendingSnapshot = null
        ClickCaptureManager.setWaitingForClick(false)
        isCapturing = false
        AppLog.w("已取消等待点击")
    }

    /**
     * 手动模式兜底：点击事件拿不到任何信息（source null 且无 text）时，
     * 不放弃，直接用预抓/补抓的快照扫节点树自动查找关闭按钮。
     */
    fun fallbackToAuto() {
        scope.launch {
            AppLog.i("手动模式兜底：点击事件无信息，转自动查找关闭按钮")
            val snapshot = pendingSnapshot ?: run {
                AppLog.i("无预抓快照，兜底补抓…")
                when (val r = GkdHttpClient.captureSnapshot()) {
                    is GkdHttpResult.Failure -> {
                        AppLog.e("兜底快照捕获失败: ${r.reason}")
                        isCapturing = false
                        return@launch
                    }
                    is GkdHttpResult.Success -> r.value
                }
            }
            pendingSnapshot = null
            ClickCaptureManager.setWaitingForClick(false)
            autoExecute(snapshot)
            isCapturing = false
        }
    }

    /** 等下拉面板收起、目标 App 回到前台（当前窗口不再是系统 UI 包），最多 [timeoutMs] */
    private suspend fun waitForForegroundApp(timeoutMs: Long = 3000) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val pkg = GkdBrainAccessibilityService.instance
                ?.rootInActiveWindow?.packageName?.toString()
            if (pkg != null && !isSystemUiPackage(pkg)) return
            delay(200)
        }
        AppLog.w("等待目标 App 回到前台超时，按当前窗口抓快照")
    }

    private fun isSystemUiPackage(pkg: String): Boolean =
        pkg.contains("systemui", ignoreCase = true) ||
            pkg.contains("launcher", ignoreCase = true)

    /** 无障碍服务捕获到目标点击后调用 */
    fun run(captured: CapturedNode, screen: ScreenParams) {
        val pre = pendingSnapshot
        pendingSnapshot = null
        scope.launch {
            delay(1000) // 捕获点击后延时 1 秒，等界面/弹窗状态稳定后再生成并写入规则
            AppLog.i("=== 开始生成规则链路 ===")
            AppLog.i("目标: ${captured.packageName} text=${captured.text} " +
                "viewId=${captured.viewIdResourceName} (${captured.centerX},${captured.centerY})")

            // 快照：优先用磁贴预抓的，否则点击后补抓
            val snapshot: GkdSnapshot = pre ?: run {
                AppLog.i("无预抓快照，点击后补抓…")
                when (val r = GkdHttpClient.captureSnapshot()) {
                    is GkdHttpResult.Failure -> {
                        AppLog.e("快照捕获失败: ${r.reason}")
                        isCapturing = false
                        return@launch
                    }
                    is GkdHttpResult.Success -> r.value
                }
            }
            AppLog.i("快照: appId=${snapshot.appId} 节点数=${snapshot.nodes.size}")

            execute(captured, screen, snapshot)
            isCapturing = false
        }
    }

    /** 反查节点：无坐标（source 为 null）用 text 反查，否则用坐标反查 */
    private fun locateNode(
        captured: CapturedNode,
        screen: ScreenParams,
        snapshot: GkdSnapshot,
    ): NodeMatchResult? {
        val hasCoordinate = captured.width > 0 || captured.height > 0
        if (!hasCoordinate) {
            val text = captured.text ?: return null
            return NodeLocator.locateByText(snapshot, text)
        }
        return when (val r = NodeLocator.locateFromCapture(snapshot, captured, screen)) {
            is LocateResult.NotFound -> null
            is LocateResult.Found -> r.result
        }
    }

    private suspend fun execute(captured: CapturedNode, screen: ScreenParams, snapshot: GkdSnapshot) {
        // 反查节点
        AppLog.i("步骤2/4 反查节点…")
        val locate = locateNode(captured, screen, snapshot)
        if (locate == null) {
            AppLog.w("手动反查失败，转入自动查找关闭按钮")
            autoExecute(snapshot)
            return
        }
        AppLog.i("命中节点 id=${locate.node.id} 方式=${locate.matchType} " +
            "clickable=${locate.node.attr.clickable}")

        // 生成选择器（优先级：vid → 文本 → desc/className）
        AppLog.i("步骤3/4 生成选择器…")
        val selector = when (val r = SelectorGenerator.generate(snapshot, locate.node)) {
            is SelectorResult.Failure -> {
                AppLog.e("选择器生成失败: ${r.reason}")
                return
            }
            is SelectorResult.Success -> r
        }
        AppLog.i("选择器: ${selector.selector} (${selector.kind})")
        rememberFeature(snapshot, selector.targetNode)

        // 套规则（学习模式 = 连续点击 + 学习；否则一次性点击不学习）+ 写入 + 记录历史
        writeRule(snapshot, selector.selector, captured.text ?: selector.selector, "手动")
    }

    /** 自动模式：优先用增强版快照（多窗口）查找关闭按钮，回退 GKD 快照 */
    private suspend fun autoExecute(snapshot: GkdSnapshot) {
        val enhancedSnapshot = captureEnhanced()
            ?.takeIf { it.nodes.isNotEmpty() }
            ?.let { GkdSnapshot(appId = it.appId, nodes = it.nodes) }
        if (enhancedSnapshot != null) {
            val node = autoLocateClose(enhancedSnapshot)
            if (node != null) {
                AppLog.i("增强版快照命中关闭按钮（多窗口 ${enhancedSnapshot.nodes.size} 节点）")
                generateAndWrite(enhancedSnapshot, node)
                return
            }
            AppLog.w("增强版快照未找到关闭按钮，回退 GKD 快照")
        }
        val node = autoLocateClose(snapshot)
        if (node == null) {
            AppLog.e("自动查找失败：未找到关闭按钮")
            return
        }
        generateAndWrite(snapshot, node)
    }

    /** 生成选择器并写入规则（自动模式） */
    private suspend fun generateAndWrite(snapshot: GkdSnapshot, node: SnapshotNode) {
        AppLog.i("自动命中节点 id=${node.id} vid=${node.attr.vid} " +
            "name=${node.attr.name} area=${node.attr.area}")
        val selector = when (val r = SelectorGenerator.generate(snapshot, node)) {
            is SelectorResult.Failure -> {
                AppLog.e("选择器生成失败: ${r.reason}")
                return
            }
            is SelectorResult.Success -> r
        }
        AppLog.i("选择器: ${selector.selector} (${selector.kind})")
        rememberFeature(snapshot, selector.targetNode)
        writeRule(snapshot, selector.selector, node.attr.vid ?: selector.selector)
    }

    /** 按 App 记住「广告容器 vid + 关闭按钮 vid」特征，用于后续生成带上下文选择器 */
    private fun rememberFeature(snapshot: GkdSnapshot, node: SnapshotNode) {
        val containerVid = SelectorGenerator.findAncestorVid(snapshot.nodes, node)
        AdFeatureStore.remember(snapshot.appId, containerVid, node.attr.vid)
        AppLog.i("记住广告特征: ${snapshot.appId} 容器=${containerVid ?: "无"} 关闭按钮=${node.attr.vid ?: "无"}")
    }

    /** 抓取增强版快照（九宫格多窗口扫描）并保存记录，返回快照供规则生成复用 */
    private fun captureEnhanced(): SnapshotCapture.EnhancedSnapshot? {
        val service = GkdBrainAccessibilityService.instance ?: return null
        val snap = SnapshotCapture.capture(service) ?: return null
        SnapshotRecordStore.save(snap.appId, snap.nodes)
        AppLog.i("已保存增强版快照: ${snap.appId} 节点=${snap.nodes.size}")
        return snap
    }

    /**
     * 在快照里自动查找广告的「关闭/跳过」按钮：
     * 只取 clickable 且 vid/id/text/desc 含关键词（close/exit/skip/cancel/关闭/跳过/知道了）的节点
     * （WebView 里的关闭 × 往往只有 desc/text 没有 vid），命中多个取面积最小（关闭按钮通常是小图标）。
     * 无关键词命中时返回 null（不写规则，避免误点普通组件）。
     */
    private fun autoLocateClose(snapshot: GkdSnapshot): com.gkdbrain.app.snapshot.SnapshotNode? {
        val clickable = snapshot.nodes.filter { it.attr.clickable && it.attr.area > 0 }
        if (clickable.isEmpty()) return null
        val keywords = listOf("close", "exit", "skip", "cancel", "关闭", "跳过", "知道了", "跳过广告")
        val matched = clickable.filter { n ->
            val id = n.attr.id.orEmpty()
            val vid = n.attr.vid.orEmpty()
            val text = n.attr.text.orEmpty()
            val desc = n.attr.desc.orEmpty()
            keywords.any { k ->
                id.contains(k, ignoreCase = true) || vid.contains(k, ignoreCase = true) ||
                    text.contains(k, ignoreCase = true) || desc.contains(k, ignoreCase = true)
            }
        }
        if (matched.isEmpty()) {
            AppLog.w("未找到含关闭关键词的可点击节点，跳过（避免误点普通组件）")
            return null
        }
        return matched.minByOrNull { it.attr.area }
    }

    /**
     * 套规则并写入 GKD 本地订阅。
     * 简化策略：先用原名 append；失败（名字冲突或其它错误）在名字后加「-1」再 append 一次；
     * 若 -1 仍失败则结束，不再重试、也不替换旧规则。
     */
    private suspend fun writeRule(
        snapshot: GkdSnapshot,
        selectorStr: String,
        label: String,
        prefix: String = "自动",
    ) {
        val baseName = "$prefix - ${snapshot.appId} - $label"
        val learn = LearningMode.enabled

        if (tryAppend(snapshot.appId, baseName, selectorStr, learn)) return
        AppLog.w("「$baseName」写入失败，改用「$baseName-1」重试")
        if (tryAppend(snapshot.appId, "$baseName-1", selectorStr, learn)) return
        AppLog.e("「$baseName-1」仍写入失败，本次不写入 GKD 规则")
    }

    /** 单次 append：成功则记录历史并返回 true；失败记录原因并返回 false */
    private suspend fun tryAppend(
        appId: String,
        groupName: String,
        selectorStr: String,
        learn: Boolean,
    ): Boolean {
        val group = buildGroup(groupName, selectorStr, learn)
        AppLog.i(
            if (learn) "写入 GKD「$groupName」（连续点击 0.5s 冷却，学习模式）…"
            else "写入 GKD「$groupName」（一次性点击，不学习）…",
        )
        return when (val r = GkdHttpClient.writeRule(appId = appId, group = group)) {
            is GkdHttpResult.Failure -> {
                AppLog.w("写入失败: ${r.reason}")
                false
            }
            is GkdHttpResult.Success -> {
                AppLog.i("★ 规则已写入 GKD: $appId → $groupName (key=${r.value})")
                val now = System.currentTimeMillis()
                val entry = HistoryStore.add(
                    HistoryEntry(
                        id = now,
                        appId = appId,
                        selector = selectorStr,
                        groupName = groupName,
                        groupKey = r.value,
                        state = if (learn) LearnState.PENDING else LearnState.CLICK_ONCE,
                        createdAt = now,
                    ),
                )
                afterWrite(entry, learn, "已加入历史记录")
                true
            }
        }
    }

    private fun buildGroup(groupName: String, selectorStr: String, learn: Boolean) =
        if (learn) {
            RuleJsonBuilder.buildGroup(
                groupName = groupName,
                matches = selectorStr,
                actionCdMs = 500L,
            )
        } else {
            RuleJsonBuilder.buildGroup(groupName = groupName, matches = selectorStr)
        }

    /** 写入成功后：学习模式下进入监控，否则仅记录 */
    private fun afterWrite(entry: HistoryEntry, learn: Boolean, msg: String) {
        if (learn) {
            AppLog.i("$msg，自动进入监控")
            MonitorManager.startMonitoring(entry)
        } else {
            AppLog.i("$msg（一次性点击，不学习）")
        }
    }
}
