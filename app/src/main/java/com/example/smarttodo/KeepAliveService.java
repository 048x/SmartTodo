package com.example.smarttodo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * 常驻前台服务，唯一作用是让自己的进程不那么容易被系统回收，
 * 从而让通知监听服务稳定在线。可以随时在授权页关掉。
 */
public class KeepAliveService extends Service {

    private static final String CH = "keepalive";
    private static final int ID = 1001;

    public static final String PREF = "setup";
    public static final String KEY_KEEP = "keepalive";

    public static boolean enabled(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_KEEP, false);
    }

    public static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_KEEP, on).apply();
        Intent i = new Intent(c, KeepAliveService.class);
        if (on) {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } else {
            c.stopService(i);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!enabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification n = build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(ID, n);
        }
        return START_STICKY;
    }

    private Notification build() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CH, "后台守护",
                    NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH)
                : new Notification.Builder(this);
        return b.setContentTitle("智能待办正在后台运行")
                .setContentText("保证群消息里的待办不会漏掉")
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
