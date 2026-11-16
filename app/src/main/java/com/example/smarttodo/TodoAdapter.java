package com.example.smarttodo;

import android.content.Context;
import android.graphics.Paint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 待办列表适配器（RecyclerView 版）。
 *
 * 与原 BaseAdapter 版保持相同的对外接口（setData / setOnAction / getItem），
 * 新增 setOnItemClick；内部用 ViewHolder + stable id + DiffUtil：
 * - 行容器 SwipeRow 不再反复 inflate，复用抖动消除；
 * - 数据刷新只更新真正变化的行，滑动位置与展开状态不被打断。
 */
public class TodoAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;

    private final Context ctx;
    private final List<Row> rows = new ArrayList<>();
    private final SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat md = new SimpleDateFormat("M月d日", Locale.getDefault());
    private final SimpleDateFormat full = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

    private static class Row {
        String header;
        TodoDb.Item item;
        /** 稳定 id：待办用数据库 id；两个分区头用固定负数。 */
        long stableId;
    }

    public TodoAdapter(Context c) {
        ctx = c;
        setHasStableIds(true);
    }

    public interface OnAction { void onAction(TodoDb.Item it, int action); }

    public interface OnItemClick { void onItemClick(TodoDb.Item it); }

    private OnAction onAction;
    private OnItemClick onItemClick;

    public void setOnAction(OnAction l) { onAction = l; }

    public void setOnItemClick(OnItemClick l) { onItemClick = l; }

    public synchronized void setData(List<TodoDb.Item> data) {
        List<Row> next = new ArrayList<>();
        List<TodoDb.Item> pending = new ArrayList<>();
        List<TodoDb.Item> done = new ArrayList<>();
        if (data != null) {
            for (TodoDb.Item it : data) {
                if (it.done) done.add(it);
                else pending.add(it);
            }
        }
        if (!pending.isEmpty()) next.add(header("待完成 · " + pending.size(), -1));
        for (TodoDb.Item it : pending) next.add(itemRow(it));
        if (!done.isEmpty()) next.add(header("已完成 · " + done.size(), -2));
        for (TodoDb.Item it : done) next.add(itemRow(it));

        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DbDiff(rows, next), false);
        rows.clear();
        rows.addAll(next);
        diff.dispatchUpdatesTo(this);
    }

    @Override
    public long getItemId(int position) {
        return rows.get(position).stableId;
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).item == null ? TYPE_HEADER : TYPE_ITEM;
    }

    public TodoDb.Item getItem(int position) {
        if (position < 0 || position >= rows.size()) return null;
        return rows.get(position).item;
    }

    @Override
    public int getItemCount() { return rows.size(); }

    public static class HeaderVH extends RecyclerView.ViewHolder {
        HeaderVH(TextView v) { super(v); }
    }

    public static class ItemVH extends RecyclerView.ViewHolder {
        final SwipeRow wrapper;
        final ImageView check;
        final TextView title;
        final TextView due;
        final TextView meta;
        TodoDb.Item item;

        ItemVH(SwipeRow v) {
            super(v);
            wrapper = v;
            View content = v.content();
            check = content.findViewById(R.id.ivCheck);
            title = content.findViewById(R.id.tvTitle);
            due = content.findViewById(R.id.tvDue);
            meta = content.findViewById(R.id.tvMeta);
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            TextView tv = new TextView(ctx);
            tv.setTextSize(12);
            tv.setTextColor(ctx.getColor(R.color.text_secondary));
            int padH = dp(4), padTop = dp(10), padBottom = dp(6);
            tv.setPadding(padH, padTop, padH, padBottom);
            tv.setGravity(Gravity.CENTER_VERTICAL);
            tv.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return new HeaderVH(tv);
        }
        SwipeRow wrapper = new SwipeRow(ctx, R.layout.item_todo);
        // RecyclerView 需要显式 LayoutParams，宽度撑满，高度由内容决定
        wrapper.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return new ItemVH(wrapper);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = rows.get(position);
        if (row.item == null) {
            ((TextView) holder.itemView).setText(row.header);
            return;
        }

        final ItemVH h = (ItemVH) holder;
        final TodoDb.Item it = row.item;
        h.item = it;

        h.check.setImageResource(it.done ? R.drawable.ic_check_on : R.drawable.ic_check_off);
        h.title.setText(it.title);

        int flags = h.title.getPaintFlags();
        if (it.done) {
            h.title.setPaintFlags(flags | Paint.STRIKE_THRU_TEXT_FLAG);
        } else {
            h.title.setPaintFlags(flags & ~Paint.STRIKE_THRU_TEXT_FLAG);
        }
        h.title.setTextColor(it.done
                ? ctx.getColor(R.color.text_tertiary)
                : ctx.getColor(R.color.text_primary));

        bindDue(h.due, it);

        List<String> parts = new ArrayList<>();
        if (!TextUtils.isEmpty(it.source)) parts.add(it.source);
        parts.add(formatCreated(it.created));
        h.meta.setText(TextUtils.join(" · ", parts));

        h.wrapper.content().setBackgroundResource(it.done ? R.drawable.bg_card_done : R.drawable.bg_card);
        h.wrapper.bind(it.id, it.done);
        h.wrapper.setListener(action -> {
            if (onAction != null) onAction.onAction(it, action);
            else h.wrapper.reset();
        });
        h.wrapper.content().setOnClickListener(v -> {
            // 已展开：先收起；未展开：收起其它展开行后打开来源应用
            if (h.wrapper.isOpen()) {
                h.wrapper.closeQuietly();
                return;
            }
            SwipeRow.closeOpenRow();
            if (onItemClick != null) onItemClick.onItemClick(it);
        });
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof ItemVH) {
            ((ItemVH) holder).wrapper.reset();
            ((ItemVH) holder).item = null;
        }
        super.onViewRecycled(holder);
    }

    private static Row header(String text, long id) {
        Row r = new Row();
        r.header = text;
        r.stableId = id;
        return r;
    }

    private static Row itemRow(TodoDb.Item it) {
        Row r = new Row();
        r.item = it;
        r.stableId = it.id;
        return r;
    }

    /** 数据差异比较：只在真正变化时刷新对应行，避免整表重建造成抖动。 */
    private static class DbDiff extends DiffUtil.Callback {
        private final List<Row> oldRows;
        private final List<Row> newRows;

        DbDiff(List<Row> oldRows, List<Row> newRows) {
            this.oldRows = new ArrayList<>(oldRows);
            this.newRows = newRows;
        }

        @Override public int getOldListSize() { return oldRows.size(); }

        @Override public int getNewListSize() { return newRows.size(); }

        @Override
        public boolean areItemsTheSame(int oldPos, int newPos) {
            Row a = oldRows.get(oldPos), b = newRows.get(newPos);
            if (a.item == null || b.item == null) {
                return a.item == null && b.item == null && a.stableId == b.stableId;
            }
            return a.item.id == b.item.id;
        }

        @Override
        public boolean areContentsTheSame(int oldPos, int newPos) {
            Row a = oldRows.get(oldPos), b = newRows.get(newPos);
            if (a.item == null || b.item == null) {
                return TextUtils.equals(a.header, b.header);
            }
            TodoDb.Item x = a.item, y = b.item;
            return x.id == y.id
                    && x.done == y.done
                    && x.dueAt == y.dueAt
                    && TextUtils.equals(x.title, y.title)
                    && TextUtils.equals(x.source, y.source)
                    && TextUtils.equals(x.due, y.due);
        }
    }

    private void bindDue(TextView due, TodoDb.Item it) {
        if (it.dueAt > 0) {
            long now = System.currentTimeMillis();
            boolean overdue = !it.done && it.dueAt < now;
            boolean today = isSameDay(it.dueAt, now);
            boolean tomorrow = isSameDay(it.dueAt, now + 24L * 3600 * 1000);
            // 24 小时内到期：单独标出来，比「今天/明天」更醒目
            long left = it.dueAt - now;
            boolean soon = !it.done && left > 0 && left <= 24L * 3600 * 1000;

            String label;
            if (it.done) {
                label = "截止 " + formatDue(it.dueAt);
                due.setBackgroundResource(R.drawable.bg_due_done);
                due.setTextColor(ctx.getColor(R.color.text_tertiary));
            } else if (overdue) {
                label = "已过期 · " + formatDue(it.dueAt);
                due.setBackgroundResource(R.drawable.bg_due_overdue);
                due.setTextColor(ctx.getColor(R.color.warn));
            } else if (soon) {
                label = "即将到期 · " + (today
                        ? hm.format(new Date(it.dueAt))
                        : md.format(new Date(it.dueAt)) + " " + hm.format(new Date(it.dueAt)));
                due.setBackgroundResource(R.drawable.bg_due_today);
                due.setTextColor(ctx.getColor(R.color.warn));
            } else if (today) {
                label = "今天 " + hm.format(new Date(it.dueAt));
                due.setBackgroundResource(R.drawable.bg_due_today);
                due.setTextColor(ctx.getColor(R.color.warn));
            } else if (tomorrow) {
                label = "明天 " + hm.format(new Date(it.dueAt));
                due.setBackgroundResource(R.drawable.bg_due_future);
                due.setTextColor(ctx.getColor(R.color.primary_dark));
            } else {
                label = "截止 " + formatDue(it.dueAt);
                due.setBackgroundResource(R.drawable.bg_due_future);
                due.setTextColor(ctx.getColor(R.color.primary_dark));
            }
            due.setText(label);
            due.setVisibility(View.VISIBLE);
        } else if (!TextUtils.isEmpty(it.due)) {
            due.setText("截止 " + it.due);
            due.setBackgroundResource(R.drawable.bg_due_future);
            due.setTextColor(ctx.getColor(R.color.primary_dark));
            due.setVisibility(View.VISIBLE);
        } else {
            due.setVisibility(View.GONE);
        }
    }

    private String formatDue(long t) {
        return md.format(new Date(t)) + " " + hm.format(new Date(t));
    }

    private static boolean isSameDay(long a, long b) {
        Calendar ca = Calendar.getInstance();
        Calendar cb = Calendar.getInstance();
        ca.setTimeInMillis(a);
        cb.setTimeInMillis(b);
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR)
                && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR);
    }

    private String formatCreated(long t) {
        long now = System.currentTimeMillis();
        if (now - t < 24L * 3600 * 1000) return hm.format(new Date(t));
        return full.format(new Date(t));
    }

    private int dp(int v) {
        return Math.round(ctx.getResources().getDisplayMetrics().density * v);
    }
}
