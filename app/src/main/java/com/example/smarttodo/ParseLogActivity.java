package com.example.smarttodo;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 解析预览记录页：看每条通知是被拦了、被判闲聊、还是生成了待办。 */
public class ParseLogActivity extends BaseActivity {

    private ListView list;
    private TextView empty;
    private ArrayAdapter<String> adapter;
    private final List<String> rows = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildRoot());
        refresh();
    }

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.bg));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), dp(14), dp(16), dp(8));

        TextView back = new TextView(this);
        back.setText("‹ 返回");
        back.setTextSize(15);
        back.setTextColor(getColor(R.color.text_secondary));
        back.setPadding(0, dp(6), dp(12), dp(6));
        back.setOnClickListener(v -> finish());
        head.addView(back);

        TextView title = new TextView(this);
        title.setText("解析记录");
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(getColor(R.color.text_primary));
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(title);

        TextView clear = new TextView(this);
        clear.setText("清空");
        clear.setTextSize(12);
        clear.setTextColor(getColor(R.color.text_secondary));
        clear.setPadding(dp(8), dp(6), 0, dp(6));
        clear.setOnClickListener(v -> {
            ParseLog.clear(this);
            refresh();
        });
        head.addView(clear);
        root.addView(head);

        TextView hint = new TextView(this);
        hint.setText("仅本机保存，最多 200 条。记录包含：应用过滤拦截、非任务消息、重复、已生成。");
        hint.setTextSize(11);
        hint.setTextColor(getColor(R.color.text_secondary));
        hint.setPadding(dp(18), 0, dp(18), dp(8));
        hint.setLineSpacing(dp(2), 1f);
        root.addView(hint);

        empty = new TextView(this);
        empty.setText("暂无记录。\n先在设置里打开「解析预览调试」，再等一条群消息。");
        empty.setTextSize(12.5f);
        empty.setTextColor(getColor(R.color.text_tertiary));
        empty.setGravity(Gravity.CENTER);
        empty.setLineSpacing(dp(4), 1f);
        empty.setPadding(dp(24), dp(40), dp(24), 0);
        root.addView(empty);

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setPadding(dp(14), dp(4), dp(14), dp(18));
        list.setClipToPadding(false);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1,
                android.R.id.text1, rows) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                tv.setTextSize(11.5f);
                tv.setTextColor(getColor(R.color.text_primary));
                tv.setPadding(dp(12), dp(10), dp(12), dp(10));
                tv.setLineSpacing(dp(3), 1f);
                return tv;
            }
        };
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private void refresh() {
        rows.clear();
        rows.addAll(ParseLog.read(this));
        if (adapter != null) adapter.notifyDataSetChanged();
        boolean has = !rows.isEmpty();
        if (empty != null) empty.setVisibility(has ? View.GONE : View.VISIBLE);
        if (list != null) list.setVisibility(has ? View.VISIBLE : View.GONE);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
