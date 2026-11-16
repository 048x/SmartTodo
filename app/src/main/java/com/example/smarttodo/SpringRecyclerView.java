package com.example.smarttodo;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

/**
 * 带 iOS 风味回弹的 RecyclerView，完全沿用原 SpringListView 的物理参数：
 * 越界拖动按橡皮筋曲线位移；松手后用临界阻尼弹簧（阻尼比 = 1.0）弹回，
 * 松手瞬间的手势速度作为弹簧初速度，快速甩动会有惯性延续。
 *
 * 与 SwipeRow 的分工不变：行的横向拖动会 requestDisallowInterceptTouchEvent，
 * 这里只处理纵向越界回弹。
 */
public class SpringRecyclerView extends RecyclerView {

    /** 弹簧刚度，越大回弹越快。阻尼由刚度推导，保证阻尼比恒为 1.0。 */
    private static final float STIFFNESS = 210f;
    /** 橡皮筋阻尼系数，越小越"拉不动"。 */
    private static final float RUBBER_FACTOR = 0.55f;

    private float offset;
    private float velocity;
    private float rawOverscroll;
    private float lastY;
    private boolean springing;
    private long lastFrameNanos;

    private VelocityTracker tracker;

    private final Choreographer.FrameCallback springFrame = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!springing) return;

            if (lastFrameNanos == 0) lastFrameNanos = frameTimeNanos - 16_000_000L;
            float dt = (frameTimeNanos - lastFrameNanos) / 1_000_000_000f;
            lastFrameNanos = frameTimeNanos;
            if (dt > 0.032f) dt = 0.032f;

            // 固定步长积分，保证不同帧率下手感一致
            int steps = Math.max(1, (int) Math.ceil(dt / 0.008f));
            float h = dt / steps;
            float damping = 2f * (float) Math.sqrt(STIFFNESS); // 阻尼比 = 1.0

            for (int i = 0; i < steps; i++) {
                float accel = -STIFFNESS * offset - damping * velocity;
                velocity += accel * h;
                offset += velocity * h;
            }

            if (Math.abs(offset) < 0.6f && Math.abs(velocity) < 8f) {
                offset = 0f;
                velocity = 0f;
                springing = false;
                setTranslationY(0f);
                return;
            }

            setTranslationY(offset);
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public SpringRecyclerView(Context context) {
        super(context);
        init();
    }

    public SpringRecyclerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SpringRecyclerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setOverScrollMode(View.OVER_SCROLL_NEVER);
        setVerticalScrollBarEnabled(false);
        // 关闭默认增删动画：数据频繁刷新时不抖动，行内容由 stable id + diff 精确更新
        setItemAnimator(null);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                stopSpring();
                rawOverscroll = offset;
                lastY = ev.getY();
                if (tracker == null) tracker = VelocityTracker.obtain();
                else tracker.clear();
                tracker.addMovement(ev);
                break;

            case MotionEvent.ACTION_MOVE: {
                if (tracker != null) tracker.addMovement(ev);
                float y = ev.getY();
                float dy = y - lastY;
                lastY = y;

                boolean atTop = isAtTop();
                boolean atBottom = isAtBottom();

                if (offset != 0f || rawOverscroll != 0f || (atTop && dy > 0) || (atBottom && dy < 0)) {
                    rawOverscroll += dy;
                    float limit = getHeight() * 1.2f;
                    if (rawOverscroll > limit) rawOverscroll = limit;
                    if (rawOverscroll < -limit) rawOverscroll = -limit;
                    if (atTop && rawOverscroll < 0) rawOverscroll = 0;
                    if (atBottom && rawOverscroll > 0) rawOverscroll = 0;

                    offset = rubberBand(rawOverscroll);
                    setTranslationY(offset);
                    return true;
                }
                break;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (tracker != null) tracker.addMovement(ev);
                if (offset != 0f || rawOverscroll != 0f) {
                    float releaseVelocity = 0f;
                    if (tracker != null) {
                        tracker.computeCurrentVelocity(1000);
                        releaseVelocity = tracker.getYVelocity();
                    }
                    rawOverscroll = 0f;
                    startSpring(offset, releaseVelocity);
                    return true;
                }
                break;
            }

            default:
                break;
        }
        return super.onTouchEvent(ev);
    }

    /** iOS 橡皮筋：越拖越沉，但永远不会真的拖走。 */
    private float rubberBand(float distance) {
        float dim = getHeight();
        if (dim <= 0f) return 0f;
        float sign = distance >= 0 ? 1f : -1f;
        float abs = Math.abs(distance);
        return sign * (1f - 1f / (abs * RUBBER_FACTOR / dim + 1f)) * dim;
    }

    private void startSpring(float startOffset, float startVelocity) {
        offset = startOffset;
        if (Math.abs(startVelocity) < 60f) startVelocity = 0f;
        if (startVelocity > 8000f) startVelocity = 8000f;
        if (startVelocity < -8000f) startVelocity = -8000f;
        velocity = startVelocity;
        lastFrameNanos = 0;
        if (!springing) {
            springing = true;
            Choreographer.getInstance().postFrameCallback(springFrame);
        }
    }

    private void stopSpring() {
        if (springing) {
            springing = false;
            Choreographer.getInstance().removeFrameCallback(springFrame);
        }
        offset = 0f;
        velocity = 0f;
        setTranslationY(0f);
    }

    private boolean isAtTop() {
        return !canScrollVertically(-1);
    }

    private boolean isAtBottom() {
        return !canScrollVertically(1);
    }

    @Override
    protected void onDetachedFromWindow() {
        stopSpring();
        super.onDetachedFromWindow();
    }
}
