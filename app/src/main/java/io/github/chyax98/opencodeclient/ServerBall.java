package io.github.chyax98.opencodeclient;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.HapticFeedbackConstants;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.Collections;

/**
 * 悬浮球：人在全屏 Web UI 里时，唯一"看得见"的回管理页入口。
 *
 * <p>为什么是球不是横条：opencode 的 Web UI 是全屏自绘的，标题栏可以在顶（`titlebar.tsx`）也可以在底
 * （`mobileTitlebarPosition`），底部还有 composer —— 一条横条必然压住其中之一；一个 48dp 的圆还能拖走。
 *
 * <p>行为：点 = 先弹预览面板（不直接换页，防误触）；拖 = 换位置，松手吸附最近的左右边，
 * 位置按屏幕比例落盘（跨启动、跨服务器都认）；闲置 5 秒淡到 40%，按住立刻回到不透明。
 *
 * <p>它是 `root` 的**第三个**孩子（WebView、setup 之后）：绝不能插到 setup 前面，
 * 否则 `ShellFlowTest.setupBox()`（`root.getChildAt(1)`）那一串断言全线崩。
 */
@SuppressLint("ViewConstructor")
public class ServerBall extends FrameLayout {
    private static final long FADE_AFTER_MS = 5_000;
    private static final float FADED_ALPHA = 0.72f; // 0.4 那档把标识淡没了（真机看一眼就懂）
    private static final String KEY_X = "ball_x"; // 0..1 的比例，别存像素：换分辨率/分屏后还站得住
    private static final String KEY_Y = "ball_y";

    interface Listener {
        void onTapBall();
    }

    private final SharedPreferences prefs;
    private final Listener listener;
    private final int size;
    private final int margin;
    private final int slop;
    private final Runnable fade = () -> animate().alpha(FADED_ALPHA).setDuration(400).start();
    private float downRawX; // 手指按下去那一帧的位置：点击判定只跟它比
    private float downRawY;
    private float lastRawX; // 上一帧的位置：算拖拽增量用
    private float lastRawY;

    public ServerBall(Context context, SharedPreferences prefs, Listener listener) {
        super(context);
        this.prefs = prefs;
        this.listener = listener;
        this.size = dp(48); // 48dp：无障碍的最小触控目标（球本来就是个圆，大这 4dp 看不出来）
        this.margin = dp(24); // 离屏幕边远一点：别压在那条系统返回手势的感应边上
        this.slop = ViewConfiguration.get(context).getScaledTouchSlop();
        setId(R.id.server_ball);
        setContentDescription("管理服务器");
        setClickable(true); // 读屏要把它当按钮播报、能双击触发，就得 clickable + focusable
        setFocusable(true);
        build();
        attachGestures();
    }

    private void build() {
        // 深色圆底（跟图标底板同色）+ 官方标识的字形 + 海拔。不再叠那个 accent 小点：
        // 它永远不变色，"这里有服务器"这层意思不成立，纯装饰。
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(getResources().getColor(R.color.icon_background, null));
        circle.setStroke(Math.max(1, dp(1)), getResources().getColor(R.color.shell_border, null));
        setBackground(circle);
        setElevation(dp(6)); // ≈ elevation-floating：不然在浅色页上像贴平的一块

        ImageView icon = new ImageView(getContext());
        icon.setImageResource(R.drawable.ic_mark_glyph); // 官方标识（和连接页顶部同源，去掉底板那层）
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); // 读屏只念外面这一层
        int inset = dp(6); // 12dp 内缩 + 标识自带留白 = 白框只剩一半，看着像个方点
        FrameLayout.LayoutParams iconParams =
                new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        iconParams.setMargins(inset, inset, inset, inset);
        addView(icon, iconParams);
    }

    private void attachGestures() {
        setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = lastRawX = event.getRawX();
                    downRawY = lastRawY = event.getRawY();
                    setAlpha(1f);
                    if (getHandler() != null) getHandler().removeCallbacks(fade);
                    return true; // 自己吃掉：拖的时候别让 WebView 也收到
                case MotionEvent.ACTION_MOVE:
                    moveBy(event.getRawX() - lastRawX, event.getRawY() - lastRawY);
                    lastRawX = event.getRawX();
                    lastRawY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    // 跟"按下那一帧"比，不是跟最后一帧比：拖完抬手处离最后一帧必然小于 slop，
                    // 拿它当判定的话每次拖完都会顺手弹出预览面板。
                    boolean tapped = Math.abs(event.getRawX() - downRawX) <= slop
                            && Math.abs(event.getRawY() - downRawY) <= slop;
                    if (tapped && event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
                    snapToEdge();
                    scheduleFade();
                    return true;
                default:
                    return false;
            }
        });
    }

    private void moveBy(float dx, float dy) {
        if (Math.abs(dx) <= slop && Math.abs(dy) <= slop) return;
        View parent = getParent() instanceof View ? (View) getParent() : null;
        if (parent == null) return;
        float x = clamp(getX() + dx, 0, parent.getWidth() - size);
        float y = clamp(getY() + dy, 0, parent.getHeight() - size);
        setX(x);
        setY(y);
    }

    /** 松手吸附到最近的左右边（竖直位置保持）。 */
    void snapToEdge() {
        View parent = getParent() instanceof View ? (View) getParent() : null;
        if (parent == null || parent.getWidth() == 0) return;
        float target = getX() + size / 2f < parent.getWidth() / 2f ? margin : parent.getWidth() - size - margin;
        animate().x(target).setDuration(150).start();
        // 落盘的是吸附后的目标：动画还没跑完时 getX() 还是旧位置，存它会让下次恢复跳回去。
        persistAt(target, getY());
    }

    /** 落盘左上角比例（restorePosition 也按左上角解释）：换分辨率/分屏后还站得住。 */
    private void persistAt(float x, float y) {
        View parent = getParent() instanceof View ? (View) getParent() : null;
        if (prefs == null || parent == null || parent.getWidth() == 0) return;
        prefs.edit()
                .putFloat(KEY_X, clamp(x, 0, parent.getWidth() - size) / parent.getWidth())
                .putFloat(KEY_Y, clamp(y, 0, parent.getHeight() - size) / Math.max(1, parent.getHeight()))
                .apply();
    }

    /** 按落盘的比例摆回去（没存过：**右侧中间**，用户定的默认位置）。 */
    void restorePosition(int parentWidth, int parentHeight) {
        if (parentWidth <= 0 || parentHeight <= 0) return;
        float x = prefs == null ? -1f : prefs.getFloat(KEY_X, -1f);
        float y = prefs == null ? -1f : prefs.getFloat(KEY_Y, -1f);
        if (x < 0f) {
            setX(parentWidth - size - margin); // 没存过：右侧中间
        } else {
            // 落盘的是左上角比例，不能再减 size/2 —— 否则每恢复一次就往左上漂半个球（24dp）。
            setX(clamp(x * parentWidth, margin, parentWidth - size - margin));
        }
        if (y < 0f) {
            setY(clamp(parentHeight / 2f - size / 2f, margin, parentHeight - size - margin));
        } else {
            setY(clamp(y * parentHeight, margin, parentHeight - size - margin));
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        excludeFromGesture();
    }

    /** API 29+：让系统的手势导航在这一小块上让路 —— 不然"按住球往屏幕里拖"会被当成返回手势。 */
    private void excludeFromGesture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        setSystemGestureExclusionRects(Collections.singletonList(new Rect(0, 0, getWidth(), getHeight())));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(() -> {
            if (getRootView() instanceof View) {
                View root = (View) getRootView();
                restorePosition(root.getWidth(), root.getHeight());
            }
        });
        scheduleFade();
    }

    private void scheduleFade() {
        if (getHandler() == null) return;
        getHandler().removeCallbacks(fade);
        getHandler().postDelayed(fade, FADE_AFTER_MS);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        // 自定义 onTouch 吃掉了默认的按压反馈：点一下给个轻触感，别让人怀疑"点没点上"
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        if (listener != null) listener.onTapBall();
        return true;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(value, Math.max(min, max)));
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
