package com.dropstar.floatpaste.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.github.chrisbanes.photoview.OnScaleChangedListener;

public class ScaleImage extends ImageView {

    private static final String TAG = "ScaleImage";

    private float lastX = 0;
    private float lastY = 0;

    public OnScaledListener onScaledListener;

    public interface OnScaledListener extends OnScaleChangedListener {
        void onScaled (float x, float y, MotionEvent event);
    }

    public ScaleImage(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    public boolean onTouchEvent(MotionEvent event) {
        if (event == null) {
            return super.onTouchEvent(event);
        }
        // 屏蔽调浮窗的事件拦截，仅由自身消费
        getParent().requestDisallowInterceptTouchEvent(true);
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getRawX();
                lastY = event.getRawY();
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - lastX;
                float dy = event.getRawY() - lastY;
                lastX = event.getRawX();
                lastY = event.getRawY();
                if (onScaledListener != null) {
                    onScaledListener.onScaled(dx, dy, event);
                }
                break;
        }
        return true;
    }
}
