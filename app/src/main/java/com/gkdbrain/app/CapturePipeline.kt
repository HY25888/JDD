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
                    if (autoMode) {
                        AppLog.i("★ 已激活（自动模式）：快照已抓取（${r.value.nodes.size} 节点），自动查找关闭按钮")
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

    /** 悬浮按钮触发：立即抓快照（不收起面板、不延时），进入「等待第一次点击」状态 */
    fun prepareFromButton() {
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
                    if (autoMode) {
                        AppLog.i("★ 已激活（自动模式）：快照已抓取（${result.value.nodes.size} 节点），自动查找关闭按钮")
                        autoExecute(result.value)
                        isCapturing = false
                    } else {
                        ClickCaptureManager.setWaitingForClick(true)
                        lastActivateTime = System.currentTimeMillis()
                        AppLog.i("★ 已激活（悬浮按钮）：快照已抓取（${result.value.nodes.size} 节点），6 秒内点击目标控件")
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
        writeRule(snapshot, selector.selector, captured.text ?: selector.selector)
    }

    /** 自动模式：在快照里查找关闭/跳过按钮并生成规则 */
    private suspend fun autoExecute(snapshot: GkdSnapshot) {
        val node = autoLocateClose(snapshot)
        if (node == null) {
            AppLog.e("自动查找失败：无 clickable 节点")
            return
        }
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

    /**
     * 在快照里自动查找广告的「关闭/跳过」按钮：
     * 优先取 clickable 且 vid/id 含关键词（close/exit/skip/cancel/关闭/跳过/知道了）的节点，
     * 命中多个取面积最小（关闭按钮通常是小图标）；无关键词时退化为所有 clickable 里面积最小的。
     */
    private fun autoLocateClose(snapshot: GkdSnapshot): com.gkdbrain.app.snapshot.SnapshotNode? {
        val clickable = snapshot.nodes.filter { it.attr.clickable }
        if (clickable.isEmpty()) return null
        val keywords = listOf("close", "exit", "skip", "cancel", "关闭", "跳过", "知道了", "跳过广告")
        val matched = clickable.filter { n ->
            val id = n.attr.id.orEmpty()
            val vid = n.attr.vid.orEmpty()
            keywords.any { k -> id.contains(k, ignoreCase = true) || vid.contains(k, ignoreCase = true) }
        }
        val candidates = if (matched.isNotEmpty()) matched else clickable
        return candidates.minByOrNull { it.attr.area }
    }

    /** 套规则并写入 GKD 本地订阅；同名更新（replace），否则新增（append），name 冲突时加 -1 后缀重试 */
    private suspend fun writeRule(snapshot: GkdSnapshot, selectorStr: String, label: String) {
        val baseName = "自动 - $label"
        val learn = LearningMode.enabled

        val existing = HistoryStore.findByGroupName(snapshot.appId, baseName)
        if (existing != null) {
            AppLog.i(
                if (learn) "更新 GKD 同名规则组（连续点击：0.5s 冷却，学习模式）…"
                else "更新 GKD 同名规则组（一次性点击，不学习）…",
            )
            val group = buildGroup(baseName, selectorStr, learn)
            when (val r = GkdHttpClient.replaceRuleGroup(snapshot.appId, existing.groupKey, group)) {
                is GkdHttpResult.Failure ->
                    AppLog.e("更新失败: ${r.reason}，改为新增规则")
                is GkdHttpResult.Success -> {
                    AppLog.i("★ 规则已更新 GKD: ${snapshot.appId} → $baseName (key=${existing.groupKey})")
                    val entry = existing.copy(
                        selector = selectorStr,
                        state = if (learn) LearnState.PENDING else LearnState.CLICK_ONCE,
                        delayMs = null,
                    )
                    HistoryStore.update(entry)
                    afterWrite(entry, learn, "已更新历史记录")
                    return
                }
            }
        }

        // 新增（append）；name 冲突时加 -1/-2… 后缀重试
        var groupName = baseName
        var suffix = 0
        while (true) {
            val group = buildGroup(groupName, selectorStr, learn)
            AppLog.i(
                if (learn) "写入 GKD 本地订阅（连续点击：0.5s 冷却，学习模式）…"
                else "写入 GKD 本地订阅（一次性点击，不学习）…",
            )
            when (val r = GkdHttpClient.writeRule(appId = snapshot.appId, group = group)) {
                is GkdHttpResult.Failure -> {
                    if (isNameDuplicate(r.reason)) {
                        suffix++
                        groupName = "$baseName-$suffix"
                        AppLog.w("同名规则已存在，改用「$groupName」重试")
                        continue
                    }
                    AppLog.e("写入失败: ${r.reason}")
                    return
                }
                is GkdHttpResult.Success -> {
                    AppLog.i("★ 规则已写入 GKD: ${snapshot.appId} → $groupName (key=${r.value})")
                    val now = System.currentTimeMillis()
                    val entry = HistoryStore.add(
                        HistoryEntry(
                            id = now,
                            appId = snapshot.appId,
                            selector = selectorStr,
                            groupName = groupName,
                            groupKey = r.value,
                            state = if (learn) LearnState.PENDING else LearnState.CLICK_ONCE,
                            createdAt = now,
                        ),
                    )
                    afterWrite(entry, learn, "已加入历史记录")
                    return
                }
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

    private fun isNameDuplicate(reason: String): Boolean =
        reason.contains("RuleNameDuplicate", ignoreCase = true) ||
            reason.contains("同名", ignoreCase = true)

    /** 写入/更新成功后：学习模式下进入监控，否则仅记录 */
    private fun afterWrite(entry: HistoryEntry, learn: Boolean, msg: String) {
        if (learn) {
            AppLog.i("$msg，自动进入监控")
            MonitorManager.startMonitoring(entry)
        } else {
            AppLog.i("$msg（一次性点击，不学习）")
        }
    }
}
