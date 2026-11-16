package com.example.smarttodo;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 解析预览调试记录：开关打开时，每条通知的处理结论都会留一条记录
 * （是否被应用过滤拦截 / 判为闲聊 / 重复 / 成功生成）。
 * 记录只存本机 SharedPreferences，最多 200 条，方便排查误识别。
 */
public final class ParseLog {

    private static final String PREF = "parse_log";
    private static final String KEY_ON = "enabled";
    private static final String KEY_ITEMS = "items";
    private static final int MAX = 200;

    private static final SimpleDateFormat TIME =
            new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());

    private ParseLog() { }

    public static boolean enabled(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_ON, false);
    }

    public static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ON, on).apply();
    }

    /** 记一条。app 传应用名/包名，verdict 传处理结论。 */
    public static synchronized void add(Context c, String app, String verdict,
                                        String title, String due) {
        if (c == null) return;
        try {
            SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            JSONArray arr;
            try {
                arr = new JSONArray(sp.getString(KEY_ITEMS, "[]"));
            } catch (Exception e) {
                arr = new JSONArray();
            }
            JSONObject o = new JSONObject();
            o.put("t", System.currentTimeMillis());
            o.put("app", trim(app, 40));
            o.put("v", trim(verdict, 20));
            o.put("title", trim(title, 60));
            o.put("due", trim(due, 30));

            JSONArray next = new JSONArray();
            next.put(o);
            for (int i = 0; i < arr.length() && next.length() < MAX; i++) {
                next.put(arr.get(i));
            }
            sp.edit().putString(KEY_ITEMS, next.toString()).apply();
        } catch (Throwable ignored) { }
    }

    /** 最新在前，已格式化，直接给列表用。 */
    public static List<String> read(Context c) {
        List<String> out = new ArrayList<>();
        try {
            SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(sp.getString(KEY_ITEMS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String time = TIME.format(new Date(o.optLong("t", 0)));
                String app = o.optString("app", "");
                String verdict = o.optString("v", "");
                String title = o.optString("title", "");
                String due = o.optString("due", "");
                StringBuilder sb = new StringBuilder();
                sb.append(time).append("  ").append(app).append(" · ").append(verdict);
                if (!title.isEmpty()) sb.append("\n").append(title);
                if (!due.isEmpty()) sb.append("   [").append(due).append("]");
                out.add(sb.toString());
            }
        } catch (Throwable ignored) { }
        return out;
    }

    public static synchronized void clear(Context c) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(KEY_ITEMS, "[]").apply();
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        String x = s.replace('\n', ' ').trim();
        return x.length() <= max ? x : x.substring(0, max) + "…";
    }
}
