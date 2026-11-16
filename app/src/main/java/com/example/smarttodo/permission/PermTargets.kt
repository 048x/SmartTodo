package com.example.smarttodo.permission

import android.content.Context
import android.os.Build

import com.example.smarttodo.R

/**
 * 无障碍代点目标文字的统一配置入口。
 *
 * 文案全部来自 res/values/perm_targets.xml：
 * - 适配系统语言：新增 values-en / values-xx 即可翻译；
 * - 适配 ROM：在 perm_targets.xml 里加 *_extra 词表，这里只维护“厂商 → 词表”的映射；
 * - 业务代码不再出现任何具体按钮文字。
 */
object PermTargets {

    fun confirm(c: Context): Array<String> =
        withRom(c, c.resources.getStringArray(R.array.perm_confirm_words))

    fun notifSwitch(c: Context): Array<String> =
        withRom(c, c.resources.getStringArray(R.array.perm_notif_switch_words))

    fun battery(c: Context): Array<String> =
        withRom(c, c.resources.getStringArray(R.array.perm_battery_words))

    fun allFiles(c: Context): Array<String> =
        withRom(c, c.resources.getStringArray(R.array.perm_all_files_words))

    fun overlay(c: Context): Array<String> =
        withRom(c, c.resources.getStringArray(R.array.perm_overlay_words))

    /** 按权限 key 取默认词表，供 PermissionSpecs 未显式指定时使用。 */
    fun forPermission(c: Context, key: String): Array<String> = when (key) {
        "notification" -> notifSwitch(c)
        "battery" -> battery(c)
        "all_files" -> allFiles(c)
        "overlay" -> overlay(c)
        else -> confirm(c)
    }

    /** 厂商差异：在基础词表后追加 ROM 专属文案，不改动基础词表。 */
    private fun withRom(c: Context, base: Array<String>): Array<String> {
        val extraRes = when (Build.MANUFACTURER.lowercase()) {
            "xiaomi", "redmi", "poco" -> R.array.perm_rom_xiaomi_extra
            "huawei", "honor" -> R.array.perm_rom_huawei_extra
            "oppo", "realme", "oneplus" -> R.array.perm_rom_oppo_extra
            "vivo", "iqoo" -> R.array.perm_rom_vivo_extra
            else -> 0
        }
        if (extraRes == 0) return base
        return (base + c.resources.getStringArray(extraRes)).distinct().toTypedArray()
    }
}
