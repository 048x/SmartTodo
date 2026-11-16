package com.example.smarttodo;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 开机 / 覆盖安装 / 解锁后，把监听服务和常驻服务重新拉起来。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent intent) {
        if (c == null || intent == null) return;
        String a = intent.getAction();
        if (a == null) return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)
                && !Intent.ACTION_USER_UNLOCKED.equals(a)) return;

        if (Build.VERSION.SDK_INT >= 24 && SetupHelper.hasNotifAccess(c)) {
            try {
                TodoListenerService.requestRebind(
                        new ComponentName(c, TodoListenerService.class));
            } catch (Throwable ignored) { }
        }

        if (KeepAliveService.enabled(c)) {
            try {
                Intent i = new Intent(c, KeepAliveService.class);
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
                else c.startService(i);
            } catch (Throwable ignored) { }
        }

        // 重启 / 覆盖安装后 AlarmManager 里的闹钟会丢失，这里按数据库重新排一遍
        try {
            ReminderScheduler.sync(c);
        } catch (Throwable ignored) { }
        try {
            TodoWidgetProvider.refresh(c);
        } catch (Throwable ignored) { }
    }
}
