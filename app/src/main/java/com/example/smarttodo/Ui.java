package com.example.smarttodo;

import android.content.Context;
import android.content.res.Configuration;

/** 深浅色模式：0 跟随系统 · 1 浅色 · 2 深色 */
public final class Ui {

    public static final int AUTO = 0;
    public static final int LIGHT = 1;
    public static final int DARK = 2;

    private static final String PREF = "ui_pref";
    private static final String KEY = "theme_mode";

    private Ui() { }

    public static int mode(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY, AUTO);
    }

    public static void setMode(Context c, int m) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt(KEY, m).apply();
    }

    public static String label(Context c) {
        switch (mode(c)) {
            case LIGHT: return "浅色";
            case DARK: return "深色";
            default: return "跟随系统";
        }
    }

    public static boolean isDark(Context c) {
        int m = mode(c);
        if (m == LIGHT) return false;
        if (m == DARK) return true;
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /** 手动指定深浅色时，用配置包一层 Context，让 -night 资源生效。 */
    public static Context wrap(Context c) {
        int m = mode(c);
        if (m == AUTO) return c;
        Configuration cfg = new Configuration(c.getResources().getConfiguration());
        cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (m == DARK ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        return c.createConfigurationContext(cfg);
    }

    /**
     * 统一对话框构造：显式指定 R.style.AppDialog，
     * values / values-night 各有一套，保证手动切深浅色后弹窗背景、文字一起跟着变，
     * 不再出现"深色首页 + 白色弹窗"。
     */
    public static android.app.AlertDialog.Builder dialog(Context c) {
        return new android.app.AlertDialog.Builder(c, R.style.AppDialog);
    }
}
