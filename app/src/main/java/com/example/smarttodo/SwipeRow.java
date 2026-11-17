package com.example.smarttodo;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
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
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 列表行容器 —— 左滑删除（Swipe to Delete）：
 *
 *   阈值 A（默认 80dp）：拖过 A 松手 → 吸附停住，右侧露出红色「删除」按钮，点击才删除；
 *   阈值 B（行宽的一半）：拖过 B 松手 → 直接标记完成（回调 ACTION_TOGGLE），删除按钮隐藏，卡片收回。
 *   不足 A 松手 → 弹回原位，不展开任何按钮。
 *
 * 拖动全程 1:1 跟随手指，手指松开后由 ease-out 动画收尾（吸附 / 回弹约 300ms）。
 * 同一时刻只允许一行展开：展开新行先收旧行；列表滚动、点击其它条目、页面 onPause 都会收起。
 * 行被 RecyclerView 回收或复用时统一走 reset()/bind() 恢复高度与透明度，不留滑动残留。
 *
 * 想调参数：改下面的 THRESHOLD_A_DP 与 THRESHOLD_B_RATIO，
 * 用户设置里的「灵敏度」(sens) 会与 A 相乘，整体放大/缩小。
 */
public class SwipeRow extends FrameLayout {

    public static final int ACTION_TOGGLE = 1;
    public static final int ACTION_DELETE = 2;

    // ===== 可调参数：两档阈值 =====
    /** 阈值 A：拖过则松手吸附，露出【删除】按钮（单位 dp，会再乘以设置里的灵敏度）。 */
    public static int THRESHOLD_A_DP = 80;
    /** 阈值 B：占行宽的比例，拖过则松手直接标记完成（0.5 = 一半宽度，必须大于 A 的等效距离）。 */
    public static final float THRESHOLD_B_RATIO = 0.5f;

    /** 吸附 / 回弹动画时长。 */
    private static final int SNAP_MS = 300;
    /** 滑出并淡出的时长。 */
    private static final int EXIT_MS = 180;
    /** 高度收缩到 0、下方行补位的时长。 */
    private static final int COLLAPSE_MS = 280;

    public interface Listener { void onAction(int action); }

    private final View content;
    private final TextView hintDelete;
    private final int delSpan;
    private final float sens;
    private final boolean haptic;
    private final int bottomMargin;

    private Listener listener;
    private long boundId = -1;

    private float downX, downY;
    private boolean decided;
    private boolean dragging;
    private float dx;
    /** 手指实际滑过的距离（未加摩擦），松手判定用这个。 */
    private float lastAx;
    private boolean tickA, tickB;
    private boolean opened;
    private VelocityTracker tracker;

    /** 删除动画状态：避免重复触发，也用于复用时的视觉恢复。 */
    private boolean deleting;
    private boolean deleteNotified;
    /** >= 0 时覆盖 onMeasure 的高度，用于高度收缩动画。 */
    private int forcedHeight = -1;

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

        hintDelete = makeHint(c, "删除", 0xFFDC2626, R.drawable.ic_swipe_delete);
        LayoutParams lp = new LayoutParams(delSpan, LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.END;
        lp.rightMargin = dp(6);
        hintDelete.setVisibility(INVISIBLE);
        addView(hintDelete, lp);

        // 吸附停住后，点删除按钮才真正删除
        hintDelete.setOnClickListener(v -> {
            if (opened) animateDelete();
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
        // 上一轮删除动画把高度压到了 0，复用到新数据前必须恢复原始高度与透明度
        if (deleting || forcedHeight >= 0 || content.getAlpha() < 1f) {
            restoreIdle();
        }
        if (id != boundId) {
            boundId = id;
            close();
        } else if (opened && !isCurrentOpenRow()) {
            // 复用 / 数据刷新后，只有"当前全局展开的那一行"才允许保持展开
            close();
        }
    }

    /** RecyclerView 回收 / 列表重置时调用，彻底清掉滑动残留。 */
    public void reset() {
        content.animate().cancel();
        hintDelete.animate().cancel();
        restoreIdle();
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

    /** 恢复成"未滑动、未删除"的初始视觉，供复用。 */
    private void restoreIdle() {
        content.animate().cancel();
        hintDelete.animate().cancel();
        content.setAlpha(1f);
        content.setTranslationX(0f);
        content.setRotation(0f);
        content.setScaleX(1f);
        content.setScaleY(1f);
        hintDelete.setAlpha(0f);
        hintDelete.setVisibility(INVISIBLE);
        forcedHeight = -1;
        deleting = false;
        deleteNotified = false;
        requestLayout();
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

    /** A 档停位：刚好露出删除按钮。 */
    private float nearX() { return -(delSpan + dp(8)); }

    private void close() {
        opened = false;
        clearOpened();
        content.setTranslationX(0f);
        content.setScaleX(1f);
        content.setScaleY(1f);
        content.setRotation(0f);
        hintDelete.setVisibility(INVISIBLE);
        hintDelete.setAlpha(0f);
        dx = 0f;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // 离开窗口（被回收 / 滑出屏幕）时不要留下静态引用
        if (opened) close();
        if (deleting && !deleteNotified) {
            // 动画被系统中断时补一次回调，避免数据层面漏删
            finishDelete();
        }
        if (tracker != null) {
            tracker.recycle();
            tracker = null;
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (deleting) return false;
                aPx = dp(THRESHOLD_A_DP) * sens;
                bPx = (getWidth() > 0 ? getWidth() : dp(320)) * THRESHOLD_B_RATIO;
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
                if (mx < 0 && Math.abs(mx) > dp(8) && Math.abs(mx) > Math.abs(my) * 1.35f) {
                    decided = true;
                    dragging = true;
                    hintDelete.setVisibility(VISIBLE);
                    hintDelete.setAlpha(0f);
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                if (Math.abs(mx) > dp(8) && Math.abs(my) > dp(8)) {
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
        if (!dragging || deleting) return super.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                if (tracker != null) tracker.addMovement(e);
                float raw = e.getX() - downX;
                if (raw > 0) return true;
                float ax = Math.abs(raw);
                lastAx = ax;

                // 全程 1:1 跟手，最多滑到整行宽度，不会越界乱飘
                float x = Math.max(raw, -getWidth());
                float lift = Math.min(1f, ax / dp(30));
                dx = x;
                content.setTranslationX(x);
                content.setScaleX(1f - 0.022f * lift);
                content.setScaleY(1f - 0.022f * lift);
                // 跟手倾斜：滑得越多，卡片侧得越明显，最多 2.6 度
                content.setRotation(Math.max(-2.6f, Math.min(2.6f, x / 42f)));

                // 删除按钮：接近 A 时逐步显形，到 A 完全露出；进入 B 档前再渐隐（B 档执行完成，不再展示删除）
                float show = Math.min(1f, Math.max(0f, (ax - aPx * 0.4f) / (aPx * 0.6f)));
                float hide = ax > bPx * 0.85f
                        ? Math.max(0f, (bPx - ax) / (bPx * 0.15f)) : 1f;
                hintDelete.setAlpha(show * hide);

                // 触感：过 A 轻一点，过 B（半宽）重一点
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
                    // B 档：拖过一半宽度，松手直接标记完成，删除按钮隐藏、卡片收回
                    if (haptic) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    completeBySwipe();
                } else if (left && (lastAx >= aPx || (flingLeft && lastAx > aPx * 0.5f))) {
                    // A 档：停住，只露删除按钮，等用户点击
                    settleTo(nearX());
                    if (haptic) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
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

    /** A 档吸附：卡片停住、删除按钮可点击（ease-out，约 300ms）。 */
    private void settleTo(final float target) {
        opened = true;
        markOpened();
        dx = target;
        hintDelete.setVisibility(VISIBLE);
        content.animate().translationX(target).scaleX(1f).scaleY(1f).rotation(0f)
                .setDuration(SNAP_MS)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        float alpha = Math.min(1f, Math.abs(target) / (delSpan * 0.8f));
        hintDelete.animate().alpha(alpha).setDuration(SNAP_MS)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
    }

    /** 不足 A：弹回原位（ease-out）。 */
    private void snapBack() {
        if (deleting) return;
        opened = false;
        clearOpened();
        content.animate().translationX(0f).scaleX(1f).scaleY(1f).rotation(0f)
                .setDuration(SNAP_MS)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        hintDelete.animate().alpha(0f).setDuration(160).withEndAction(() -> {
            if (!dragging) hintDelete.setVisibility(INVISIBLE);
        }).start();
        dx = 0f;
    }

    /**
     * B 档：直接标记完成（ACTION_TOGGLE）。隐藏删除按钮，卡片 ease-out 收回原位。
     * 业务逻辑完全复用原有完成/取消完成分支，这里只负责手势判定后的视觉收尾。
     */
    private void completeBySwipe() {
        opened = false;
        clearOpened();
        hintDelete.animate().alpha(0f).setDuration(120).withEndAction(() -> {
            if (!dragging) hintDelete.setVisibility(INVISIBLE);
        }).start();
        content.animate().translationX(0f).scaleX(1f).scaleY(1f).rotation(0f)
                .setDuration(SNAP_MS)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        dx = 0f;
        if (listener != null) listener.onAction(ACTION_TOGGLE);
    }

    /**
     * 点删除按钮（A 档吸附后）：整行向左滑出并淡出 → 高度收缩到 0（下方行平滑上移）→ 回调删除。
     */
    private void animateDelete() {
        if (deleting) return;
        deleting = true;
        deleteNotified = false;
        opened = false;
        clearOpened();
        final int w = getWidth() > 0 ? getWidth() : dp(360);
        hintDelete.animate().alpha(0f).setDuration(120).start();
        content.animate().translationX(-w).rotation(0f).alpha(0f)
                .setDuration(EXIT_MS)
                .setInterpolator(new DecelerateInterpolator(1.4f))
                .withEndAction(this::collapseThenDelete).start();
    }

    /** 滑出结束后收缩高度，让下方行补位。 */
    private void collapseThenDelete() {
        if (deleteNotified) return;
        final int from = getHeight();
        if (from <= 0) {
            finishDelete();
            return;
        }
        ValueAnimator va = ValueAnimator.ofInt(from, 0);
        va.setDuration(COLLAPSE_MS);
        va.setInterpolator(new DecelerateInterpolator(1.6f));
        va.addUpdateListener(a -> {
            forcedHeight = (int) a.getAnimatedValue();
            requestLayout();
        });
        va.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                forcedHeight = 0;
                requestLayout();
                finishDelete();
            }
        });
        va.start();
    }

    /** 通知业务层删除数据；视图在数据刷新后被移除，复用时由 bind()/reset() 恢复。 */
    private void finishDelete() {
        if (deleteNotified) return;
        deleteNotified = true;
        deleting = false;
        hintDelete.setVisibility(INVISIBLE);
        hintDelete.setAlpha(0f);
        if (listener != null) listener.onAction(ACTION_DELETE);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        if (forcedHeight >= 0) {
            setMeasuredDimension(getMeasuredWidth(), forcedHeight);
            return;
        }
        int contentH = content.getMeasuredHeight();
        if (contentH > 0) {
            int exactH = MeasureSpec.makeMeasureSpec(contentH, MeasureSpec.EXACTLY);
            hintDelete.measure(MeasureSpec.makeMeasureSpec(delSpan, MeasureSpec.EXACTLY), exactH);
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
