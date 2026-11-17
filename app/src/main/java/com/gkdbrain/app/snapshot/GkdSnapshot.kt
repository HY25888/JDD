package com.gkdbrain.app.snapshot

import kotlinx.serialization.Serializable

/**
 * GKD 快照顶层结构（对应 GKD v1.13.x 的 ComplexSnapshot）。
 *
 * 快照是一个 zip 文件，内含：
 * - `{id}.json`：本结构序列化后的 JSON（节点在 nodes 数组）
 * - `{id}.webp` 或 `{id}.png`：截图
 *
 * 字段名与 GKD 源码保持一致，避免臆造。
 */
@Serializable
data class GkdSnapshot(
    val id: Long = 0,
    val appId: String = "",
    val activityId: String? = null,
    val screenHeight: Int = 0,
    val screenWidth: Int = 0,
    val isLandscape: Boolean = false,
    val appInfo: AppInfo? = null,
    val gkdAppInfo: AppInfo? = null,
    val device: DeviceInfo? = null,
    val nodes: List<SnapshotNode> = emptyList(),
)

/** 目标应用信息（抓取快照时所在的 App） */
@Serializable
data class AppInfo(
    val id: String = "",
    val name: String = "",
    val versionCode: Int = 0,
    val versionName: String? = null,
    val isSystem: Boolean = false,
    val mtime: Long = 0,
    val hidden: Boolean = false,
    val userId: Int = 0,
)

/** 抓取快照时的设备信息（模块3 用于与实时屏幕参数做坐标一致性比对） */
@Serializable
data class DeviceInfo(
    val device: String = "",
    val model: String = "",
    val manufacturer: String = "",
    val brand: String = "",
    val sdkInt: Int = 0,
    val release: String = "",
)
