package com.example.smarttodo;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 桌面小组件：展示未完成待办（最多 3 条）+ 完成进度。
 * 数据变更时由 TodoDb.notifyChanged() 主动刷新，不依赖 updatePeriodMillis 轮询。
 */
public class TodoWidgetProvider extends AppWidgetProvider {

    private static final SimpleDateFormat DUE_FMT =
            new SimpleDateFormat("M/d HH:mm", Locale.getDefault());

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        if (appWidgetIds == null) return;
        for (int id : appWidgetIds) {
            try {
                manager.updateAppWidget(id, build(context));
            } catch (Throwable ignored) { }
        }
    }

    /** 供数据层调用：刷新所有已添加的小组件实例。 */
    public static void refresh(Context c) {
        if (c == null) return;
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            ComponentName cn = new ComponentName(c, TodoWidgetProvider.class);
            int[] ids = m.getAppWidgetIds(cn);
            if (ids == null || ids.length == 0) return;
            for (int id : ids) m.updateAppWidget(id, build(c));
        } catch (Throwable ignored) { }
    }

    private static RemoteViews build(Context c) {
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_todo);

        List<TodoDb.Item> all = TodoDb.get(c).queryAll();
        List<TodoDb.Item> pending = new ArrayList<>();
        int total = 0, done = 0;
        for (TodoDb.Item it : all) {
            total++;
            if (it.done) done++;
            else pending.add(it);
        }

        rv.setTextViewText(R.id.wCount, done + " / " + total);

        int[] lineIds = {R.id.wLine1, R.id.wLine2, R.id.wLine3};
        for (int i = 0; i < lineIds.length; i++) {
            if (i < pending.size()) {
                rv.setTextViewText(lineIds[i], format(pending.get(i)));
                rv.setViewVisibility(lineIds[i], android.view.View.VISIBLE);
            } else {
                rv.setViewVisibility(lineIds[i], android.view.View.GONE);
            }
        }
        rv.setViewVisibility(R.id.wEmpty, pending.isEmpty()
                ? android.view.View.VISIBLE : android.view.View.GONE);

        Intent open = new Intent(c, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(c, 0, open, flags);
        rv.setOnClickPendingIntent(R.id.wTitle, pi);
        rv.setOnClickPendingIntent(R.id.wEmpty, pi);
        rv.setOnClickPendingIntent(R.id.wHint, pi);
        return rv;
    }

    private static String format(TodoDb.Item it) {
        String title = it.title == null ? "" : it.title;
        if (title.length() > 18) title = title.substring(0, 18) + "…";
        if (it.dueAt > 0) {
            return "· " + title + "  " + DUE_FMT.format(new Date(it.dueAt));
        }
        return "· " + title;
    }
}
