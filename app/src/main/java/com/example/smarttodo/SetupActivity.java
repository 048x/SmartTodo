package com.example.smarttodo;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.smarttodo.permission.PermAutoClickService;

/** 一键授权页：把四个系统开关集中到一屏，能自动点的就自动点。 */
public class SetupActivity extends BaseActivity {

    private static final int BG = 0xFFF4F6FA;
    private static final int CARD = 0xFFFFFFFF;
    private static final int TEXT = 0xFF1B1F2A;
    private static final int SUB = 0xFF8A90A0;
    private static final int PRIMARY = 0xFF3A57D6;
    private static final int OKC = 0xFF16A34A;
    private static final int WARNC = 0xFFC2410C;

    private LinearLayout content;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int p = dp(18);
        content.setPadding(p, dp(22), p, dp(30));
        sv.addView(content, new ViewGroup.LayoutParams(-1, -2));
        setContentView(sv);
        buildUi();
    }

    private void buildUi() {
        content.removeAllViews();

        TextView title = text("一键授权", 24, TEXT, true);
        content.addView(title);

        TextView sub = text("第一步只能在系统里手动打开无障碍——Android 的硬限制，任何 App 都绕不过。"
                + "打开之后，剩下的开关我都能替你点。", 13, SUB, false);
        sub.setPadding(0, dp(8), 0, dp(18));
        sub.setLineSpacing(dp(3), 1f);
        content.addView(sub);

        TextView one = new TextView(this);
        boolean allOk = SetupHelper.hasNotifAccess(this)
                && SetupHelper.hasAccessibility(this)
                && SetupHelper.ignoreBattery(this);
        one.setText(allOk ? "已全部就绪" : "一键授权（自动点开关）");
        one.setTextSize(15);
        one.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        one.setTextColor(Color.WHITE);
        one.setGravity(Gravity.CENTER);
        one.setPadding(0, dp(15), 0, dp(15));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(allOk ? OKC : PRIMARY);
        bg.setCornerRadius(dp(14));
        one.setBackground(bg);
        one.setOnClickListener(v -> oneTap());
        content.addView(one, new LinearLayout.LayoutParams(-1, -2));

        TextView note = text("如果无障碍的开关是灰的、点不动（Android 13+ 侧载限制）：\n"
                + "设置 → 应用 → 智能待办 → 右上角「⋮」→「允许受限制的设置」，返回后再打开无障碍。\n"
                + "部分 ROM 还要在「应用信息 → 权限」里允许「受限设置」。", 11, SUB, false);
        note.setPadding(0, dp(10), 0, dp(6));
        note.setLineSpacing(dp(3), 1f);
        content.addView(note);

        addRow("无障碍服务", "必须手动开一次 · 之后本应用可代你点开关，也能直接抓通知",
                SetupHelper.hasAccessibility(this), "去开启",
                () -> startSafely(SetupHelper.accessibilityIntent()));

        addRow("通知使用权", "可选加强 · 不开也能靠无障碍抓通知",
                SetupHelper.hasNotifAccess(this), "去授权",
                () -> {
                    PermAutoClickService.arm(this, PermAutoClickService.TARGET_NOTIF);
                    startSafely(SetupHelper.notifIntent());
                });

        addRow("电池优化白名单", "锁屏后不被限制后台",
                SetupHelper.ignoreBattery(this), "去设置",
                () -> {
                    PermAutoClickService.arm(this, PermAutoClickService.TARGET_BATTERY);
                    try {
                        startSafely(SetupHelper.batteryDialogIntent(this));
                    } catch (Throwable t) {
                        startSafely(SetupHelper.batteryListIntent());
                    }
                });

        addRow("自启动 / 后台运行", SetupHelper.romName() + " 需要在系统里手动允许一次",
                null, "去设置",
                () -> startSafely(SetupHelper.autostartIntent(this)));

        addKeepAliveRow();

        TextView ka = text("关于「后台常驻通知」：开启后会显示一条低优先级、常驻的通知，"
                + "作用是让系统把本应用当成「正在运行」的应用，降低锁屏后被清理的概率；"
                + "它不采集任何数据，随时可以在本页关闭。\n"
                + "注意：这只是一个「提高存活率」的辅助手段，不能保证 100% 不被杀。"
                + "真正影响存活的是系统的省电策略和自启动权限，请把下面几项一起设置好。", 11, SUB, false);
        ka.setPadding(0, dp(14), 0, 0);
        ka.setLineSpacing(dp(3), 1f);
        content.addView(ka);

        TextView tip = text("小米/红米：设置→应用→智能待办→省电策略选「无限制」，并允许自启动。\n"
                + "华为/荣耀：应用启动管理→改为手动，全部允许。\n"
                + "OPPO/vivo：允许后台运行 + 允许自启动。\n"
                + "还可以在最近任务里把本应用下拉/加锁，防止被一键清理。", 11, SUB, false);
        tip.setPadding(0, dp(12), 0, 0);
        tip.setLineSpacing(dp(4), 1f);
        content.addView(tip);
    }

    private void oneTap() {
        if (!SetupHelper.hasAccessibility(this)) {
            Toast.makeText(this, "先开启无障碍，之后所有开关都能一键点", Toast.LENGTH_LONG).show();
            startSafely(SetupHelper.accessibilityIntent());
            return;
        }
        if (!SetupHelper.hasNotifAccess(this)) {
            PermAutoClickService.arm(this, PermAutoClickService.TARGET_NOTIF);
            Toast.makeText(this, "正在打开通知使用权，稍等自动点开关", Toast.LENGTH_SHORT).show();
            startSafely(SetupHelper.notifIntent());
            return;
        }
        if (!SetupHelper.ignoreBattery(this)) {
            PermAutoClickService.arm(this, PermAutoClickService.TARGET_BATTERY);
            try {
                startSafely(SetupHelper.batteryDialogIntent(this));
            } catch (Throwable t) {
                startSafely(SetupHelper.batteryListIntent());
            }
            return;
        }
        if (!KeepAliveService.enabled(this)) {
            enableKeepAlive(true);
            Toast.makeText(this, "已开启后台常驻", Toast.LENGTH_SHORT).show();
        }
        Toast.makeText(this, "全部就绪，最后确认一下自启动即可", Toast.LENGTH_LONG).show();
        startSafely(SetupHelper.autostartIntent(this));
    }

    private void addRow(String name, String desc, Boolean state, String actionText, Runnable action) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(13), dp(12), dp(13));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(9);
        content.addView(card, lp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        card.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView t = text(name, 14, TEXT, true);
        col.addView(t);
        TextView d = text(desc, 11, SUB, false);
        d.setPadding(0, dp(3), 0, 0);
        col.addView(d);

        if (state != null && state) {
            TextView ok = text("已开启", 12, OKC, true);
            ok.setPadding(dp(10), dp(6), dp(10), dp(6));
            GradientDrawable ob = new GradientDrawable();
            ob.setColor(0x1A16A34A);
            ob.setCornerRadius(dp(9));
            ok.setBackground(ob);
            card.addView(ok);
        } else {
            TextView btn = text(actionText, 12, PRIMARY, true);
            btn.setPadding(dp(14), dp(8), dp(14), dp(8));
            GradientDrawable bb = new GradientDrawable();
            bb.setColor(0x143A57D6);
            bb.setCornerRadius(dp(10));
            btn.setBackground(bb);
            btn.setOnClickListener(v -> action.run());
            card.addView(btn);
        }
    }

    private void addKeepAliveRow() {
        boolean on = KeepAliveService.enabled(this);
        addRow("后台常驻通知", "低优先级常驻通知，降低被系统回收概率，可随时关闭", null,
                on ? "关闭" : "开启",
                () -> enableKeepAlive(!KeepAliveService.enabled(this)));
    }

    private void enableKeepAlive(boolean on) {
        KeepAliveService.setEnabled(this, on);
        if (on && Build.VERSION.SDK_INT >= 33) {
            try {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7);
            } catch (Throwable ignored) { }
        }
        buildUi();
    }

    private void startSafely(Intent i) {
        try {
            startActivity(i);
        } catch (Throwable t) {
            try {
                startActivity(SetupHelper.appDetailIntent(this));
                Toast.makeText(this, "该机型没有这个页面，已打开应用详情", Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) { }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (content != null) buildUi();
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
