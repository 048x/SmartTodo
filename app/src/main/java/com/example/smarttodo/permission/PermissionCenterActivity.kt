package com.example.smarttodo.permission

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.smarttodo.Ui

/**
 * 权限中心：每项单独检测、单独说明、单独跳转。
 * onResume 回来自动重新检测并刷新状态。
 */
class PermissionCenterActivity : Activity() {

    private var dark = false
    private var bg = 0
    private var cardBg = 0
    private var textC = 0
    private var subC = 0
    private var okC = 0
    private var warnC = 0
    private var lineC = 0
    private var primary = 0

    private val rows = ArrayList<Row>()
    private lateinit var summary: TextView

    private inner class Row(val item: PermItem) {
        lateinit var state: TextView
        lateinit var btn: TextView
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dark = Ui.isDark(this)
        bg = if (dark) 0xFF0F1116.toInt() else 0xFFF3F5FA.toInt()
        cardBg = if (dark) 0xFF181B22.toInt() else Color.WHITE
        textC = if (dark) 0xFFECEEF4.toInt() else 0xFF1B2130.toInt()
        subC = if (dark) 0xFF98A0B0.toInt() else 0xFF6B7385.toInt()
        okC = if (dark) 0xFF5FD08A.toInt() else 0xFF16A34A.toInt()
        warnC = if (dark) 0xFFF0915B.toInt() else 0xFFC2410C.toInt()
        lineC = if (dark) 0xFF242833.toInt() else 0xFFE7EAF2.toInt()
        primary = if (dark) 0xFF7C95FF.toInt() else 0xFF2F6BFF.toInt()
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
        Toast.makeText(this, "权限状态已更新", Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun round(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        // 顶栏
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(10))
        }
        val back = TextView(this).apply {
            text = "‹ 返回"
            textSize = 15f
            setTextColor(subC)
            setPadding(0, dp(6), dp(16), dp(6))
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = "权限中心"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textC)
        }
        head.addView(back)
        head.addView(title)
        root.addView(head)

        summary = TextView(this).apply {
            textSize = 12f
            setTextColor(subC)
            setPadding(dp(20), 0, dp(20), dp(10))
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        root.addView(summary)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(16))
        }
        PermissionSpecs.ALL.forEach { item -> list.addView(buildCard(item)) }
        scroll.addView(list)
        root.addView(scroll)

        val bottom = TextView(this).apply {
            text = "一键补齐（逐个跳转）"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = round(primary, 14)
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener { oneTap() }
        }
        val bottomWrap = LinearLayout(this).apply {
            setPadding(dp(16), dp(8), dp(16), dp(18))
        }
        bottomWrap.addView(
            bottom,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(bottomWrap)

        setContentView(root)
    }

    private fun buildCard(item: PermItem): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = round(cardBg, 16)
            setOnClickListener { onCardClick(item) }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(10)
        card.layoutParams = lp

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val t = TextView(this).apply {
            text = item.title
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textC)
        }
        val d = TextView(this).apply {
            text = item.desc
            textSize = 11.5f
            setTextColor(subC)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(5), 0, 0)
        }
        col.addView(t)
        col.addView(d)
        card.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, 0, 0)
        }
        val state = TextView(this).apply { textSize = 11f }
        val btn = TextView(this).apply {
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        right.addView(state)
        val blp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        blp.topMargin = dp(6)
        right.addView(btn, blp)
        card.addView(right)

        rows.add(Row(item).also {
            it.state = state
            it.btn = btn
        })
        return card
    }

    private fun refresh() {
        var granted = 0
        for (r in rows) {
            val on = r.item.check(this)
            if (on) granted++
            r.state.text = if (on) "已开启" else "未开启"
            r.state.setTextColor(if (on) okC else warnC)
            r.btn.text = if (on) "已完成" else "去设置"
            r.btn.setTextColor(if (on) okC else primary)
            r.btn.background = round(if (on) withAlpha(okC, 0.12f) else withAlpha(primary, 0.12f), 9)
        }
        summary.text = "已开启 $granted / ${PermissionSpecs.ALL.size} 项。未开启的项点一下会先说明用途，再跳到该权限专属设置页。\n" +
                "无障碍开关必须由你手动打开一次，其余项跳转后我会自动帮你点。\n" +
                "若「无障碍」开关是灰色点不动（Android 13+ 侧载限制）：设置 → 应用 → 智能待办 → 右上角「⋮」→「允许受限制的设置」，返回后再打开。"
        if (granted == PermissionSpecs.ALL.size) PermissionManager.stopAutoClick()
    }

    private fun withAlpha(color: Int, a: Float): Int =
        Color.argb((a * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    private fun onCardClick(item: PermItem) {
        if (item.check(this)) {
            Toast.makeText(this, "${item.title} 已开启", Toast.LENGTH_SHORT).show()
            return
        }
        showExplain(item)
    }

    private fun showExplain(item: PermItem) {
        val msg = StringBuilder(item.desc)
        msg.append("\n\n")
        msg.append(
            when (item.kind) {
                PermKind.RUNTIME -> "这是普通权限，会直接弹系统授权框。"
                PermKind.ACCESSIBILITY -> "无障碍属于特殊权限，只能跳到系统设置手动开，代码无法直接申请。"
                PermKind.SPECIAL -> "这是特殊权限，只能跳到专属设置页由你确认。"
            }
        )
        com.example.smarttodo.Ui.dialog(this)
            .setTitle("开启「${item.title}」")
            .setMessage(msg.toString())
            .setNegativeButton("以后再说", null)
            .setPositiveButton("去设置") { _, _ -> open(item) }
            .show()
    }

    private fun open(item: PermItem) {
        val ok = PermissionManager.request(this, item)
        if (!ok) {
            Toast.makeText(this, "打不开该设置页，请手动前往系统设置", Toast.LENGTH_LONG).show()
            return
        }
        if (item.kind != PermKind.RUNTIME) {
            Toast.makeText(this, "已跳转，回来后会自动刷新状态", Toast.LENGTH_SHORT).show()
        }
    }

    private fun oneTap() {
        PermissionManager.stopAutoClick()
        val next = PermissionSpecs.ALL.firstOrNull { !it.check(this) }
        if (next == null) {
            Toast.makeText(this, "全部权限已开启", Toast.LENGTH_SHORT).show()
            return
        }
        showExplain(next)
    }
}
