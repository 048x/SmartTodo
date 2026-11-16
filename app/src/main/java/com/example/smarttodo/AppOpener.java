package com.example.smarttodo;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.widget.Toast;

/** 根据待办里记录的来源应用名，找回包名并打开。 */
public final class AppOpener {

    private AppOpener() { }

    public static boolean open(Context c, String appLabel) {
        if (appLabel == null || appLabel.trim().isEmpty()
                || "手动添加".equals(appLabel.trim())) {
            Toast.makeText(c, "这条是手动添加的，没有对应应用", Toast.LENGTH_SHORT).show();
            return false;
        }
        String want = appLabel.trim();
        PackageManager pm = c.getPackageManager();

        Intent exact = null, fuzzy = null;
        try {
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                String label = pm.getApplicationLabel(ai).toString();
                Intent i = pm.getLaunchIntentForPackage(ai.packageName);
                if (i == null) continue;
                if (label.equalsIgnoreCase(want)) {
                    exact = i;
                    break;
                }
                if (fuzzy == null && (label.contains(want) || want.contains(label))) {
                    fuzzy = i;
                }
            }
        } catch (Throwable ignored) { }

        Intent target = exact != null ? exact : fuzzy;
        if (target == null) {
            Toast.makeText(c, "没找到「" + want + "」，可能已卸载", Toast.LENGTH_SHORT).show();
            return false;
        }
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(target);
            return true;
        } catch (Throwable t) {
            Toast.makeText(c, "打不开「" + want + "」", Toast.LENGTH_SHORT).show();
            return false;
        }
    }
}
