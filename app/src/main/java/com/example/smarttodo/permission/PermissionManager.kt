package com.example.smarttodo.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 权限工具类：只做检测 / 申请 / 跳转，不含任何 UI。
 * - 运行时权限：requestPermissions
 * - 特殊权限（无障碍、悬浮窗、所有文件、电池优化）：只能跳专属设置页
 * 跳转后会 arm 一次无障碍代点，替用户把开关和确认框点掉。
 */
object PermissionManager {

    const val REQ_NOTIFICATION = 2001
    const val REQ_LEGACY_STORAGE = 2002

    data class Status(val item: PermItem, val granted: Boolean)

    fun statuses(c: Context): List<Status> = PermissionSpecs.ALL.map { Status(it, it.check(c)) }

    fun isGranted(c: Context, item: PermItem): Boolean = item.check(c)

    fun grantedCount(c: Context): Int = PermissionSpecs.ALL.count { it.check(c) }

    /** 统一入口：能代码申请就弹系统框，否则跳设置页 */
    fun request(a: Activity, item: PermItem): Boolean {
        if (item.kind == PermKind.RUNTIME) {
            if (item.key == "notification" && Build.VERSION.SDK_INT >= 33) {
                a.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
                return true
            }
            if (item.key == "all_files" && Build.VERSION.SDK_INT < 30) {
                a.requestPermissions(
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    REQ_LEGACY_STORAGE
                )
                return true
            }
        }
        return jump(a, item)
    }

    /** 跳到该权限「专属」设置页（不是通用设置），并预置无障碍代点目标 */
    fun jump(c: Context, item: PermItem): Boolean {
        val words = item.autoWords ?: PermTargets.forPermission(c, item.key)
        PermAutoClickService.armGuide(words, item.autoSelfRow, item.autoSelfSwitch)
        return try {
            c.startActivity(item.jump(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            try {
                c.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${c.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            } catch (e2: Exception) {
                false
            }
        }
    }

    /** 该权限是否可以由代码直接弹框申请（UI 用来区分按钮文案） */
    fun canRequestDirectly(item: PermItem): Boolean =
        item.kind == PermKind.RUNTIME && Build.VERSION.SDK_INT >= 33

    /** 全部完成后收起代点，避免无障碍继续动作 */
    fun stopAutoClick() = PermAutoClickService.disarm()
}
