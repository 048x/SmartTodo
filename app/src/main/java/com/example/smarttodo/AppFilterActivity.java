package com.example.smarttodo;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 应用黑白名单设置页：
 * - 顶部切换过滤方式（全部 / 白名单 / 黑名单）；
 * - 下面列出已安装的桌面应用，勾选即加入当前模式的名单。
 * 黑白名单共用一份勾选集合，切换模式只改变"选中代表允许还是禁止"。
 */
public class AppFilterActivity extends BaseActivity {

    private final List<String> pkgs = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private ListView list;
    private TextView modeView;
    private TextView hintView;
    private TextView emptyView;
    private TextView clearView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildRoot());
        loadAppsAsync();
    }

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.bg));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), dp(14), dp(16), dp(8));

        TextView back = new TextView(this);
        back.setText("‹ 返回");
        back.setTextSize(15);
        back.setTextColor(getColor(R.color.text_secondary));
        back.setPadding(0, dp(6), dp(12), dp(6));
        back.setOnClickListener(v -> finish());
        head.addView(back);

        TextView title = new TextView(this);
        title.setText("应用过滤");
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(getColor(R.color.text_primary));
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(title);

        clearView = new TextView(this);
        clearView.setText("清空选择");
        clearView.setTextSize(12);
        clearView.setTextColor(getColor(R.color.text_secondary));
        clearView.setPadding(dp(8), dp(6), 0, dp(6));
        clearView.setOnClickListener(v -> {
            AppFilter.setApps(this, new HashSet<String>());
            refreshChecked();
            updateTexts();
        });
        head.addView(clearView);
        root.addView(head);

        modeView = new TextView(this);
        modeView.setTextSize(14);
        modeView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        modeView.setTextColor(getColor(R.color.text_primary));
        modeView.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(getColor(R.color.surface));
        bg.setCornerRadius(dp(14));
        modeView.setBackground(bg);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.leftMargin = dp(16);
        mlp.rightMargin = dp(16);
        root.addView(modeView, mlp);
        modeView.setOnClickListener(v -> pickMode());

        hintView = new TextView(this);
        hintView.setTextSize(11.5f);
        hintView.setLineSpacing(dp(3), 1f);
        hintView.setPadding(dp(20), dp(8), dp(20), dp(6));
        root.addView(hintView);

        emptyView = new TextView(this);
        emptyView.setText("正在读取已安装应用…");
        emptyView.setTextSize(12);
        emptyView.setTextColor(getColor(R.color.text_tertiary));
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(0, dp(30), 0, 0);
        root.addView(emptyView);

        list = new ListView(this);
        list.setChoiceMode(AbsListView.CHOICE_MODE_MULTIPLE);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setPadding(dp(10), dp(4), dp(10), dp(16));
        list.setClipToPadding(false);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_multiple_choice,
                android.R.id.text1, labels);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            saveChecked();
            updateTexts();
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        updateTexts();
        return root;
    }

    private void pickMode() {
        final String[] labels = {"全部应用（不过滤）", "白名单（只解析选中的应用）", "黑名单（不解析选中的应用）"};
        Ui.dialog(this)
                .setTitle("过滤方式")
                .setSingleChoiceItems(labels, AppFilter.mode(this), (d, which) -> {
                    AppFilter.setMode(this, which);
                    d.dismiss();
                    updateTexts();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void updateTexts() {
        if (modeView == null) return;
        modeView.setText("过滤方式：" + AppFilter.modeLabel(this) + "  ›");
        int m = AppFilter.mode(this);
        if (m == AppFilter.OFF) {
            hintView.setText("全部应用的通知都会参与解析，勾选暂时不生效。");
            hintView.setTextColor(getColor(R.color.text_secondary));
        } else if (m == AppFilter.WHITE && AppFilter.apps(this).isEmpty()) {
            hintView.setText("白名单为空：现在任何通知都不会生成待办。请至少勾选一个应用。");
            hintView.setTextColor(getColor(R.color.warn));
        } else if (m == AppFilter.WHITE) {
            hintView.setText("只解析已勾选的应用（" + AppFilter.apps(this).size() + " 个）。");
            hintView.setTextColor(getColor(R.color.text_secondary));
        } else {
            hintView.setText("已勾选的应用不会生成待办（" + AppFilter.apps(this).size() + " 个）。");
            hintView.setTextColor(getColor(R.color.text_secondary));
        }
    }

    private void saveChecked() {
        Set<String> set = new HashSet<>();
        for (int i = 0; i < pkgs.size(); i++) {
            if (list.isItemChecked(i)) set.add(pkgs.get(i));
        }
        AppFilter.setApps(this, set);
    }

    private void refreshChecked() {
        if (list == null) return;
        Set<String> set = AppFilter.apps(this);
        for (int i = 0; i < pkgs.size(); i++) {
            list.setItemChecked(i, set.contains(pkgs.get(i)));
        }
    }

    private void loadAppsAsync() {
        new Thread(() -> {
            final Map<String, String> map = new HashMap<>();
            try {
                PackageManager pm = getPackageManager();
                Intent main = new Intent(Intent.ACTION_MAIN);
                main.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
                for (ResolveInfo ri : ris) {
                    if (ri == null || ri.activityInfo == null) continue;
                    String pkg = ri.activityInfo.packageName;
                    if (pkg == null || pkg.equals(getPackageName())) continue;
                    if (!map.containsKey(pkg)) {
                        CharSequence label = ri.loadLabel(pm);
                        map.put(pkg, label == null ? pkg : label.toString());
                    }
                }
            } catch (Throwable ignored) { }

            runOnUiThread(() -> {
                List<String> names = new ArrayList<>(map.keySet());
                Collections.sort(names, new Comparator<String>() {
                    @Override
                    public int compare(String a, String b) {
                        String la = map.get(a), lb = map.get(b);
                        return String.valueOf(la).compareToIgnoreCase(String.valueOf(lb));
                    }
                });
                pkgs.clear();
                labels.clear();
                for (String p : names) {
                    pkgs.add(p);
                    labels.add(map.get(p) + "  ·  " + p);
                }
                adapter.notifyDataSetChanged();
                refreshChecked();
                emptyView.setText(pkgs.isEmpty()
                        ? "没有读取到已安装应用（部分系统限制了应用可见性）" : "");
                emptyView.setVisibility(pkgs.isEmpty() ? View.VISIBLE : View.GONE);
                updateTexts();
            });
        }, "app-filter-load").start();
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
