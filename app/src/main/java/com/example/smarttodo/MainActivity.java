package com.example.smarttodo;

import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends BaseActivity {

    private TodoAdapter adapter;
    private SpringRecyclerView listView;
    private TextView tvDate;
    private TextView tvProgress;
    private TextView tvStatus;
    private TextView tvEmpty;
    private EditText etAdd;
    private ProgressBar progressBar;
    private SharedPreferences sp;

    /**
     * 数据库变更监听：替代原来的 2 秒轮询。
     * TodoDb 每次写入（增/删/改）后 notifyChange，这里收到回调再刷新，
     * 数据没变就完全不耗电。
     */
    private final ContentObserver dbObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange) {
            refresh();
        }
    };

    private boolean observerRegistered;

    /** 冷启动时无障碍服务可能还没连上，晚 1.5 秒再复检一次状态，避免误报"掉线"。 */
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            refresh();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        listView = findViewById(R.id.list);
        listView.setLayoutManager(new LinearLayoutManager(this));
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView rv, int newState) {
                // 列表一开始滚动，就收起已展开的行，避免滑动时按钮悬空
                if (newState != RecyclerView.SCROLL_STATE_IDLE) {
                    SwipeRow.closeOpenRow();
                }
            }
        });

        tvDate = findViewById(R.id.tvDate);
        tvProgress = findViewById(R.id.tvProgress);
        tvStatus = findViewById(R.id.tvStatus);
        // 状态文案可点：无障碍掉线 / 未授权时直接跳到一键授权页
        tvStatus.setOnClickListener(v -> startActivity(new Intent(this, SetupActivity.class)));
        tvEmpty = findViewById(R.id.tvEmpty);
        etAdd = findViewById(R.id.etAdd);
        progressBar = findViewById(R.id.pbProgress);

        adapter = new TodoAdapter(this);
        adapter.setOnAction((it, action) -> {
            if (action == SwipeRow.ACTION_DELETE) {
                TodoDb.get(this).delete(it.id);
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
            } else {
                TodoDb.get(this).setDone(it.id, !it.done);
                Toast.makeText(this, it.done ? "已标记为未完成" : "已完成", Toast.LENGTH_SHORT).show();
            }
            // 写库会触发 ContentObserver → refresh，这里应用内再兜底一次
            refresh();
        });
        adapter.setOnItemClick(it -> {
            SwipeRow.closeOpenRow();
            AppOpener.open(this, it.source);
        });
        listView.setAdapter(adapter);

        SimpleDateFormat fmt = new SimpleDateFormat("M月d日 EEEE", Locale.CHINA);
        tvDate.setText("从消息中自动提取 · " + fmt.format(new Date()));

        sp = getSharedPreferences("settings", MODE_PRIVATE);

        TextView btnTheme = findViewById(R.id.btnTheme);
        btnTheme.setText("设置");
        btnTheme.setOnClickListener(v -> showSettings());

        findViewById(R.id.btnAuth).setOnClickListener(v ->
                startActivity(new Intent(this, SetupActivity.class)));

        findViewById(R.id.btnClearDone).setOnClickListener(v -> {
            int n = TodoDb.get(this).deleteDone();
            refresh();
            Toast.makeText(this, n > 0 ? "已清理 " + n + " 条已完成" : "没有已完成的事项",
                    Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btnAdd).setOnClickListener(v -> addTodo());

        etAdd.setOnEditorActionListener((v, actionId, event) -> {
            addTodo();
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 只在回到前台时做一次状态检查，不再启动定时轮询
        rebindListenerIfNeeded();
        if (!observerRegistered) {
            getContentResolver().registerContentObserver(TodoDb.URI, true, dbObserver);
            observerRegistered = true;
        }
        // 回到前台补一次闹钟同步（进程被杀 / 重启后闹钟可能丢失）
        ReminderScheduler.sync(this);
        refresh();
        uiHandler.removeCallbacks(statusTick);
        uiHandler.postDelayed(statusTick, 1500);
    }

    /** 系统偶尔会把通知监听服务回收掉，回到前台时顺手重新绑定。 */
    private void rebindListenerIfNeeded() {
        if (Build.VERSION.SDK_INT >= 24 && hasPermission()) {
            try {
                TodoListenerService.requestRebind(
                        new ComponentName(this, TodoListenerService.class));
            } catch (Throwable ignored) { }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        uiHandler.removeCallbacks(statusTick);
        if (observerRegistered) {
            getContentResolver().unregisterContentObserver(dbObserver);
            observerRegistered = false;
        }
        SwipeRow.closeOpenRow();
    }

    private void addTodo() {
        String text = etAdd.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            Toast.makeText(this, "先写点什么", Toast.LENGTH_SHORT).show();
            return;
        }
        addTodoInternal(text);
        etAdd.setText("");
        hideKeyboard();
        refresh();
    }

    private void addTodoInternal(String text) {
        String due = "";

        // 1) 显式「截止 xxx」优先
        int at = text.indexOf("截止");
        if (at > 0) {
            due = text.substring(at + 2).trim();
            text = text.substring(0, at).trim();
        } else {
            // 2) 自动识别句子里的时间短语，如「明天下午3点前交作业」「月底前交报告」
            String phrase = DueParser.findDuePhrase(text);
            if (!TextUtils.isEmpty(phrase)) {
                String rest = text.replace(phrase, " ").trim();
                if (rest.length() >= 2) {
                    due = phrase;
                    text = rest;
                }
            }
        }

        text = text.replaceAll("^[，,、\\s]+|[，,、\\s]+$", "");
        if (TextUtils.isEmpty(text)) text = "未命名待办";
        long dueAt = DueParser.parse(due, System.currentTimeMillis());
        TodoDb.get(this).insert(text, "手动添加", due, dueAt, System.currentTimeMillis());
        listView.scrollToPosition(0);
    }

    private void showSettings() {
        final String[] items = {
                "外观：" + Ui.label(this),
                "震动反馈：" + (sp.getBoolean("haptic", true) ? "开" : "关"),
                "显示已完成：" + (sp.getBoolean("show_done", true) ? "开" : "关"),
                "应用过滤：" + AppFilter.summary(this),
                "解析预览调试：" + (ParseLog.enabled(this) ? "开" : "关"),
                "查看解析记录",
                "导入待办（JSON）",
                "导出待办（JSON）",
                "权限中心（逐项检测与跳转）",
                "通知与后台权限",
                "清空全部待办"
        };
        Ui.dialog(this)
                .setTitle("设置")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        pickTheme();
                    } else if (which == 1) {
                        sp.edit().putBoolean("haptic", !sp.getBoolean("haptic", true)).apply();
                        showSettings();
                    } else if (which == 2) {
                        sp.edit().putBoolean("show_done", !sp.getBoolean("show_done", true)).apply();
                        refresh();
                        showSettings();
                    } else if (which == 3) {
                        startActivity(new Intent(this, AppFilterActivity.class));
                    } else if (which == 4) {
                        ParseLog.setEnabled(this, !ParseLog.enabled(this));
                        showSettings();
                    } else if (which == 5) {
                        startActivity(new Intent(this, ParseLogActivity.class));
                    } else if (which == 6) {
                        importJson();
                    } else if (which == 7) {
                        exportJson();
                    } else if (which == 8) {
                        startActivity(new Intent(this,
                                com.example.smarttodo.permission.PermissionCenterActivity.class));
                    } else if (which == 9) {
                        startActivity(new Intent(this, SetupActivity.class));
                    } else if (which == 10) {
                        Ui.dialog(this)
                                .setMessage("清空后不可恢复，确认删除全部待办？")
                                .setNegativeButton("取消", null)
                                .setPositiveButton("清空", (dd, ww) -> {
                                    TodoDb.get(this).deleteAll();
                                    refresh();
                                    Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
                                })
                                .show();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void pickTheme() {
        final String[] labels = {"跟随系统", "浅色", "深色"};
        Ui.dialog(this)
                .setTitle("外观")
                .setSingleChoiceItems(labels, Ui.mode(this), (d, which) -> {
                    Ui.setMode(this, which);
                    d.dismiss();
                    recreate();
                })
                .show();
    }

    // ===== JSON 导入导出（系统文件选择器，不需要存储权限） =====
    private static final int REQ_EXPORT = 3001;
    private static final int REQ_IMPORT = 3002;

    private void exportJson() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        String name = "smarttodo-"
                + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + ".json";
        i.putExtra(Intent.EXTRA_TITLE, name);
        try {
            startActivityForResult(i, REQ_EXPORT);
        } catch (Throwable t) {
            Toast.makeText(this, "没有可用的文件管理器", Toast.LENGTH_SHORT).show();
        }
    }

    private void importJson() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_IMPORT);
        } catch (Throwable t) {
            Toast.makeText(this, "没有可用的文件管理器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();

        if (requestCode == REQ_EXPORT) {
            new Thread(() -> {
                String msg;
                try {
                    String json = TodoJson.exportJson(this);
                    OutputStream os = getContentResolver().openOutputStream(uri, "wt");
                    if (os == null) throw new IllegalStateException("无法写入");
                    os.write(json.getBytes("UTF-8"));
                    os.flush();
                    os.close();
                    msg = "已导出 " + TodoDb.get(this).queryAll().size() + " 条待办";
                } catch (Throwable t) {
                    msg = "导出失败：" + t.getMessage();
                }
                final String out = msg;
                runOnUiThread(() -> Toast.makeText(this, out, Toast.LENGTH_LONG).show());
            }, "todo-export").start();
        } else if (requestCode == REQ_IMPORT) {
            new Thread(() -> {
                String msg;
                try {
                    InputStream is = getContentResolver().openInputStream(uri);
                    if (is == null) throw new IllegalStateException("无法读取");
                    BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append('\n');
                    br.close();
                    int n = TodoJson.importJson(this, sb.toString());
                    msg = n > 0 ? "已导入 " + n + " 条待办" : "没有新增（重复或格式不匹配）";
                } catch (Throwable t) {
                    msg = "导入失败：" + t.getMessage();
                }
                final String out = msg;
                runOnUiThread(() -> {
                    Toast.makeText(this, out, Toast.LENGTH_LONG).show();
                    refresh();
                });
            }, "todo-import").start();
        }
    }

    private boolean hasPermission() {
        String p = Settings.Secure.getString(
                getContentResolver(), "enabled_notification_listeners");
        return p != null && p.contains(getPackageName());
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etAdd.getWindowToken(), 0);
    }

    private void refresh() {
        List<TodoDb.Item> data = TodoDb.get(this).queryAll();
        if (!sp.getBoolean("show_done", true)) {
            for (int i = data.size() - 1; i >= 0; i--) {
                if (data.get(i).done) data.remove(i);
            }
        }
        adapter.setData(data);
        tvEmpty.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);

        int[] counts = TodoDb.get(this).counts();
        int total = counts[0];
        int done = counts[1];
        tvProgress.setText(done + " / " + total + " 已完成");
        progressBar.setMax(Math.max(total, 1));
        progressBar.setProgress(done);

        long now = System.currentTimeMillis();
        int overdue = 0, today = 0;
        for (TodoDb.Item it : data) {
            if (it.done || it.dueAt <= 0) continue;
            if (it.dueAt < now) overdue++;
            else if (sameDay(it.dueAt, now)) today++;
        }

        boolean notif = hasPermission();
        boolean a11yOn = com.example.smarttodo.permission.PermAutoClickService.isEnabled(this);
        boolean a11yLive = com.example.smarttodo.permission.PermAutoClickService.isConnected();

        StringBuilder sb = new StringBuilder();
        boolean warn = false;
        if (a11yOn && !a11yLive) {
            // 无障碍开关还开着，但服务被系统回收 / 杀掉：这是最常见的"莫名其妙不工作了"
            sb.append("无障碍服务已掉线（被系统回收）· 点此重新开启");
            warn = true;
        } else if (!notif && !a11yOn) {
            sb.append("还没授权：点右上角「授权」，一键搞定");
            warn = true;
        } else {
            sb.append(notif ? "通知使用权已开启" : "已通过无障碍读取通知");
            if (!a11yOn) sb.append(" · 无障碍未开启，自动点击不可用");
            if (today > 0) sb.append(" · 今天到期 ").append(today).append(" 项");
            if (overdue > 0) sb.append(" · 已过期 ").append(overdue).append(" 项");
            if (today == 0 && overdue == 0) sb.append("，群消息里的待办会自动出现在这里");
            warn = overdue > 0 || !a11yOn;
        }
        tvStatus.setText(sb.toString());
        tvStatus.setTextColor(warn ? 0xFFC2410C : 0xFF5B6478);
    }

    private static boolean sameDay(long a, long b) {
        Calendar ca = Calendar.getInstance();
        Calendar cb = Calendar.getInstance();
        ca.setTimeInMillis(a);
        cb.setTimeInMillis(b);
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR)
                && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR);
    }
}
