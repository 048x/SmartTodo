package com.example.smarttodo;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 应用黑白名单：控制哪些 App 的通知可以生成待办。
 * 全部本地配置，存 SharedPreferences。
 *
 * - OFF：不过滤，所有 App 的通知都参与解析；
 * - WHITE：只有选中的 App 才解析；
 * - BLACK：选中的 App 不解析。
 */
public final class AppFilter {

    public static final int OFF = 0;
    public static final int WHITE = 1;
    public static final int BLACK = 2;

    private static final String PREF = "app_filter";
    private static final String KEY_MODE = "mode";
    private static final String KEY_APPS = "apps";

    private AppFilter() { }

    public static int mode(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_MODE, OFF);
    }

    public static void setMode(Context c, int mode) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putInt(KEY_MODE, mode).apply();
    }

    public static Set<String> apps(Context c) {
        String csv = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY_APPS, "");
        Set<String> out = new HashSet<>();
        if (csv == null || csv.isEmpty()) return out;
        Collections.addAll(out, csv.split(","));
        return out;
    }

    public static void setApps(Context c, Set<String> pkgs) {
        StringBuilder sb = new StringBuilder();
        if (pkgs != null) {
            for (String p : pkgs) {
                if (p == null || p.isEmpty()) continue;
                if (sb.length() > 0) sb.append(',');
                sb.append(p);
            }
        }
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(KEY_APPS, sb.toString()).apply();
    }

    /** 这条通知要不要继续解析。 */
    public static boolean allows(Context c, String pkg) {
        int m = mode(c);
        if (m == OFF) return true;
        if (pkg == null || pkg.isEmpty()) return true;
        boolean selected = apps(c).contains(pkg);
        return m == WHITE ? selected : !selected;
    }

    public static String modeLabel(Context c) {
        switch (mode(c)) {
            case WHITE: return "白名单";
            case BLACK: return "黑名单";
            default: return "全部应用";
        }
    }

    public static String summary(Context c) {
        int m = mode(c);
        if (m == OFF) return "全部应用的通知都会解析";
        return modeLabel(c) + " · 已选 " + apps(c).size() + " 个应用";
    }
}
