package com.example.smarttodo;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 列表行容器 —— 左滑两档阈值：
 *
 *   A（近阈值，默认 52dp）：滑过 A，松手后卡片停住，只露出右侧「删除」按钮，点击才删除；
 *   B（远阈值，默认 150dp）：滑过 B，松手直接执行「完成 / 未完成」，卡片飞回并收起。
 *
 * 拖动过程 1:1 跟随手指（超过 B 后加一点摩擦，仍保持跟手），全程不会越界乱飘。
 * 同一时刻只允许一行展开：展开新行先收旧行；列表滚动、点击其它条目、页面 onPause 都会收起。
 *
 * 想调参数：直接改下面的 THRESHOLD_A_DP / THRESHOLD_B_DP，
 * 用户设置里的「灵敏度」(sens) 会与 dp 值相乘，整体放大/缩小。
 */
public class SwipeRow extends FrameLayout {

    public static final int ACTION_TOGGLE = 1;
    public static final int ACTION_DELETE = 2;

    // ===== 可调参数：两档阈值（单位 dp，会再乘以设置里的灵敏度） =====
    /** 阈值 A：松手后停住，露出【删除】按钮。 */
    public static int THRESHOLD_A_DP = 52;
    /** 阈值 B：松手直接完成（必须 > A），过程中不展示删除按钮。 */
    public static int THRESHOLD_B_DP = 150;
    /** 超过 B 之后的拖动摩擦（0.2 = 手指再走 100dp，卡片只走 20dp）。 */
    private static final float OVER_B_FRICTION = 0.2f;

    public interface Listener { void onAction(int action); }

    private final View content;
    private final TextView hintDelete;
    private final TextView hintToggle;
    private final int delSpan;
    private final float sens;
    private final boolean haptic;
    private final int bottomMargin;

    private Listener listener;
    private long boundId = -1;
    private boolean done;

    private float downX, downY;
    private boolean decided;
    private boolean dragging;
    private float dx;
    /** 手指实际滑过的距离（未加摩擦），松手判定用这个，避免阈值被摩擦影响。 */
    private float lastAx;
    private boolean tickA, tickB;
    private boolean opened;
    private VelocityTracker tracker;

    /** 本次手势换算后的阈值像素。 */
    private float aPx, bPx;

    /**
     * 全局单行管理器：同一时刻最多只允许一行处于展开状态。
     * 用弱引用，避免 RecyclerView 回收行时持有已废弃的 View。
     */
    private static java.lang.ref.WeakReference<SwipeRow> sOpenRow;

    public SwipeRow(Context c, int layoutRes) {
        super(c);
        delSpan = dp(88);
        android.content.SharedPreferences sp =
                c.getSharedPreferences("settings", Context.MODE_PRIVATE);
        sens = sp.getFloat("sens", 1f);
        haptic = sp.getBoolean("haptic", true);

        // 背景层：A 档显示「删除」，B 档显示「完成 / 未完成」，两者位置重叠、交叉淡入
        hintToggle = makeHint(c, "完成", 0xFF16A34A, R.drawable.ic_swipe_check);
        LayoutParams rp = new LayoutParams(delSpan, LayoutParams.MATCH_PARENT);
        rp.gravity = Gravity.END;
        rp.rightMargin = dp(6);
        hintToggle.setVisibility(INVISIBLE);
        hintToggle.setClickable(false);
        hintToggle.setFocusable(false);
        addView(hintToggle, rp);

        hintDelete = makeHint(c, "删除", 0xFFDC2626, R.drawable.ic_swipe_delete);
        LayoutParams lp = new LayoutParams(delSpan, LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.END;
        lp.rightMargin = dp(6);
        hintDelete.setVisibility(INVISIBLE);
        addView(hintDelete, lp);

        // A 档停住后，点删除才真正删除；B 档由松手自动执行，不需要点
        hintDelete.setOnClickListener(v -> {
            if (opened) commit(ACTION_DELETE, -getWidth());
        });

        content = LayoutInflater.from(c).inflate(layoutRes, this, false);
        ViewGroup.LayoutParams clp = content.getLayoutParams();
        int bottom = 0;
        if (clp instanceof ViewGroup.MarginLayoutParams) {
            bottom = ((ViewGroup.MarginLayoutParams) clp).bottomMargin;
        }
        bottomMargin = bottom;
        addView(content, new LayoutParams(clp));
    }

    private static TextView makeHint(Context c, String text, int color, int icon) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(11);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setCompoundDrawablesWithIntrinsicBounds(0, icon, 0, 0);
        t.setCompoundDrawablePadding(dpi(c, 3));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor((color & 0x00FFFFFF) | 0x14000000);
        bg.setCornerRadius(dpi(c, 12));
        t.setBackground(bg);
        t.setTextColor(color);
        t.setAlpha(0f);
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    public View content() { return content; }

    public void setListener(Listener l) { listener = l; }

    public void bind(long id, boolean isDone) {
        if (id != boundId) {
            boundId = id;
            close();
        } else if (opened && !isCurrentOpenRow()) {
            // 复用 / 数据刷新后，只有"当前全局展开的那一行"才允许保持展开
            close();
        }
        done = isDone;
        hintToggle.setText(done ? "未完成" : "完成");
        hintToggle.setTextColor(done ? 0xFF8A90A0 : 0xFF16A34A);
    }

    /** RecyclerView 回收 / 列表重置时调用，彻底清掉滑动残留。 */
    public void reset() {
        content.animate().cancel();
        hintDelete.animate().cancel();
        hintToggle.animate().cancel();
        close();
        dragging = false;
        decided = false;
        tickA = false;
        tickB = false;
        lastAx = 0f;
        if (tracker != null) {
            tracker.recycle();
            tracker = null;
        }
    }

    /** 是否已滑开、删除按钮可点。 */
    public boolean isOpen() { return opened; }

    /** 外部调用：把滑开的行收回去。 */
    public void closeQuietly() { snapBack(); }

    /** 收起当前展开的行（列表滚动、点击其它条目、onPause 时调用）。 */
    public static void closeOpenRow() {
        SwipeRow r = sOpenRow == null ? null : sOpenRow.get();
        if (r != null && r.opened) r.snapBack();
        sOpenRow = null;
    }

    private void markOpened() {
        SwipeRow prev = sOpenRow == null ? null : sOpenRow.get();
        if (prev != null && prev != this && prev.opened) prev.snapBack();
        sOpenRow = new java.lang.ref.WeakReference<>(this);
    }

    private void clearOpened() {
        if (sOpenRow != null && sOpenRow.get() == this) sOpenRow = null;
    }

    private boolean isCurrentOpenRow() {
        SwipeRow r = sOpenRow == null ? null : sOpenRow.get();
        return r == this;
    }

    /** A 档停位：露出删除按钮即可。 */
    private float nearX() { return -(delSpan + dp(10)); }

    private void close() {
        opened = false;
        clearOpened();
        content.setTranslationX(0f);
        content.setScaleX(1f);
        content.setScaleY(1f);
        content.setRotation(0f);
        hintDelete.setVisibility(INVISIBLE);
        hintToggle.setVisibility(INVISIBLE);
        hintDelete.setAlpha(0f);
        hintToggle.setAlpha(0f);
        dx = 0f;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // 离开窗口（被回收 / 滑出屏幕）时不要留下静态引用
        if (opened) close();
        if (tracker != null) {
            tracker.recycle();
            tracker = null;
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                aPx = dp(THRESHOLD_A_DP) * sens;
                bPx = dp(THRESHOLD_B_DP) * sens;
                // 已滑开时，点卡片区域先收起；点右侧删除按钮交给按钮自己处理
                if (opened && getWidth() > 0 && e.getX() < getWidth() - Math.abs(dx)) {
                    closeQuietly();
                }
                downX = e.getX();
                downY = e.getY();
                decided = false;
                dragging = false;
                dx = 0f;
                lastAx = 0f;
                tickA = false;
                tickB = false;
                if (tracker == null) tracker = VelocityTracker.obtain();
                else tracker.clear();
                tracker.addMovement(e);
                return false;
            case MotionEvent.ACTION_MOVE: {
                if (tracker != null) tracker.addMovement(e);
                if (decided) return dragging;
                float mx = e.getX() - downX;
                float my = e.getY() - downY;
                if (Math.abs(mx) > dp(8) && Math.abs(mx) > Math.abs(my) * 1.35f) {
                    decided = true;
                    dragging = true;
                    if (mx < 0) {
                        hintDelete.setVisibility(VISIBLE);
                        hintToggle.setVisibility(VISIBLE);
                        hintDelete.setAlpha(0f);
                        hintToggle.setAlpha(0f);
                    }
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                if (Math.abs(my) > dp(8)) {
                    decided = true;
                    dragging = false;
                }
                return false;
            }
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!dragging) return super.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                if (tracker != null) tracker.addMovement(e);
                float raw = e.getX() - downX;
                float ax = Math.abs(raw);
                lastAx = ax;

                // 1:1 跟随；超过 B 之后加摩擦，避免卡片飞出屏幕
                float x;
                if (ax <= bPx) {
                    x = raw;
                } else {
                    float extra = (ax - bPx) * OVER_B_FRICTION;
                    x = Math.signum(raw) * (bPx + extra);
                }
                float lift = Math.min(1f, ax / dp(30));
                dx = x;
                content.setTranslationX(x);
                content.setScaleX(1f - 0.022f * lift);
                content.setScaleY(1f - 0.022f * lift);
                // 跟手倾斜：滑得越多，卡片侧得越明显，最多 2.6 度
                content.setRotation(Math.max(-2.6f, Math.min(2.6f, x / 42f)));

                // A 档「删除」淡入；接近 B 时淡出，同时「完成」淡入
                float farProg = bPx <= 0 ? 0f : Math.max(0f, (ax - bPx * 0.75f) / (bPx * 0.25f));
                farProg = Math.min(1f, farProg);
                float delProg = Math.min(1f, ax / (delSpan * 0.8f)) * (1f - farProg);
                hintDelete.setAlpha(delProg);
                hintToggle.setAlpha(farProg);

                // 触感：过 A 轻一点，过 B 重一点
                if (!tickA && ax >= aPx) {
                    if (haptic) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    tickA = true;
                } else if (tickA && ax < aPx) {
                    tickA = false;
                }
                if (!tickB && ax >= bPx) {
                    if (haptic) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    tickB = true;
                } else if (tickB && ax < bPx) {
                    tickB = false;
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                if (tracker != null) {
                    tracker.addMovement(e);
                    tracker.computeCurrentVelocity(1000);
                }
                float vx = tracker != null ? tracker.getXVelocity() : 0f;
                boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                boolean left = dx < 0;
                boolean flingLeft = vx < -700;

                if (!up) {
                    // 被系统取消：一律回弹，绝不执行动作
                    snapBack();
                } else if (left && lastAx >= bPx) {
                    // B 档：松手直接完成 / 取消完成，卡片飞出后收回
                    commit(ACTION_TOGGLE, -getWidth());
                } else if (left && (lastAx >= aPx || (flingLeft && lastAx > aPx * 0.5f))) {
                    // A 档：停住，只露删除按钮，等用户点击
                    settleTo(nearX());
                    if (haptic) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                } else {
                    snapBack();
                }
                if (tracker != null) {
                    tracker.recycle();
                    tracker = null;
                }
                dragging = false;
                return true;
            }
        }
        return true;
    }

    /** A 档吸附：卡片停住、删除按钮可点击。 */
    private void settleTo(final float target) {
        opened = true;
        markOpened();
        dx = target;
        hintDelete.setVisibility(VISIBLE);
        hintToggle.setVisibility(INVISIBLE);
        hintToggle.setAlpha(0f);
        content.animate().translationX(target).scaleX(1f).scaleY(1f).rotation(0f)
                .setDuration(240).setInterpolator(new DecelerateInterpolator(1.8f)).start();
        float ax = Math.abs(target);
        float alpha = Math.min(1f, ax / (delSpan * 0.8f));
        hintDelete.animate().alpha(alpha).setDuration(200).start();
    }

    private void snapBack() {
        opened = false;
        clearOpened();
        content.animate().translationX(0f).scaleX(1f).scaleY(1f).rotation(0f).setDuration(200)
                .setInterpolator(new DecelerateInterpolator(1.8f)).start();
        hintDelete.animate().alpha(0f).setDuration(160).withEndAction(() -> {
            if (!dragging) hintDelete.setVisibility(INVISIBLE);
        }).start();
        hintToggle.animate().alpha(0f).setDuration(160).withEndAction(() -> {
            if (!dragging) hintToggle.setVisibility(INVISIBLE);
        }).start();
        dx = 0f;
    }

    private void commit(final int action, float targetX) {
        opened = false;
        clearOpened();
        content.animate().translationX(targetX).scaleX(1f).scaleY(1f).rotation(0f).setDuration(170)
                .setInterpolator(new AccelerateInterpolator(1.4f)).withEndAction(() -> {
            content.setTranslationX(0f);
            content.setScaleX(1f);
            content.setScaleY(1f);
            content.setRotation(0f);
            hintDelete.setVisibility(INVISIBLE);
            hintToggle.setVisibility(INVISIBLE);
            hintDelete.setAlpha(0f);
            hintToggle.setAlpha(0f);
            dx = 0f;
            if (listener != null) listener.onAction(action);
        }).start();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        int contentH = content.getMeasuredHeight();
        if (contentH > 0) {
            int exactH = MeasureSpec.makeMeasureSpec(contentH, MeasureSpec.EXACTLY);
            hintDelete.measure(MeasureSpec.makeMeasureSpec(delSpan, MeasureSpec.EXACTLY), exactH);
            hintToggle.measure(MeasureSpec.makeMeasureSpec(delSpan, MeasureSpec.EXACTLY), exactH);
            setMeasuredDimension(getMeasuredWidth(), contentH + bottomMargin);
        }
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private static int dpi(Context c, int v) {
        return Math.round(c.getResources().getDisplayMetrics().density * v);
    }
}
