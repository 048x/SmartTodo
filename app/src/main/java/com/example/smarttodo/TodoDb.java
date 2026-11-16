package com.example.smarttodo;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

public class TodoDb extends SQLiteOpenHelper {

    private static final String DB = "smarttodo.db";
    private static final int VER = 3;

    /**
     * 本地数据变更通知地址。
     * 不需要 ContentProvider：ContentResolver 的观察者注册表只认 Uri，
     * 任何写入后 notifyChange 即可让 UI 通过 ContentObserver 收到回调。
     */
    public static final Uri URI = Uri.parse("content://com.example.smarttodo/todo");

    private static TodoDb sInstance;

    public static synchronized TodoDb get(Context c) {
        if (sInstance == null) sInstance = new TodoDb(c.getApplicationContext());
        return sInstance;
    }

    private final Context appCtx;

    private TodoDb(Context c) {
        super(c, DB, null, VER);
        appCtx = c;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE todo (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "title TEXT, source TEXT, due TEXT, due_at INTEGER DEFAULT 0," +
                "done INTEGER, created INTEGER, notif_key TEXT DEFAULT '')");
        db.execSQL("CREATE INDEX idx_created ON todo(created)");
        db.execSQL("CREATE INDEX idx_notif_key ON todo(notif_key)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 2) {
            try {
                db.execSQL("ALTER TABLE todo ADD COLUMN due_at INTEGER DEFAULT 0");
            } catch (Exception ignored) { }
        }
        if (oldV < 3) {
            try {
                db.execSQL("ALTER TABLE todo ADD COLUMN notif_key TEXT DEFAULT ''");
            } catch (Exception ignored) { }
            try {
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_notif_key ON todo(notif_key)");
            } catch (Exception ignored) { }
        }
    }

    public static class Item {
        public long id;
        public String title = "";
        public String source = "";
        public String due = "";
        public long dueAt;
        public boolean done;
        public long created;
    }

    /** 兼容原调用方：不带通知 key。 */
    public synchronized long insert(String title, String source, String due,
                                    long dueAt, long created) {
        return insert(title, source, due, dueAt, created, "");
    }

    /**
     * 写入一条待办。插入与「超出 1000 条裁旧」放在同一事务里，
     * 避免裁剪失败时留下半成品状态；提交后再通知 UI。
     */
    public synchronized long insert(String title, String source, String due,
                                    long dueAt, long created, String notifKey) {
        long id = -1;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues cv = new ContentValues();
            cv.put("title", title);
            cv.put("source", source == null ? "" : source);
            cv.put("due", due == null ? "" : due);
            cv.put("due_at", dueAt);
            cv.put("done", 0);
            cv.put("created", created);
            cv.put("notif_key", notifKey == null ? "" : notifKey);
            id = db.insert("todo", null, cv);
            db.execSQL("DELETE FROM todo WHERE id NOT IN " +
                    "(SELECT id FROM todo ORDER BY created DESC LIMIT 1000)");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        notifyChanged();
        return id;
    }

    /** 同一条消息会被系统反复回调，按"去标点后的标题 + 来源"去重。 */
    public synchronized boolean existsRecent(String title, String source, long since) {
        String normTitle = normalize(title);
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT title FROM todo WHERE source=? AND created>=?",
                new String[]{source == null ? "" : source, String.valueOf(since)});
        boolean has = false;
        while (c.moveToNext()) {
            if (normalize(c.getString(0)).equals(normTitle)) {
                has = true;
                break;
            }
        }
        c.close();
        return has;
    }

    /** 第一优先级去重：同一条系统通知（StatusBarNotification.getKey）。 */
    public synchronized boolean existsKey(String notifKey) {
        if (notifKey == null || notifKey.isEmpty()) return false;
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM todo WHERE notif_key=? LIMIT 1",
                new String[]{notifKey});
        boolean has = c.moveToFirst();
        c.close();
        return has;
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s，。；;！!？?、（）()【】\\[\\]“”\"']+", "").toLowerCase();
    }

    public synchronized List<Item> queryAll() {
        List<Item> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,title,source,due,due_at,done,created FROM todo " +
                        "ORDER BY done ASC, CASE WHEN due_at>0 THEN 0 ELSE 1 END, " +
                        "due_at ASC, created DESC", null);
        while (c.moveToNext()) {
            Item it = new Item();
            it.id = c.getLong(0);
            it.title = c.getString(1);
            it.source = c.getString(2);
            it.due = c.getString(3);
            it.dueAt = c.getLong(4);
            it.done = c.getInt(5) == 1;
            it.created = c.getLong(6);
            out.add(it);
        }
        c.close();
        return out;
    }

    /** 未完成且有截止时间的待办（按时间升序），给闹钟和小组件用。 */
    public synchronized List<Item> queryPending() {
        List<Item> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,title,source,due,due_at,done,created FROM todo " +
                        "WHERE done=0 AND due_at>0 ORDER BY due_at ASC LIMIT 200", null);
        while (c.moveToNext()) {
            Item it = new Item();
            it.id = c.getLong(0);
            it.title = c.getString(1);
            it.source = c.getString(2);
            it.due = c.getString(3);
            it.dueAt = c.getLong(4);
            it.done = false;
            it.created = c.getLong(6);
            out.add(it);
        }
        c.close();
        return out;
    }

    public synchronized Item byId(long id) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,title,source,due,due_at,done,created FROM todo WHERE id=?",
                new String[]{String.valueOf(id)});
        Item it = null;
        if (c.moveToFirst()) {
            it = new Item();
            it.id = c.getLong(0);
            it.title = c.getString(1);
            it.source = c.getString(2);
            it.due = c.getString(3);
            it.dueAt = c.getLong(4);
            it.done = c.getInt(5) == 1;
            it.created = c.getLong(6);
        }
        c.close();
        return it;
    }

    /**
     * JSON 导入：整批放进一个事务，避免逐条写入触发 N 次刷新。
     * 标题 + 创建时间完全相同的视为已存在，跳过。
     */
    public synchronized int importBatch(List<Item> items) {
        if (items == null || items.isEmpty()) return 0;
        int inserted = 0;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Item it : items) {
                if (it == null || it.title == null || it.title.trim().isEmpty()) continue;
                if (existsSame(db, it.title, it.created)) continue;
                ContentValues cv = new ContentValues();
                cv.put("title", it.title);
                cv.put("source", it.source == null ? "" : it.source);
                cv.put("due", it.due == null ? "" : it.due);
                cv.put("due_at", it.dueAt);
                cv.put("done", it.done ? 1 : 0);
                cv.put("created", it.created > 0 ? it.created : System.currentTimeMillis());
                cv.put("notif_key", "");
                if (db.insert("todo", null, cv) > 0) inserted++;
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        if (inserted > 0) notifyChanged();
        return inserted;
    }

    private boolean existsSame(SQLiteDatabase db, String title, long created) {
        Cursor c = db.rawQuery(
                "SELECT 1 FROM todo WHERE title=? AND created=? LIMIT 1",
                new String[]{title, String.valueOf(created)});
        boolean has = c.moveToFirst();
        c.close();
        return has;
    }

    public synchronized void setDone(long id, boolean done) {
        write("UPDATE todo SET done=? WHERE id=?",
                new Object[]{done ? 1 : 0, id});
    }

    public synchronized void delete(long id) {
        write("DELETE FROM todo WHERE id=?", new Object[]{id});
    }

    public synchronized int deleteDone() {
        return write("DELETE FROM todo WHERE done=1", new Object[0]);
    }

    public synchronized int deleteAll() {
        return write("DELETE FROM todo", new Object[0]);
    }

    /** 所有写操作统一走事务，提交成功后再发一次变更通知。 */
    private int write(String sql, Object[] args) {
        int affected = 0;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.execSQL(sql, args);
            affected = countChanged(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        notifyChanged();
        return affected;
    }

    /** 用 SQLite 的 changes() 取最近一条语句影响的行数（仅在事务内有效）。 */
    private int countChanged(SQLiteDatabase db) {
        Cursor c = db.rawQuery("SELECT changes()", null);
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    private void notifyChanged() {
        try {
            appCtx.getContentResolver().notifyChange(URI, null);
        } catch (Throwable ignored) { }
        // 数据一变，桌面小组件和本地闹钟跟着同步；两个调用内部都做了异常与空数据保护
        try {
            TodoWidgetProvider.refresh(appCtx);
        } catch (Throwable ignored) { }
        try {
            ReminderScheduler.sync(appCtx);
        } catch (Throwable ignored) { }
    }

    public synchronized int[] counts() {
        int total = 0, done = 0;
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*), SUM(done) FROM todo", null);
        if (c.moveToFirst()) {
            total = c.getInt(0);
            done = c.isNull(1) ? 0 : c.getInt(1);
        }
        c.close();
        return new int[]{total, done};
    }
}
