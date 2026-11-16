package com.example.smarttodo;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

/** 通知入库的公共入口：通知使用权和偶发通知两条路都走这里。 */
public final class NotifIngest {

    private static final long DEDUP_WINDOW = 6L * 3600 * 1000;

    private NotifIngest() { }

    /** 兼容旧调用方（无障碍兜底没有系统通知 key）。 */
    public static void ingest(Context c, String pkg, String title, String body, long postTime) {
        ingest(c, pkg, title, body, postTime, null);
    }

    /**
     * @param notifKey StatusBarNotification.getKey()，同一条通知的唯一标识。
     *                 非空时作为第一优先级去重依据；文本去重保留为降级兜底。
     */
    public static void ingest(Context c, String pkg, String title, String body,
                              long postTime, String notifKey) {
        if (c == null || pkg == null) return;
        if (c.getPackageName().equals(pkg)) return;

        String source = label(c, pkg);
        boolean debug = ParseLog.enabled(c);

        // 0) 应用黑白名单：被过滤掉的通知连解析都不做
        if (!AppFilter.allows(c, pkg)) {
            if (debug) ParseLog.add(c, source, "应用过滤", title, "");
            return;
        }

        TodoExtractor.Hint hint = TodoExtractor.extract(title == null ? "" : title.trim(),
                body == null ? "" : body.trim());
        if (hint == null) {
            if (debug) ParseLog.add(c, source, "非任务", title, "");
            return;
        }

        TodoDb db = TodoDb.get(c);
        long now = System.currentTimeMillis();

        // 1) 通知 key 精确去重：系统重复回调、通知内容刷新都会命中
        if (db.existsKey(notifKey)) {
            if (debug) ParseLog.add(c, source, "重复(key)", hint.title, hint.due);
            return;
        }

        // 2) 降级兜底：没有 key（无障碍通道）或 key 未命中时，按文本 + 来源 + 6 小时窗口去重
        if (db.existsRecent(hint.title, source, now - DEDUP_WINDOW)) {
            if (debug) ParseLog.add(c, source, "重复(文本)", hint.title, hint.due);
            return;
        }

        long time = postTime > 0 ? postTime : now;
        db.insert(hint.title, source, hint.due, hint.dueAt, time,
                notifKey == null ? "" : notifKey);
        if (debug) ParseLog.add(c, source, "已生成", hint.title, hint.due);
    }

    static String label(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) {
            return pkg;
        }
    }
}
