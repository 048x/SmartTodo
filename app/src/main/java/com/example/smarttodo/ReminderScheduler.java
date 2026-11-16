package com.example.smarttodo;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.List;

/**
 * 本地闹钟：截止时间一到，发一条系统通知提醒。
 * 纯 AlarmManager + BroadcastReceiver，不引入任何第三方库、不联网。
 *
 * 同步策略：
 * - 每次数据变更（TodoDb.notifyChanged）都会调用 sync()；
 * - sync() 先比对"未完成 + 有截止时间"的签名，没变就直接返回，避免重复排闹钟；
 * - 签名变了就取消上一轮排过的，再按最新数据重排（最多 120 个）。
 * 完成待办 = done 置 1 → 不再出现在 queryPending() → 自动取消。
 */
public final class ReminderScheduler {

    public static final String ACTION_REMIND = "com.example.smarttodo.action.REMIND";

    private static final String PREF = "reminders";
    private static final String KEY_IDS = "scheduled_ids";
    private static final int MAX_ALARMS = 120;

    /** 进程内缓存签名，避免每次写库都做一次全量取消/重排。 */
    private static String sSignature = "";

    private ReminderScheduler() { }

    public static synchronized void sync(Context c) {
        if (c == null) return;
        Context app = c.getApplicationContext();

        List<TodoDb.Item> pending = TodoDb.get(app).queryPending();
        long now = System.currentTimeMillis();

        StringBuilder sig = new StringBuilder();
        for (TodoDb.Item it : pending) {
            if (it.dueAt > now) sig.append(it.id).append(':').append(it.dueAt).append(';');
        }
        String signature = sig.toString();
        if (signature.equals(sSignature)) return;

        SharedPreferences sp = app.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);

        // 取消上一轮排过的全部闹钟
        if (am != null) {
            String old = sp.getString(KEY_IDS, "");
            if (!old.isEmpty()) {
                for (String s : old.split(",")) {
                    long id = parseLong(s);
                    if (id > 0) {
                        try {
                            am.cancel(pendingIntentFor(app, id));
                        } catch (Throwable ignored) { }
                    }
                }
            }
        }

        // 重新排
        StringBuilder keep = new StringBuilder();
        if (am != null) {
            int n = 0;
            for (TodoDb.Item it : pending) {
                if (it.dueAt <= now) continue;
                if (n >= MAX_ALARMS) break;
                try {
                    setAlarm(app, am, it.id, it.dueAt);
                    keep.append(it.id).append(',');
                    n++;
                } catch (Throwable ignored) { }
            }
        }
        sp.edit().putString(KEY_IDS, keep.toString()).apply();
        sSignature = signature;
    }

    /** 只取消单条（删除时用；其余情况交给 sync 统一处理）。 */
    public static void cancel(Context c, long id) {
        if (c == null || id <= 0) return;
        try {
            AlarmManager am = (AlarmManager) c.getApplicationContext()
                    .getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(pendingIntentFor(c.getApplicationContext(), id));
        } catch (Throwable ignored) { }
        sSignature = "";
    }

    private static void setAlarm(Context c, AlarmManager am, long id, long at) {
        PendingIntent pi = pendingIntentFor(c, id);
        if (Build.VERSION.SDK_INT >= 31) {
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                // 没有精确闹钟权限时退化为非精确闹钟，仍然会在到期后提醒
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    private static PendingIntent pendingIntentFor(Context c, long id) {
        Intent i = new Intent(c, ReminderReceiver.class);
        i.setAction(ACTION_REMIND);
        i.putExtra("id", id);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, (int) (id % Integer.MAX_VALUE), i, flags);
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }
}
