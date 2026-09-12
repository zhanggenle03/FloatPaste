package com.to3g.snipasteandroid.view;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.text.Layout;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Magnifier;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.dd.ShadowLayout;
import com.to3g.snipasteandroid.R;
import com.to3g.snipasteandroid.lib.SharePasteHelper;

/**
 * 文字贴图容器。长按进入选择模式，支持两个倒水滴拖拽手柄 + 放大镜。
 */
public class TextStickerView extends FrameLayout {

    private TextView textView;
    private ScaleImage scaleImage;
    private String tag;

    // ===================== 手势参数 =====================
    private static final int TAP_SLOP = 48;
    private static final long LONG_PRESS_TIME = 400;
    private static final int GESTURE_NONE = 0;
    private static final int GESTURE_DRAG = 1;
    private int gesture = GESTURE_NONE;
    private float downX, downY;
    private long downTime;
    private boolean selectMode = false;
    /** 本轮触摸是否正在做两指捏合缩放（为 true 时独占事件，不再走拖拽/选择） */
    private boolean pinchActive = false;

    // ---------- 双击 ----------
    private long lastDownTime = 0;
    private float lastDownRawX, lastDownRawY;
    private static final long DOUBLE_TAP_TIME = 300;
    private final Runnable longPressRunnable = this::onLongPress;

    // ===================== 选中状态 =====================
    private int selStart = -1;
    private int selEnd = -1;
    private boolean hasSelection = false;
    private final Paint selPaint;

    // ===================== 手柄 =====================
    private SelectionHandleView handleStart;
    private SelectionHandleView handleEnd;
    private boolean handlesCreated = false;

    // 选择模式时贴图底部为手柄预留的透明空间（dp）。
    // 手柄高 = 22(水滴) + 14(触摸) + 6 = 42dp，留 4dp 余量。
    private static final int HANDLE_SPACE_DP = 46;
    private boolean spaceExpanded = false;

    // 拖拽手柄时的目标
    private boolean dragTargetIsStart = false;
    private boolean isDraggingHandle = false;

    // ===================== 系统放大镜（API 28+） =====================
    private Magnifier textMagnifier;
    private float lastMagX = Float.MAX_VALUE, lastMagY = Float.MAX_VALUE;

    // ===================== 按钮 =====================
    private Button actionBtn;
    private boolean btnReady = false;

    // ===================== 构造 =====================
    public TextStickerView(@NonNull Context context) {
        super(context); selPaint = initSelPaint();
    }
    public TextStickerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs); selPaint = initSelPaint();
    }
    public TextStickerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr); selPaint = initSelPaint();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // 手柄保持在文字行下方（尖点朝上），选中最后一行时会超出贴图底部，
        // 逐层关闭父容器裁剪，让越界部分直接绘制在贴图外，保证始终可见、可触摸。
        setClipChildren(false);
        ViewParent p = getParent();
        while (p instanceof ViewGroup) {
            ((ViewGroup) p).setClipChildren(false);
            p = p.getParent();
        }
    }

    private Paint initSelPaint() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0x4081D4FA); return p;
    }

    @Override protected void onFinishInflate() {
        super.onFinishInflate();
        textView = findViewById(R.id.textContent);
        scaleImage = findViewById(R.id.scaleImage);
    }

    public void setStickerTag(@NonNull String tag) { this.tag = tag; }
    @NonNull public String getStickerTag() { return tag; }

    // ===================== 触摸 =====================
    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (isTouchOnScaleImage(event)) return false;
        int masked = event.getActionMasked();
        // 第二根手指落下 → 交给两指捏合缩放，拦截后续事件由本 View 消费
        if (masked == MotionEvent.ACTION_POINTER_DOWN && !pinchActive
                && SharePasteHelper.handlePinchGesture(tag, findBody(), event)) {
            pinchActive = true;
            removeCallbacks(longPressRunnable); // 捏合不可能是长按
            return true;
        }
        if (pinchActive) return true;
        if (masked == MotionEvent.ACTION_DOWN) {
            float rx = event.getRawX(), ry = event.getRawY();
            long now = System.currentTimeMillis();
            if (now - lastDownTime < DOUBLE_TAP_TIME
                    && Math.abs(rx - lastDownRawX) < TAP_SLOP
                    && Math.abs(ry - lastDownRawY) < TAP_SLOP) {
                lastDownTime = 0;
                if (tag != null) SharePasteHelper.toggleOpacitySlider(tag);
                return true;
            }
            lastDownTime = now; lastDownRawX = rx; lastDownRawY = ry;
        }
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        // 捏合进行中不得再走手柄判定分支：捏合时手指可能移到右下角手柄上方，若把手柄判定当成
        // 「这一下不是给我的」而 return false，捏合会被腰斩 —— 贴图停在半途的变换里。
        if (!pinchActive && isTouchOnScaleImage(event)) return false;
        int masked = event.getActionMasked();
        if (masked == MotionEvent.ACTION_POINTER_DOWN
                && SharePasteHelper.handlePinchGesture(tag, findBody(), event)) {
            pinchActive = true; // onInterceptTouchEvent 可能已处理过同一事件，handlePinchGesture 对它是幂等的
        }
        if (pinchActive) {
            if (masked != MotionEvent.ACTION_POINTER_DOWN) {
                SharePasteHelper.handlePinchGesture(tag, findBody(), event);
            }
            if (masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL) {
                pinchActive = false;
            }
            return true; // 捏合期间独占触摸：松手也不落到拖拽 / 长按选择逻辑
        }
        switch (masked) {
            case MotionEvent.ACTION_DOWN:  return onDown(event);
            case MotionEvent.ACTION_MOVE:  return onMove(event);
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: return onUp(event);
        }
        return super.onTouchEvent(event);
    }

    private boolean onDown(MotionEvent e) {
        downX = e.getX(); downY = e.getY();
        downTime = System.currentTimeMillis();
        gesture = GESTURE_NONE;
        isDraggingHandle = false;

        if (selectMode) {
            // 文字上点击 → 重设选区起点（手柄的事件由手柄自己消费）
            selStart = charAt(downX, downY);
            selEnd = selStart;
            hasSelection = false;
            updateBtn();
            positionHandles();
            // 按住时直接用手指位置显示放大镜（转为 textView 相对坐标）
            if (textMagnifier != null && textView != null) {
                float tvX = downX - textView.getLeft();
                float tvY = downY - textView.getTop();
                textMagnifier.show(tvX, tvY);
            }
            invalidate();
            return true;
        }

        clearSelection();
        removeCallbacks(longPressRunnable);
        postDelayed(longPressRunnable, LONG_PRESS_TIME);

        // 预初始化拖拽基线，保证 MOVE 中位移计算从当前触点开始
        View body = findBody();
        if (tag != null && body != null) {
            SharePasteHelper.initializeDrag(tag, body, lastDownRawX, lastDownRawY);
        }
        return true;
    }

    private boolean onMove(MotionEvent e) {
        if (selectMode) {
            if (!isDraggingHandle) {
                updateSel(e);
                positionHandles();
            }
            return true;
        }
        float dx = e.getX() - downX, dy = e.getY() - downY;
        if (Math.hypot(dx, dy) > TAP_SLOP
                && System.currentTimeMillis() - downTime < LONG_PRESS_TIME) {
            removeCallbacks(longPressRunnable);
            gesture = GESTURE_DRAG;
            handleDrag(e);
            return true;
        }
        if (gesture == GESTURE_DRAG) { handleDrag(e); return true; }
        return true;
    }

    private boolean onUp(MotionEvent e) {
        removeCallbacks(longPressRunnable);
        if (gesture == GESTURE_DRAG) finishDrag(e);
        // 选择模式下松手 -> 隐藏放大镜（手柄拖拽松手由 HandleDragListener.onDragEnd 自行隐藏）
        if (selectMode && !isDraggingHandle) {
            dismissMagnifier();
        }
        gesture = GESTURE_NONE; isDraggingHandle = false;
        return true;
    }

    // ===================== 长按 → 选择模式 =====================
    private void onLongPress() {
        if (textView == null || textView.getText().length() == 0) return;
        selectMode = true;
        int off = charAt(downX, downY);
        selStart = off; selEnd = off; hasSelection = false;
        ensureHandles();
        // 贴图底部预留手柄空间（文字区不变，新增透明留白），随后重新布局完成再定位手柄
        expandSpaceForHandles();
        positionHandles();
        updateBtn();
        invalidate();
    }

    // ===================== 选择更新 =====================
    private void updateSel(MotionEvent e) {
        if (textView == null || textView.getLayout() == null) return;
        int off = charAt(e.getX(), e.getY());
        if (selEnd == off) return;
        selEnd = off;
        hasSelection = Math.abs(selStart - selEnd) > 0;
        invalidate(); updateBtn();
    }

    private int charAt(float x, float y) {
        float tvX = x - textView.getLeft(), tvY = y - textView.getTop();
        int off = textView.getOffsetForPosition(tvX, tvY);
        if (off < 0) off = 0;
        return Math.min(off, textView.getText().length());
    }

    // ===================== 手柄 =====================
    private void ensureHandles() {
        if (handlesCreated) return;
        handlesCreated = true;

        handleStart = new SelectionHandleView(getContext(), true);
        handleEnd = new SelectionHandleView(getContext(), false);

        handleStart.setOnDragListener(new HandleDragListener(true));
        handleEnd.setOnDragListener(new HandleDragListener(false));

        handleStart.setVisibility(GONE);
        handleEnd.setVisibility(GONE);
        addView(handleStart);
        addView(handleEnd);

        // 系统原生放大镜（仅 API 28+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && textView != null) {
            textMagnifier = new Magnifier(textView);
        }
    }

    private class HandleDragListener implements SelectionHandleView.OnDragListener {
        final boolean isStart;
        HandleDragListener(boolean isStart) { this.isStart = isStart; }

        @Override public void onDragStart(boolean isStart) {
            isDraggingHandle = true;
            dragTargetIsStart = isStart;
            lastMagX = lastMagY = Float.MAX_VALUE; // 强制本次拖拽首帧刷新放大镜
            showMagnifier();
        }

        @Override public void onDrag(float rawX, float rawY) {
            if (textView == null || textView.getLayout() == null) return;

            // 1. 根据手指位置计算字符偏移
            int[] tvLoc = new int[2];
            textView.getLocationOnScreen(tvLoc);
            float tvX = rawX - tvLoc[0];
            float tvY = rawY - tvLoc[1];
            int newOff = charAtAbs(tvX, tvY);
            newOff = Math.max(0, Math.min(newOff, textView.getText().length()));

            // 2. 更新选区（不交换 start/end，dispatchDraw 自行处理排序）
            if (isStart) {
                selStart = newOff;
            } else {
                selEnd = newOff;
            }
            hasSelection = Math.abs(selStart - selEnd) > 0;

            // 3. 手柄 X 直接跟随手指（不 snap 到字符位置，避免一顿一顿），
            //    并限制在贴图左右边界内；Y 贴字符所在行下方，顶出屏幕时自动上移
            SelectionHandleView target = isStart ? handleStart : handleEnd;
            int[] parentLoc = new int[2];
            TextStickerView.this.getLocationOnScreen(parentLoc);
            placeHandleX(target, rawX - parentLoc[0] - target.getWidth() / 2f);

            Layout layout = textView.getLayout();
            int line = layout.getLineForOffset(newOff);
            placeHandleY(target, line);

            // 4. 更新放大镜：跟随手柄指向的字符（手柄跟随手指、放大镜不跟随手指），
            //    放大镜固定在手柄尖端所指字符的放大位置，避免拖拽时画面乱跳
            showMagnifier();
            invalidate();
            updateBtn();
        }

        @Override public void onDragEnd() {
            isDraggingHandle = false;
            dismissMagnifier();
        }
    }

    /**
     * 更新系统放大镜位置。
     * 拖拽中：放大镜中心 = 手柄尖端坐标（与手柄完全重合，杜绝字符偏差；
     * 手柄尖端 X 跟随手指、Y 吸附字符行，选区逻辑仍按字符 offset 走）。
     * 非拖拽（手指滑动选择/长按）：放大镜 = 选区末尾字符中心。
     */
    private void showMagnifier() {
        if (textMagnifier == null || textView == null || textView.getLayout() == null) return;

        float sx, sy;
        if (isDraggingHandle) {
            SelectionHandleView target = dragTargetIsStart ? handleStart : handleEnd;
            // 手柄尖端 = 手柄 view 顶部中心（倒水滴尖点），转 textView 相对坐标
            sx = target.getX() + target.getWidth() / 2f - textView.getLeft();
            sy = target.getY() - textView.getTop();
        } else {
            int len = textView.getText().length();
            if (len == 0) return;
            int offset = Math.max(selStart, selEnd) - 1;
            if (offset < 0) offset = 0;
            if (offset >= len) offset = len - 1;
            Layout layout = textView.getLayout();
            int line = layout.getLineForOffset(offset);
            // 字符中心（x 取左右缘中点、y 取行垂直中线）
            float xStart = layout.getPrimaryHorizontal(offset);
            float xEnd = layout.getPrimaryHorizontal(Math.min(offset + 1, len));
            sx = (xStart + xEnd) / 2f + textView.getTotalPaddingLeft();
            sy = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
                    + textView.getTotalPaddingTop();
        }

        // 位置几乎未变 → 跳过，避免拖拽/滑动时放大镜无谓刷新造成卡顿
        if (Math.abs(sx - lastMagX) < 0.5f && Math.abs(sy - lastMagY) < 0.5f) return;
        lastMagX = sx; lastMagY = sy;
        textMagnifier.show(sx, sy);
    }

    private void dismissMagnifier() {
        lastMagX = lastMagY = Float.MAX_VALUE;
        if (textMagnifier != null) textMagnifier.dismiss();
    }

    /** charAt 但参数已经是 TextView 相对坐标 */
    private int charAtAbs(float tvX, float tvY) {
        int off = textView.getOffsetForPosition(tvX, tvY);
        if (off < 0) off = 0;
        return Math.min(off, textView.getText().length());
    }

    private void positionHandles() {
        if (!handlesCreated || textView == null || textView.getLayout() == null) return;
        Layout layout = textView.getLayout();
        int s = Math.min(selStart, selEnd);
        int e = Math.max(selStart, selEnd);

        boolean show = selectMode && s < e;
        handleStart.setVisibility(show ? VISIBLE : GONE);
        handleEnd.setVisibility(show ? VISIBLE : GONE);
        if (!show) return;

        // 起点手柄：在第一个字符下方（越界部分绘制在贴图外，顶出屏幕时自动上移）
        int sLine = layout.getLineForOffset(s);
        placeHandleX(handleStart, textView.getLeft() + textView.getTotalPaddingLeft()
                + layout.getPrimaryHorizontal(s) - handleStart.getWidth() / 2f);
        placeHandleY(handleStart, sLine);

        // 终点手柄：在最后一个字符下方（越界部分绘制在贴图外，顶出屏幕时自动上移）
        int lastOff = Math.max(0, e - 1);
        int eLine = layout.getLineForOffset(lastOff);
        placeHandleX(handleEnd, textView.getLeft() + textView.getTotalPaddingLeft()
                + layout.getPrimaryHorizontal(lastOff) - handleEnd.getWidth() / 2f);
        placeHandleY(handleEnd, eLine);

        // 更新放大镜信息
        showMagnifier();
    }

    /**
     * 手柄 X 定位：限制在贴图左右边界内。
     *
     * @param desiredCenterX 期望的手柄中心 X（TextStickerView 局部坐标）
     */
    private void placeHandleX(SelectionHandleView handle, float desiredCenterX) {
        float maxX = getWidth() - handle.getWidth();
        handle.setX(Math.max(0, Math.min(desiredCenterX, maxX)));
    }

    /**
     * 手柄 Y 定位：始终放在字符所在行下方（尖点朝上指向文字），不翻转。
     * 坐标只依赖 TextView（textView.getTop + padding + lineBottom），
     * 不依赖容器高度时序；选择模式时容器底部已预留 {@link #HANDLE_SPACE_DP}
     * 透明留白，手柄必然落在贴图内部。
     */
    private void placeHandleY(SelectionHandleView handle, int line) {
        if (textView.getLayout() == null) return;
        Layout layout = textView.getLayout();
        float lineBottom = textView.getTop() + textView.getTotalPaddingTop()
                + layout.getLineBottom(line);
        handle.setY(lineBottom);
    }

    // ===================== 选择模式贴图底部留白 =====================

    // 进入选择模式前 TextView 的 LayoutParams 高度（用于退出时恢复 match_parent）
    private int savedTvHeight = -1;
    private ShadowLayout shadowHost;

    /**
     * 进入选择模式：贴图底部预留手柄空间。
     * <p>
     * 实现要点（避免 shadow-layout 的阴影位图干扰）：
     * 1. TextView 高度锁定为当前 px —— 容器长高后 match_parent 不会拉长灰色背景，
     *    AutoSizeText 也不会因高度变化跳动字号；
     * 2. 父容器 imageOutterShadow 若是显式尺寸则同步长高（wrap 阶段 TextStickerView
     *    会因 padding 撑高容器），保证窗口/容器容纳手柄；
     * 3. 长高期间把 ShadowLayout 的阴影背景换成透明占位，并关闭 onSizeChanged 重建，
     *    防止阴影位图被拉伸/重建到新增区域产生黑色遮罩。
     * 容器尺寸变化是异步布局的，注册一次性 onLayoutChange，重排完成后重新定位手柄。
     */
    private void expandSpaceForHandles() {
        if (spaceExpanded) return;
        spaceExpanded = true;
        final int space = (int) (HANDLE_SPACE_DP * getResources().getDisplayMetrics().density);

        // 1. 锁定 TextView 高度
        if (textView != null && textView.getHeight() > 0) {
            ViewGroup.LayoutParams tvLp = textView.getLayoutParams();
            if (tvLp != null && tvLp.height == ViewGroup.LayoutParams.MATCH_PARENT) {
                savedTvHeight = textView.getHeight();
                tvLp.height = savedTvHeight;
                textView.setLayoutParams(tvLp);
            }
        }

        // 2. 父容器长高（显式尺寸时）
        View parent = (View) getParent();
        if (parent != null) {
            ViewGroup.LayoutParams lp = parent.getLayoutParams();
            if (lp != null && lp.height > 0) {
                lp.height += space;
                parent.setLayoutParams(lp);
            }
        }

        // 3. 长高期间屏蔽 ShadowLayout 阴影背景（透明占位 + 禁止重建）
        disableShadowDuringSelect();

        // 4. 等容器按新尺寸重新布局后再定位手柄（此刻 getHeight() 仍是旧值）
        addOnLayoutChangeListener(new OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                v.removeOnLayoutChangeListener(this);
                positionHandles();
            }
        });
    }

    /** 退出选择模式：移除贴图底部预留空间，恢复原始尺寸与阴影 */
    private void collapseSpaceForHandles() {
        if (!spaceExpanded) return;
        spaceExpanded = false;
        int space = (int) (HANDLE_SPACE_DP * getResources().getDisplayMetrics().density);

        // 恢复 TextView 高度
        if (textView != null && savedTvHeight > 0) {
            ViewGroup.LayoutParams tvLp = textView.getLayoutParams();
            if (tvLp != null && tvLp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                tvLp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                textView.setLayoutParams(tvLp);
            }
            savedTvHeight = -1;
        }

        // 恢复父容器高度
        View parent = (View) getParent();
        if (parent != null) {
            ViewGroup.LayoutParams lp = parent.getLayoutParams();
            if (lp != null && lp.height > 0) {
                lp.height -= space;
                parent.setLayoutParams(lp);
            }
        }

        restoreShadowAfterSelect();
    }

    /** 屏蔽 ShadowLayout 阴影：背景换透明占位并禁止 onSizeChanged 重建位图 */
    private void disableShadowDuringSelect() {
        View parent = (View) getParent();
        if (parent instanceof ShadowLayout) {
            shadowHost = (ShadowLayout) parent;
            shadowHost.setInvalidateShadowOnSizeChanged(false);
            shadowHost.setBackground(new ColorDrawable(Color.TRANSPARENT));
        }
    }

    /** 恢复 ShadowLayout 阴影：允许重建并强制重建背景位图 */
    private void restoreShadowAfterSelect() {
        if (shadowHost != null) {
            shadowHost.setBackground(null); // 清除透明占位
            shadowHost.setInvalidateShadowOnSizeChanged(true);
            shadowHost.invalidateShadow();  // 强制按当前尺寸重建阴影
            shadowHost = null;
        }
    }

    // ===================== 高亮绘制 =====================
    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);

        if (!hasSelection || textView == null || textView.getLayout() == null) return;
        Layout layout = textView.getLayout();
        int s = Math.min(selStart, selEnd), e = Math.max(selStart, selEnd);
        if (s >= e) return;

        canvas.save();
        canvas.translate(textView.getLeft() + textView.getTotalPaddingLeft(),
                textView.getTop() + textView.getTotalPaddingTop());
        int sl = layout.getLineForOffset(s), el = layout.getLineForOffset(e);
        if (sl == el) {
            float l = layout.getPrimaryHorizontal(s), r = layout.getPrimaryHorizontal(e);
            if (l > r) { float t = l; l = r; r = t; }
            canvas.drawRect(l, layout.getLineTop(sl), r, layout.getLineBottom(sl), selPaint);
        } else {
            canvas.drawRect(layout.getPrimaryHorizontal(s), layout.getLineTop(sl),
                    layout.getLineRight(sl), layout.getLineBottom(sl), selPaint);
            for (int line = sl + 1; line < el; line++)
                canvas.drawRect(layout.getLineLeft(line), layout.getLineTop(line),
                        layout.getLineRight(line), layout.getLineBottom(line), selPaint);
            canvas.drawRect(layout.getLineLeft(el), layout.getLineTop(el),
                    layout.getPrimaryHorizontal(e), layout.getLineBottom(el), selPaint);
        }
        canvas.restore();
    }

    // ===================== 按钮 =====================
    private void ensureBtn() {
        if (btnReady) return;
        btnReady = true;
        actionBtn = new Button(getContext());
        actionBtn.setTextSize(12);
        actionBtn.setPadding(16, 6, 16, 6);
        actionBtn.setMinWidth(0); actionBtn.setMinHeight(0);
        actionBtn.setMinimumWidth(0); actionBtn.setMinimumHeight(0);
        actionBtn.setVisibility(GONE);
        actionBtn.setBackgroundResource(android.R.drawable.screen_background_light_transparent);
        actionBtn.setTextColor(0xFF1976D2);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.setMargins(0, 6, 6, 0);
        actionBtn.setLayoutParams(lp);
        actionBtn.setOnClickListener(v -> onActionBtn());
        addView(actionBtn);
    }

    private void updateBtn() {
        ensureBtn(); if (actionBtn == null) return;
        if (!selectMode) { actionBtn.setVisibility(GONE); return; }
        String txt = hasSelection ? getResources().getString(R.string.log_copy) : "完成";
        if (actionBtn.getVisibility() != VISIBLE) actionBtn.setVisibility(VISIBLE);
        // 文本未变化就不 setText，避免拖拽过程中按钮反复重排导致卡顿
        if (!txt.contentEquals(actionBtn.getText())) actionBtn.setText(txt);
    }

    private void onActionBtn() {
        if (!selectMode) return;
        if (hasSelection) doCopy(); else exitSelectMode();
    }

    private void doCopy() {
        if (!hasSelection || textView == null) return;
        int s = Math.min(selStart, selEnd), e = Math.max(selStart, selEnd);
        if (s >= e) return;
        String text = textView.getText().subSequence(s, e).toString();
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("sticker_text", text));
            Toast.makeText(getContext(), "已复制", Toast.LENGTH_SHORT).show();
        }
        exitSelectMode();
    }

    private void exitSelectMode() {
        selectMode = false;
        clearSelection();
        collapseSpaceForHandles();
    }

    private void clearSelection() {
        selStart = -1; selEnd = -1; hasSelection = false;
        dismissMagnifier(); // 退出选择/清选区时一并隐藏放大镜
        if (actionBtn != null) actionBtn.setVisibility(GONE);
        if (handlesCreated) {
            handleStart.setVisibility(GONE);
            handleEnd.setVisibility(GONE);
        }
        invalidate();
    }

    // ===================== 公共方法 =====================
    public void dismissOverlays() {
        exitSelectMode();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child != textView && child != scaleImage)
                child.setVisibility(GONE);
        }
    }

    // ===================== 贴图拖拽 =====================
    private void handleDrag(MotionEvent e) {
        if (tag == null) return;
        View b = findBody();
        if (b != null) SharePasteHelper.applyDragOut(tag, b, e);
    }
    private void finishDrag(MotionEvent e) {
        if (tag == null) return;
        View b = findBody();
        if (b != null) SharePasteHelper.applyDragOut(tag, b, e);
    }
    private void initDrag(MotionEvent e) {
        View b = findBody();
        if (b == null || tag == null) return;
        SharePasteHelper.initializeDrag(tag, b, lastDownRawX, lastDownRawY);
    }

    private boolean isTouchOnScaleImage(MotionEvent e) {
        if (scaleImage == null || scaleImage.getVisibility() != VISIBLE) return false;
        int[] loc = new int[2];
        scaleImage.getLocationOnScreen(loc);
        float x = e.getRawX(), y = e.getRawY();
        return x >= loc[0] && x <= loc[0] + scaleImage.getWidth()
                && y >= loc[1] && y <= loc[1] + scaleImage.getHeight();
    }
    @Nullable private View findBody() {
        View root = getRootView();
        return root != null ? root.findViewById(R.id.imageOutterShadow) : null;
    }
}
