package com.dropstar.floatpaste.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 倒水滴型文字选取手柄。
 * 视觉：顶部尖点指向字符，流畅曲线外扩，底部圆润。
 * 触摸：自由拖拽（XY 双向），实时更新选区。
 * 放大镜由系统 {@link android.widget.Magnifier} 提供。
 */
public class SelectionHandleView extends View {

    public interface OnDragListener {
        void onDragStart(boolean isStart);
        void onDrag(float rawX, float rawY);
        void onDragEnd();
    }

    private final boolean isStart;
    private final float density;
    private OnDragListener listener;

    private static final float DROP_W_DP = 16f;
    private static final float DROP_H_DP = 22f;
    private static final float TOUCH_EXTRA_DP = 14f;

    private final Paint bodyPaint;
    private final Path dropPath = new Path();
    private boolean pathDirty = true;

    // 拖拽
    private boolean isDragging = false;

    // 尺寸
    private final int handleW, handleH;

    public SelectionHandleView(Context context, boolean isStart) {
        super(context);
        this.isStart = isStart;
        this.density = context.getResources().getDisplayMetrics().density;

        bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bodyPaint.setColor(0xFF1976D2);
        bodyPaint.setStyle(Paint.Style.FILL);

        handleW = (int) (DROP_W_DP * density + TOUCH_EXTRA_DP * 2 * density);
        handleH = (int) (DROP_H_DP * density + TOUCH_EXTRA_DP * density + 6 * density);
    }

    public void setOnDragListener(@Nullable OnDragListener l) { this.listener = l; }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(handleW, handleH);
    }

    // ===================== 倒水滴绘制 =====================

    private void buildDropPath() {
        dropPath.reset();
        pathDirty = false;
        float cx = getWidth() / 2f;
        float r = DROP_W_DP * density / 2f;
        float dropH = DROP_H_DP * density;
        float extra = TOUCH_EXTRA_DP * density;
        float bottomY = getHeight() - extra;
        float circleCY = bottomY - r - 2 * density;
        float topY = bottomY - dropH;

        dropPath.moveTo(cx, topY);
        // 右侧平滑曲线
        dropPath.cubicTo(
                cx + r * 0.1f, topY + r * 0.25f,
                cx + r, circleCY - r * 0.6f,
                cx + r, circleCY - r * 0.15f);
        // 底部半圆
        RectF arc = new RectF(cx - r, circleCY - r, cx + r, circleCY + r);
        dropPath.arcTo(arc, 0, 180);
        // 左侧平滑曲线回尖点
        dropPath.cubicTo(
                cx - r, circleCY - r * 0.15f,
                cx - r * 0.1f, topY + r * 0.25f,
                cx, topY);
        dropPath.close();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (pathDirty) buildDropPath();
        canvas.drawPath(dropPath, bodyPaint);
    }

    // ===================== 触摸 =====================

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                isDragging = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                if (listener != null) listener.onDragStart(isStart);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (isDragging && listener != null) {
                    listener.onDrag(event.getRawX(), event.getRawY());
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (isDragging) {
                    isDragging = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    if (listener != null) listener.onDragEnd();
                }
                return true;
            }
        }
        return false;
    }
}
