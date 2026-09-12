package com.dropstar.floatpaste.lib;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;

import com.lzf.easyfloat.anim.AppFloatDefaultAnimator;
import com.lzf.easyfloat.enums.SidePattern;

/**
 * 底部操作条（收起 / 关闭 / 取消）的出入场动画。
 *
 * EasyFloat 的系统浮窗默认动画 {@link AppFloatDefaultAnimator} 操纵的是 params.x（横向）：
 * 按浮窗离左右哪边更近，从左边或右边滑入。操作条虽用 Gravity.BOTTOM | CENTER_HORIZONTAL 锚定在
 * 底部居中，但默认动画仍会横向滑入，表现为「从屏幕右下（或左下）进入」。
 *
 * 这里改为操纵 params.y（纵向）：进场时从屏幕正下方滑入，出场时向屏幕正下方滑出，
 * 与「底部弹出操作条」的语义一致。
 *
 * 注意：EasyFloat 的窗口 gravity 永远为 START|TOP，params.x/params.y 是「以屏幕左上角为原点」的
 * 绝对像素坐标，越大的 y 越靠屏幕底部。setGravity 已把本操作条的 params.y 算成静止落点，
 * 动画只需在 [屏幕底边, 静止落点] 之间平移即可，静止落点保持不变。
 */
public class BottomSheetAnimator extends AppFloatDefaultAnimator {

    private static final long ENTER_DURATION = 280;
    private static final long EXIT_DURATION = 220;

    @Override
    public Animator enterAnim(View view, WindowManager.LayoutParams params,
                              WindowManager windowManager, SidePattern sidePattern) {
        Rect parentRect = new Rect();
        windowManager.getDefaultDisplay().getRectSize(parentRect);
        // 起始 y：窗口顶部对齐到屏幕底边之外，整条完整藏在屏幕下方，再向上平移入场
        int fromY = parentRect.bottom;
        ValueAnimator animator = ValueAnimator.ofInt(fromY, params.y).setDuration(ENTER_DURATION);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            params.y = (int) animation.getAnimatedValue();
            windowManager.updateViewLayout(view, params);
        });
        return animator;
    }

    @Override
    public Animator exitAnim(View view, WindowManager.LayoutParams params,
                             WindowManager windowManager, SidePattern sidePattern) {
        Rect parentRect = new Rect();
        windowManager.getDefaultDisplay().getRectSize(parentRect);
        // 滑出到屏幕正下方
        int toY = parentRect.bottom;
        ValueAnimator animator = ValueAnimator.ofInt(params.y, toY).setDuration(EXIT_DURATION);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            params.y = (int) animation.getAnimatedValue();
            windowManager.updateViewLayout(view, params);
        });
        return animator;
    }
}
