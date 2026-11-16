package com.example.smarttodo;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 待办 JSON 导入导出。使用系统自带的 org.json，不引入第三方库。
 *
 * 导出结构：
 * {
 *   "app": "smarttodo", "version": 1, "exportedAt": 1700000000000,
 *   "items": [ { "title": "...", "source": "...", "due": "...",
 *                "dueAt": 0, "done": false, "created": 0 } ]
 * }
 * 导入支持上面的对象结构，也兼容直接给一个数组。
 */
public final class TodoJson {

    private TodoJson() { }

    public static String exportJson(Context c) {
        List<TodoDb.Item> all = TodoDb.get(c).queryAll();
        JSONArray items = new JSONArray();
        try {
            for (TodoDb.Item it : all) {
                JSONObject o = new JSONObject();
                o.put("title", it.title == null ? "" : it.title);
                o.put("source", it.source == null ? "" : it.source);
                o.put("due", it.due == null ? "" : it.due);
                o.put("dueAt", it.dueAt);
                o.put("done", it.done);
                o.put("created", it.created);
                items.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("app", "smarttodo");
            root.put("version", 1);
            root.put("exportedAt", System.currentTimeMillis());
            root.put("items", items);
            return root.toString(2);
        } catch (Exception e) {
            return "{\"app\":\"smarttodo\",\"items\":[]}";
        }
    }

    /** 返回成功写入的条数；解析失败抛 JSONException 由调用方提示。 */
    public static int importJson(Context c, String text) throws Exception {
        if (text == null || text.trim().isEmpty()) return 0;
        String body = text.trim();
        JSONArray arr;
        if (body.startsWith("[")) {
            arr = new JSONArray(body);
        } else {
            JSONObject root = new JSONObject(body);
            arr = root.optJSONArray("items");
        }
        if (arr == null) return 0;

        List<TodoDb.Item> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            TodoDb.Item it = new TodoDb.Item();
            it.title = o.optString("title", "").trim();
            if (it.title.isEmpty()) continue;
            it.source = o.optString("source", "导入");
            it.due = o.optString("due", "");
            it.dueAt = o.optLong("dueAt", 0);
            it.done = o.optBoolean("done", false);
            it.created = o.optLong("created", System.currentTimeMillis());
            list.add(it);
        }
        return TodoDb.get(c).importBatch(list);
    }
}
