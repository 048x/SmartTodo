package com.example.smarttodo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 截止时间到点后的提醒。
 * 触发时会再查一次数据库：已删除 / 已完成 / 不存在的直接忽略，
 * 因此即使闹钟没来得及取消，也不会打扰用户。
 */
public class ReminderReceiver extends BroadcastReceiver {

    private static final String CH = "todo_remind";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (ctx == null || intent == null) return;
        long id = intent.getLongExtra("id", -1);
        if (id <= 0) return;

        TodoDb.Item it = TodoDb.get(ctx).byId(id);
        if (it == null || it.done) return;

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 24 && !nm.areNotificationsEnabled()) return;

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "待办到期提醒",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("待办截止时间到达时提醒");
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }

        Intent open = new Intent(ctx, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(ctx, (int) (id % Integer.MAX_VALUE), open, flags);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, CH)
                : new Notification.Builder(ctx);
        String text = it.due == null || it.due.isEmpty() ? "截止时间到了" : "截止：" + it.due;
        b.setContentTitle(it.title == null ? "待办到期" : it.title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setAutoCancel(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .setContentIntent(pi);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH);

        try {
            nm.notify((int) (id % 100000), b.build());
        } catch (Throwable ignored) { }
    }
}
