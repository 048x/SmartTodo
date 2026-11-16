package com.example.smarttodo.permission

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.app.NotificationManager
import android.Manifest

enum class PermKind {
    /** 普通运行时权限，可以直接 requestPermissions */
    RUNTIME,
    /** 特殊权限，只能跳设置页，代码申请不了 */
    SPECIAL,
    /** 无障碍服务，必须用户手动开启 */
    ACCESSIBILITY
}

/**
 * 一条权限的描述：怎么查、怎么跳、跳过去之后无障碍该点哪些字。
 * 与 UI 完全解耦，新增权限只改这个列表。
 */
data class PermItem(
    val key: String,
    val title: String,
    val desc: String,
    val kind: PermKind,
    val check: (Context) -> Boolean,
    val jump: (Context) -> Intent,
    /** null 表示使用 PermTargets 里按 key 配置的默认词表，避免业务代码硬编码文案。 */
    val autoWords: Array<String>? = null,
    val autoSelfRow: Boolean = false,
    val autoSelfSwitch: Boolean = false
)

object PermissionSpecs {

    private const val TAG = "smarttodo-perm"

    fun isAccessibilityOn(c: Context): Boolean = PermAutoClickService.isEnabled(c)

    private fun canDrawOverlays(c: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(c) else true

    private fun hasAllFiles(c: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager()
        }
        return c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun batteryIgnored(c: Context): Boolean {
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(c.packageName)
    }

    private fun notificationsOn(c: Context): Boolean {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.areNotificationsEnabled()
    }

    private fun pkgUri(c: Context) = Uri.parse("package:${c.packageName}")

    val ALL: List<PermItem> = listOf(
        PermItem(
            key = "accessibility",
            title = "无障碍服务",
            desc = "用于自动读取群消息里的通知文字，并在你授权时替你点开系统设置里的开关。不开启也能手动录入待办。",
            kind = PermKind.ACCESSIBILITY,
            check = { c -> isAccessibilityOn(c) },
            jump = { _ ->
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            },
            autoSelfRow = true
        ),
        PermItem(
            key = "overlay",
            title = "显示在其他应用上层",
            desc = "用于在桌面/微信之上显示悬浮提醒（今日到期、逾期提醒）。跳转后若出现开关，会自动帮你打开。",
            kind = PermKind.SPECIAL,
            check = { c -> canDrawOverlays(c) },
            jump = { c ->
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri(c))
            },
            autoSelfSwitch = true
        ),
        PermItem(
            key = "all_files",
            title = "所有文件访问权限",
            desc = "用于导出/备份待办数据到本地文件。若只想用基础功能，可以不授权。",
            kind = PermKind.SPECIAL,
            check = { c -> hasAllFiles(c) },
            jump = { c ->
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkgUri(c))
                } else {
                    Intent(Settings.ACTION_SETTINGS)
                }
                intent
            }
        ),
        PermItem(
            key = "battery",
            title = "忽略电池优化",
            desc = "防止系统在后台杀掉待办监听，导致群消息漏抓。加入白名单后耗电略有增加。",
            kind = PermKind.SPECIAL,
            check = { c -> batteryIgnored(c) },
            jump = { c ->
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri(c))
            }
        ),
        PermItem(
            key = "notification",
            title = "通知权限",
            desc = "Android 13 起发通知需要单独授权，用于提醒「今天到期」的待办。属于运行时权限，可直接弹系统对话框申请。",
            kind = PermKind.RUNTIME,
            check = { c -> notificationsOn(c) },
            jump = { c ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName)
            }
        )
    )

    fun find(key: String): PermItem? = ALL.firstOrNull { it.key == key }
}
