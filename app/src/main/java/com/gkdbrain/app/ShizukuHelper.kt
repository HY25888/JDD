package com.gkdbrain.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.FileInputStream
import kotlin.concurrent.thread

/**
 * Shizuku / ADB 特权封装：检测、授权，并通过 WRITE_SECURE_SETTINGS 静默重开无障碍。
 *
 * 原理（与 GKD 一致）：
 * - 通过 Shizuku 执行 pm grant 授予 WRITE_SECURE_SETTINGS，一次性授予。
 * - 授予后 App 自身即可直接写 Settings.Secure（无需 Shizuku 常驻），用于静默重开无障碍。
 */
object ShizukuHelper {

    private const val REQUEST_CODE = 1000
    private const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"

    /** Shizuku 服务是否运行 */
    fun isServiceAvailable(): Boolean = Shizuku.pingBinder()

    /** 是否已授权本 App 使用 Shizuku */
    fun isShizukuGranted(): Boolean =
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    /** 是否已持有 WRITE_SECURE_SETTINGS 权限（pm grant 后持久生效） */
    fun hasWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** 请求 Shizuku 授权（未授权时） */
    fun requestShizukuPermission(onResult: (Boolean) -> Unit) {
        if (isShizukuGranted()) {
            onResult(true)
            return
        }
        var listener: Shizuku.OnRequestPermissionResultListener? = null
        listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQUEST_CODE) {
                listener?.let { Shizuku.removeRequestPermissionResultListener(it) }
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        Shizuku.requestPermission(REQUEST_CODE)
    }

    /**
     * 通过 Shizuku 运行 shell 命令 `pm grant <pkg> WRITE_SECURE_SETTINGS`。
     * 与 GKD 用 ADB 授权的路径完全一致，绕开隐藏 API 反射，targetSdk 保持 35 不受影响。
     * 回调第二参数为失败原因，便于直接展示给用户定位。
     */
    fun grantWriteSecureSettings(context: Context, onResult: (Boolean, String) -> Unit) {
        val pkg = context.packageName
        thread {
            val (granted, detail) =
                try {
                    AppLog.i("开始授予静默重开权限")
                    val binder = Shizuku.getBinder()
                    if (binder == null) {
                        Pair(false, "Shizuku 服务未连接")
                    } else {
                        val service = IShizukuService.Stub.asInterface(binder)
                        val cmd = "pm grant $pkg $WRITE_SECURE_SETTINGS"
                        val proc = service.newProcess(
                            arrayOf("/system/bin/sh", "-c", cmd),
                            null,
                            null,
                        )
                        val exit = proc.waitFor()
                        val out = FileInputStream(proc.inputStream.fileDescriptor)
                            .use { it.readBytes().toString(Charsets.UTF_8).trim() }
                        val err = FileInputStream(proc.errorStream.fileDescriptor)
                            .use { it.readBytes().toString(Charsets.UTF_8).trim() }
                        val ok = hasWriteSecureSettings(context)
                        AppLog.i(
                            "pm grant 退出码 $exit，权限${if (ok) "已生效" else "未生效"}" +
                                (if (out.isNotEmpty()) "，stdout: $out" else "") +
                                (if (err.isNotEmpty()) "，stderr: $err" else ""),
                        )
                        if (ok) {
                            Pair(true, "")
                        } else {
                            Pair(
                                false,
                                if (err.isNotEmpty()) err
                                else if (out.isNotEmpty()) out
                                else "命令已执行但权限未生效(退出码 $exit)",
                            )
                        }
                    }
                } catch (t: Throwable) {
                    AppLog.e("静默重开权限授权失败: ${t.javaClass.name}: ${t.message}")
                    Pair(false, "${t.javaClass.simpleName}: ${t.message}")
                }
            onResult(granted, detail)
        }
    }

    /**
     * 静默重开无障碍服务（需已持有 WRITE_SECURE_SETTINGS）。
     * 字段里已有本服务则不干预（避免打断系统正在进行的绑定），没有才加回。
     */
    fun restartAccessibility(context: Context): Boolean {
        if (!hasWriteSecureSettings(context)) return false
        val component = ComponentName(context, GkdBrainAccessibilityService::class.java)
        val flatten = component.flattenToString()
        return try {
            Settings.Secure.putInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                1,
            )
            val current =
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ).orEmpty()
            val names = current.split(':').filter { it.isNotBlank() }.toMutableSet()
            if (names.contains(flatten)) {
                return true
            }
            names.add(flatten)
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                names.joinToString(":"),
            )
            true
        } catch (e: Exception) {
            false
        }
    }
}
