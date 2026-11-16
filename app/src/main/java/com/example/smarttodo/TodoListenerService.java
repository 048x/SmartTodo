package com.example.smarttodo;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 监听通知，把群消息里"要做的事"自动提取成待办。
 * 只在本地做规则匹配，不读取、不上传任何内容。
 *
 * 解析与入库都在单个后台线程 + 有界队列里执行，回调线程只负责投递，
 * 长文本通知不会阻塞系统回调线程，也不会造成 ANR。
 */
public class TodoListenerService extends NotificationListenerService {

    private static final String TAG = "SmartTodo";

    /**
     * 单线程有界队列：
     * - 单线程：保证同一时刻只有一条消息在解析/入库，天然串行化，避免并发去重竞态；
     * - 队列满：DiscardOldestPolicy 丢弃最旧的待处理通知，优先保证系统回调不堆积。
     */
    private static final int QUEUE_SIZE = 64;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(QUEUE_SIZE),
            r -> {
                Thread t = new Thread(r, "notif-parse");
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy());

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            final StatusBarNotification snapshot = sbn;
            worker.execute(() -> {
                try {
                    handle(snapshot);
                } catch (Exception e) {
                    Log.w(TAG, "handle fail", e);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "post fail", t);
        }
    }

    @Override
    public void onDestroy() {
        // 优雅关闭：队列里已收到的通知尽量处理完，不中断正在进行的入库
        worker.shutdown();
        super.onDestroy();
    }

    private void handle(StatusBarNotification sbn) {
        if (sbn == null) return;

        Notification n = sbn.getNotification();
        if (n == null) return;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;

        Bundle ex = n.extras;
        if (ex == null) return;

        CharSequence t = ex.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence b = ex.getCharSequence(Notification.EXTRA_TEXT);
        if (b == null) b = ex.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (b == null) b = ex.getCharSequence(Notification.EXTRA_SUMMARY_TEXT);

        NotifIngest.ingest(this, sbn.getPackageName(),
                t == null ? "" : t.toString(),
                b == null ? "" : b.toString(),
                sbn.getPostTime(),
                sbn.getKey());
    }
}
