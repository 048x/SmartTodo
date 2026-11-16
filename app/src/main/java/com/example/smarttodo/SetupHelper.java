package com.example.smarttodo;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

/** 各种系统开关的状态查询与跳转。 */
public final class SetupHelper {

    private SetupHelper() { }

    public static boolean hasNotifAccess(Context c) {
        String p = Settings.Secure.getString(c.getContentResolver(),
                "enabled_notification_listeners");
        return p != null && p.contains(c.getPackageName() + "/");
    }

    public static boolean hasAccessibility(Context c) {
        return com.example.smarttodo.permission.PermAutoClickService.isEnabled(c);
    }

    public static boolean ignoreBattery(Context c) {
        if (Build.VERSION.SDK_INT < 23) return true;
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    public static Intent notifIntent() {
        return new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
    }

    public static Intent accessibilityIntent() {
        return new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
    }

    /** 直接弹「允许后台运行」系统对话框。 */
    public static Intent batteryDialogIntent(Context c) {
        Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        i.setData(Uri.parse("package:" + c.getPackageName()));
        return i;
    }

    public static Intent batteryListIntent() {
        return new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
    }

    public static Intent appDetailIntent(Context c) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        i.setData(Uri.parse("package:" + c.getPackageName()));
        return i;
    }

    private static final String[][] AUTOSTART = {
            {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
            {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
            {"com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"},
            {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
            {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
            {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
            {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
            {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
            {"com.meizu.safe", "com.meizu.safe.permission.PermissionMainActivity"},
            {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
    };

    /** 各家 ROM 的自启动管理页；找不到时回落到应用详情页。 */
    public static Intent autostartIntent(Context c) {
        for (String[] p : AUTOSTART) {
            Intent i = new Intent();
            i.setComponent(new ComponentName(p[0], p[1]));
            if (c.getPackageManager().resolveActivity(i, 0) != null) return i;
        }
        for (String[] p : AUTOSTART) {
            Intent i = new Intent("android.intent.action.MAIN");
            i.addCategory("android.intent.category.LAUNCHER");
            i.setComponent(new ComponentName(p[0], p[1]));
            if (c.getPackageManager().resolveActivity(i, 0) != null) return i;
        }
        return appDetailIntent(c);
    }

    public static String romName() {
        String m = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase();
        if (m.contains("xiaomi") || m.contains("redmi")) return "小米/红米";
        if (m.contains("huawei") || m.contains("honor")) return "华为/荣耀";
        if (m.contains("oppo") || m.contains("realme")) return "OPPO/realme";
        if (m.contains("vivo") || m.contains("iqoo")) return "vivo/iQOO";
        if (m.contains("meizu")) return "魅族";
        if (m.contains("samsung")) return "三星";
        return "本机";
    }
}
