package com.example.smarttodo.permission

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.smarttodo.NotifIngest

/**
 * 权限引导用的无障碍服务。
 * 两件事：
 * 1) 没有通知使用权时，靠无障碍事件兜底读取通知文字（保持原有能力）；
 * 2) 从「权限中心」跳转后，在系统设置页里替你点开关 / 点掉确认弹窗。
 *
 * 只在 arm() 之后的 90 秒内、且当前页面属于系统设置时动作，其余时间只做通知兜底。
 */
class PermAutoClickService : AccessibilityService() {

    internal val handler = Handler(Looper.getMainLooper())
    private var clickedAt = 0L
    private var clickedSwitch = false
    private var wentBack = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        appContext = applicationContext
        handler.removeCallbacks(poll)
        if (armed) handler.post(poll)
    }

    override fun onInterrupt() {}

    /** 系统关闭无障碍 / 服务被回收时会走这里，及时清掉"在线"标记。 */
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        handler.removeCallbacks(poll)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacks(poll)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            try {
                captureNotification(event)
            } catch (t: Throwable) {
                Log.w(TAG, "capture", t)
            }
        }
    }

    private fun captureNotification(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        var title = ""
        var body = ""
        val texts = event.text
        if (texts != null && texts.isNotEmpty()) {
            title = texts[0].toString()
            if (texts.size > 1) body = texts[1].toString()
        }
        val data = event.parcelableData
        if (data is android.app.Notification) {
            val ex = data.extras
            if (ex != null) {
                var t = ex.getCharSequence(android.app.Notification.EXTRA_TITLE)
                var b = ex.getCharSequence(android.app.Notification.EXTRA_TEXT)
                if (b == null) b = ex.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)
                if (b == null) b = ex.getCharSequence(android.app.Notification.EXTRA_SUMMARY_TEXT)
                if (t != null) title = t.toString()
                if (b != null) body = b.toString()
            }
        }
        NotifIngest.ingest(this, pkg, title, body, event.eventTime)
    }

    internal val poll = object : Runnable {
        override fun run() {
            if (!armed) return
            if (System.currentTimeMillis() > deadline) {
                armed = false
                return
            }
            try {
                tick()
            } catch (t: Throwable) {
                Log.w(TAG, "tick", t)
            }
            handler.postDelayed(this, 600)
        }
    }

    private fun tick() {
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: ""
        if (!isSettingsLike(pkg)) return
        if (wentBack) {
            armed = false
            return
        }

        // 已经点过开关：接下来只负责点掉确认弹窗，然后返回
        if (clickedSwitch) {
            val ok = findByText(root, *texts)
            if (ok != null && ok.isEnabled) click(ok)
            if (System.currentTimeMillis() - clickedAt > 2200) finish()
            return
        }

        if (clickSelfSwitch) {
            val sw = findSwitchForSelf(root)
            if (sw != null) {
                if (!sw.isChecked) {
                    click(sw)
                    clickedAt = System.currentTimeMillis()
                    clickedSwitch = true
                } else {
                    finish()
                }
                return
            }
        }

        if (clickSelfRow) {
            val row = findRowForSelf(root)
            if (row != null) {
                click(row)
                clickedAt = System.currentTimeMillis()
                clickedSwitch = true
                return
            }
        }

        val target = findByText(root, *texts)
        if (target != null) {
            click(target)
            clickedAt = System.currentTimeMillis()
            clickedSwitch = true
        }
    }

    private fun finish() {
        performGlobalAction(GLOBAL_ACTION_BACK)
        wentBack = true
        armed = false
    }

    private fun isSettingsLike(pkg: String): Boolean {
        return pkg == "com.android.settings" || pkg.endsWith(".settings") ||
                pkg.startsWith("com.android.permissioncontroller") || pkg == "android" ||
                pkg.startsWith("com.miui.securitycenter") || pkg.startsWith("com.huawei.systemmanager") ||
                pkg.startsWith("com.coloros") || pkg.startsWith("com.oppo") ||
                pkg.startsWith("com.vivo") || pkg.startsWith("com.iqoo")
    }

    private fun findSwitchForSelf(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val label = selfLabel()
        val all = ArrayList<AccessibilityNodeInfo>()
        collect(root, all)
        for (n in all) {
            val s = n.text?.toString()?.trim() ?: continue
            if (s != label && !s.contains(label)) continue
            var p: AccessibilityNodeInfo? = n
            for (i in 0 until 8) {
                val sw = findDescendantSwitch(p)
                if (sw != null) return sw
                p = p?.parent
            }
        }
        val switches = all.filter { it.isCheckable || isSwitchClass(it.className?.toString()) }
        return if (switches.size == 1) switches[0] else null
    }

    private fun findRowForSelf(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val label = selfLabel()
        val all = ArrayList<AccessibilityNodeInfo>()
        collect(root, all)
        for (n in all) {
            val s = n.text?.toString()?.trim() ?: continue
            if (s != label && !s.contains(label)) continue
            var p: AccessibilityNodeInfo? = n
            for (i in 0 until 6) {
                if (p != null && p.isClickable && p != root) return p
                p = p?.parent
            }
        }
        return null
    }

    private fun isSwitchClass(cn: String?): Boolean {
        if (cn == null) return false
        return cn.contains("Switch") || cn.contains("CheckBox") || cn.contains("Toggle")
    }

    private fun selfLabel(): String {
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    private fun findDescendantSwitch(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        val list = ArrayList<AccessibilityNodeInfo>()
        collect(n, list)
        for (x in list) {
            if (x.isCheckable) return x
            if (isSwitchClass(x.className?.toString())) return x
            val id = x.viewIdResourceName
            if (id != null && id.endsWith("switch_widget")) return x
        }
        return null
    }

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (n == null || out.size > 400) return
        out.add(n)
        for (i in 0 until n.childCount) collect(n.getChild(i), out)
    }

    private fun findByText(n: AccessibilityNodeInfo, vararg wants: String): AccessibilityNodeInfo? {
        if (wants.isEmpty()) return null
        val all = ArrayList<AccessibilityNodeInfo>()
        collect(n, all)
        for (x in all) {
            val s = x.text?.toString()?.trim() ?: continue
            if (TextUtils.isEmpty(s)) continue
            if (wants.any { s == it }) return x
        }
        for (x in all) {
            val s = x.text?.toString()?.trim() ?: continue
            if (TextUtils.isEmpty(s)) continue
            if (wants.any { s.contains(it) }) return x
        }
        return null
    }

    private fun click(n: AccessibilityNodeInfo) {
        var cur: AccessibilityNodeInfo? = n
        for (i in 0 until 4) {
            if (cur != null && cur.isClickable) {
                cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
            cur = cur?.parent
        }
    }

    companion object {
        private const val TAG = "SmartTodoGrant"

        /** 通知使用权开关（老的授权流程仍在用） */
        const val TARGET_NOTIF = 1
        /** 电池优化弹窗 */
        const val TARGET_BATTERY = 2

        @Volatile private var instance: PermAutoClickService? = null
        /** 供静态 arm(...) 读取文案资源；无障碍未连接时为空。 */
        @Volatile private var appContext: android.content.Context? = null
        @Volatile private var armed = false
        @Volatile private var deadline = 0L
        @Volatile private var texts: Array<String> = emptyArray()
        @Volatile private var clickSelfRow = false
        @Volatile private var clickSelfSwitch = false

        private fun armInternal(
            words: Array<String>,
            selfRow: Boolean,
            selfSwitch: Boolean
        ) {
            texts = words
            clickSelfRow = selfRow
            clickSelfSwitch = selfSwitch
            deadline = System.currentTimeMillis() + 90_000L
            armed = true
            instance?.let {
                it.clickedSwitch = false
                it.wentBack = false
                it.handler.removeCallbacks(it.poll)
                it.handler.post(it.poll)
            }
        }

        /** 供原有 Java 授权流程调用；文案统一取自 PermTargets（XML 配置表）。 */
        @JvmStatic
        fun arm(target: Int) {
            arm(appContext, target)
        }

        /** 带 Context 版本：无障碍尚未连接时也能拿到文案资源。 */
        @JvmStatic
        fun arm(c: Context?, target: Int) {
            when (target) {
                TARGET_NOTIF -> armInternal(
                    c?.let { PermTargets.notifSwitch(it) } ?: emptyArray(), false, true)
                TARGET_BATTERY -> armInternal(
                    c?.let { PermTargets.battery(it) } ?: emptyArray(), false, false)
                else -> armInternal(
                    c?.let { PermTargets.confirm(it) } ?: emptyArray(), false, false)
            }
        }

        /** 权限中心：按文字找，可选先点本应用那一行/开关 */
        @JvmStatic
        @JvmOverloads
        fun armGuide(words: Array<String>, selfRow: Boolean = false, selfSwitch: Boolean = false) {
            armInternal(words, selfRow, selfSwitch)
        }

        @JvmStatic
        fun disarm() {
            armed = false
        }

        @JvmStatic
        fun isEnabled(c: Context): Boolean {
            appContext = c.applicationContext
            val s = Settings.Secure.getString(c.contentResolver, "enabled_accessibility_services") ?: return false
            val long = c.packageName + "/" + PermAutoClickService::class.java.name
            val short = c.packageName + "/." + PermAutoClickService::class.java.simpleName
            return s.split(':').any { it.equals(long, true) || it.equals(short, true) }
        }

        /**
         * 开关还开着、但服务实例已被系统回收（常见于国产 ROM 清理后台）。
         * 首页用它提示"无障碍已掉线"，引导用户重新开启。
         */
        @JvmStatic
        fun isConnected(): Boolean = instance != null
    }
}
