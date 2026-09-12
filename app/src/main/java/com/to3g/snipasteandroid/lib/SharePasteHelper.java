package com.to3g.snipasteandroid.lib;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Matrix;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.dd.ShadowLayout;

import com.lzf.easyfloat.EasyFloat;
import com.lzf.easyfloat.enums.ShowPattern;
import com.lzf.easyfloat.interfaces.OnFloatCallbacks;
import com.lzf.easyfloat.permission.PermissionUtils;
import com.to3g.snipasteandroid.QDApplication;
import com.to3g.snipasteandroid.R;
import com.to3g.snipasteandroid.view.ScaleImage;
import com.to3g.snipasteandroid.view.TextStickerView;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 共享接收与贴图助手
 * 提供从外部 App 接收分享文字/图片并直接创建悬浮窗的静态方法
 * HomeFragment 也可复用此工具类
 */
public class SharePasteHelper {

    private static final String TAG = "SharePasteHelper";

    /** 主线程 Handler：用于代替 view.post()，避免 View detach 后 Runnable 丢失 */
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    /** 贴图 tag -> 贴图本体 View（编辑条调节不透明度时直接作用于该 View） */
    private static final Map<String, View> sliderStickerBodies = new HashMap<>();
    /** 贴图 tag -> 创建该贴图时所在的 Activity（强引用保存；文字贴图所在的瞬态 Activity 一旦被 WeakReference 回收，双击时就拿不到 Activity 而弹不出编辑条。该 Activity 对象能成功创建贴图浮窗，就能成功创建编辑条浮窗） */
    private static final Map<String, Activity> sliderActivities = new HashMap<>();
    /** 贴图 tag -> 双击检测状态（在浮窗 touchEvent 回调里做「完成态双击」判定，双击弹出/收起编辑条） */
    private static final Map<String, TapState> tapStates = new HashMap<>();

    // ===================== 底部固定「贴图编辑条」 =====================
    // 编辑条是【全局唯一】浮窗，固定停靠在屏幕底部居中，同一时刻只服务一个贴图（editBarImageTag）。
    // 因为位置固定、不跟随贴图，所以不存在坐标换算，也就没有「呼出定位不准」的问题。

    /** 编辑条浮窗 tag */
    private static final String EDIT_BAR_TAG = "sticker_edit_bar";
    /** 编辑条距屏幕底部的间距(dp) */
    private static final int EDIT_BAR_MARGIN_DP = 24;
    /** 编辑条浮窗是否已创建（收起只是 hide，不销毁，下次直接 show 回来） */
    private static boolean editBarCreated = false;
    /** 编辑条当前是否处于显示状态（自行维护，因为 hide 之后 getAppFloatView 仍非 null） */
    private static boolean editBarShown = false;
    /** 编辑条浮窗根 View（hide 期间依然可用，用于追加操作按钮） */
    private static View editBarView = null;
    /** 编辑条当前作用的贴图 tag；收起或未显示时为 null */
    private static String editBarImageTag = null;
    /** 已注册的「编辑贴图」扩展操作（由 addEditBarAction 追加，编辑条每次创建时按此列表生成按钮） */
    private static final List<EditBarAction> editBarActions = new ArrayList<>();
    /**
     * 编辑态高亮：贴图 tag -> 该贴图根 View 原本的 foreground。
     * 挂高亮前先存下原值、退出编辑时原样恢复，避免覆盖掉别处设置的 foreground。
     */
    private static final Map<String, Drawable> stickerOriginalForegrounds = new HashMap<>();
    /**
     * 贴图当前的不透明度（tag -> 0~1）。
     *
     * <p>ShadowLayout 的阴影是它自己在 {@code onSizeChanged} 里生成的<b>背景位图</b>，
     * 尺寸一变（缩放贴图）就换成新的 Drawable、alpha 回到 255 —— 所以把目标值记下来，
     * 尺寸变化后按它把阴影的 alpha 重新压回去，否则低不透明度下阴影会突然变回实色。
     */
    private static final Map<String, Float> stickerAlphas = new HashMap<>();

    /** 编辑条上的一项扩展编辑操作：图标资源 + 文案资源 + 点击行为 */
    private static class EditBarAction {
        final int iconRes;
        final int textRes;
        final Runnable action;

        EditBarAction(int iconRes, int textRes, @NonNull Runnable action) {
            this.iconRes = iconRes;
            this.textRes = textRes;
            this.action = action;
        }
    }

    /** 由 helper 创建的图片浮窗 tag 列表 */
    private static final List<String> helperImageTags = new ArrayList<>();

    // 双击判定阈值：两次「按下→抬起且几乎未移动」的完整点击，第二次抬手距第一次抬手小于该值即视为双击
    private static final long DOUBLE_TAP_TIME = 300;
    // 判定为「点击」而非「拖拽」的位移容差(px)：超过则视为拖拽，取消待定的第一次点击
    private static final int TAP_SLOP = 24;
    // 收起把手（贴边条仅 12dp 厚）比普通浮窗更难精准点击，使用更大的容差避免手指自然晃动被误判为拖拽
    private static final int HANDLE_TAP_SLOP = 64;

    /** 浮窗 touchEvent 里的双击检测状态 */
    private static class TapState {
        boolean firstTapPending;  // 已记录一次完成的点击，等待可能的第二次
        long firstTapUpTime;      // 第一次点击抬手时刻
        float downX, downY;       // 本次手势落点
        boolean moved;            // 本次手势是否发生明显移动（拖拽）
    }

    // ===================== 拖出屏幕边缘（仅保留视觉滑出，不再触发任何菜单） =====================

    /** 收起把手 浮窗 tag 后缀 */
    private static final String HANDLE_SUFFIX = "_handle";
    /** 拖拽手势状态：用于检测「用户把贴图推到屏幕边缘、继续推（系统会把浮窗夹在屏内，无法真正拖出）」的意图。
     *  命中后用「裁切」实现视觉拖出——把贴图内容沿吸附方向平移，被窗口裁掉，呈现滑出屏幕的效果。 */
    private static final Map<String, DragState> dragStates = new HashMap<>();

    /** 单次拖拽手势的状态：记录手势起点(手指+贴图窗口位置)，用于推算「贴图被系统夹在屏边后，手指继续推」产生的视觉拖出量 */
    private static class DragState {
        boolean moved;             // 本手势是否发生过移动（区分轻点）
        boolean inited;            // 起点基线是否已记录(ACTION_DOWN)
        int startRawX, startRawY;  // 手势起点手指 raw 坐标
        int startWinX, startWinY;  // 手势起点贴图本体的真实屏幕左上角坐标
        int winW, winH;            // 贴图窗口尺寸
        int originX, originY;      // 窗口帧原点：屏幕坐标 = params + origin + translation
        float overflowX, overflowY; // 当前各轴向「已拖出」量(px)
        int edgeX, edgeY;          // 横向/纵向吸附方向：0=未吸附；-1=左/上；+1=右/下
    }

    // ===================== 两指捏合缩放（lp 路径：与手柄/拖动同机制） =====================
    //
    // 设计要点（这台 vivo 上反复实测的结论）：
    //  · 不用任何 View 渲染变换（scale/translation 变换会把内容和窗口几何解耦，窗口一 resize
    //    就出现「旧缓冲配新几何」的过渡帧 —— 闪、蹦）；
    //  · 每帧只做两类确定性更新：lp（尺寸，内容恒填满窗口，与手柄缩放同路径）+ params（位置，
    //    纯平移不动 surface 大小，与拖动同路径）—— 两者都被本机长期实测稳定；
    //  · 手势结束无需「提交/折算」：几何全程都是真实值，松手不存在闪的机会；
    //  · 阴影手势期间冻结（每帧按新尺寸重建阴影位图 + 软件模糊会拖垮帧率），松手重建一次。

    /** 两指捏合时两指的最小间距(dp)：低于此值不认为是捏合，避免手指并拢误触 */
    private static final float MIN_PINCH_SPAN_DP = 24f;

    /**
     * 捏合平滑系数：中点取 1（不滤 —— 位置滤一次就滞后一帧，滞后即「打滑」；中点噪声是亚像素级），
     * 间距取 0.5（间距噪声会被比值放大成尺寸跳动，压掉，手指停住时尺寸不再自己抖）。
     */
    private static final float PINCH_SMOOTH_MID = 1f;
    private static final float PINCH_SMOOTH_DIST = 0.5f;

    /** 每张贴图一份的捏合手势状态 */
    private static final Map<String, PinchState> pinchStates = new HashMap<>();

    private static class PinchState {
        boolean active;              // 捏合是否进行中
        float startDist;             // 本段手势的起始两指间距(px)（换指后重置）
        int startW, startH;          // 本段手势起始的贴图 lp 尺寸(px)
        float anchorX, anchorY;      // 两指中点在贴图内的相对位置(可为 0~1 之外，钉住不夹取)，缩放时钉住
        float smoothDist;            // 低通后的两指间距(px)
        float smoothMidX, smoothMidY;// 低通后的两指中点屏幕坐标
        float baseScale = 1f;        // 换指前已累计的比例（换指后重置为 1，见 rebaselinePinch）
        boolean holding;             // 只剩一根手指：画面冻结，等它抬起
        float lastScale = 1f;        // 当前生效比例（相对 startW/H）
        int lastW, lastH;            // 当前生效 lp 尺寸
        float lastLeft, lastTop;     // 当前生效的内容左上角屏幕坐标
        int originX, originY;        // 窗口帧原点：屏幕坐标 = params 坐标 + origin
        ShadowLayout shadowHost;     // 手势期间被冻结阴影的根容器
    }

    /**
     * 两指捏合缩放（等比）。双指只当「驱动器」：间距驱动贴图 lp（与手柄缩放同路径，内容恒填满
     * 窗口），整体平移驱动窗口 params（与拖动同路径，纯位置更新）—— 两条路径都被实测稳定，
     * 且全程不产生任何「变换 vs 窗口几何」的错位，松手也无需提交。
     *
     * @return true 表示本次事件已被捏合手势消费，调用方应跳过拖动 / 双击等其它手势处理。
     *         对 {@code ACTION_POINTER_DOWN} 是幂等的：ViewGroup 的
     *         {@code onInterceptTouchEvent} 与 {@code onTouchEvent} 可能对同一事件各调一次。
     */
    public static boolean handlePinchGesture(@Nullable String tag, @Nullable View body,
                                             @NonNull MotionEvent event) {
        if (tag == null || body == null) return false;
        // 「手柄控制」模式下完全不识别捏合，事件继续走拖动 / 双击逻辑
        if (!isPinchZoomEnabled()) return false;
        PinchState s = pinchStates.get(tag);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (event.getPointerCount() < 2) return false;
                if (s != null && s.active) {
                    // 已在本轮捏合中（幂等）。若此前因「只剩一根手指」而冻结，此刻第二指重新落下
                    // —— 按当前画面重建基准继续捏合；否则视为误触的第三指 / 手掌边缘，忽略。
                    if (s.holding) rebaselinePinch(body, s, event, 0, 1);
                    return true;
                }
                float[] m = pinchMetrics(event, body);
                if (m[0] < MIN_PINCH_SPAN_DP * density()) return false; // 两指几乎并拢，不当作捏合
                int w = body.getWidth(), h = body.getHeight();
                if (w <= 0 || h <= 0) return false;
                com.lzf.easyfloat.widget.appfloat.AppFloatManager afm =
                        com.lzf.easyfloat.widget.appfloat.FloatManager.INSTANCE.getAppFloatManager(tag);
                if (afm == null || afm.getFrameLayout() == null) return false;

                int[] loc = new int[2];
                body.getLocationOnScreen(loc);
                float tx = body.getTranslationX(), ty = body.getTranslationY();

                if (s == null) {
                    s = new PinchState();
                    pinchStates.put(tag, s);
                }
                // 锚点 = 两指中点在贴图内的相对落点。不夹取 0~1：中点落在贴图外（手指跨贴图边缘）
                // 时照样锁定 —— 内容点严格跟随中点，放大哪里哪里就不动
                s.anchorX = (m[1] - loc[0]) / w;
                s.anchorY = (m[2] - loc[1]) / h;
                s.startDist = m[0];
                s.startW = w;
                s.startH = h;
                s.smoothDist = m[0];
                s.smoothMidX = m[1];
                s.smoothMidY = m[2];
                s.baseScale = 1f;
                s.holding = false;
                s.lastScale = 1f;
                s.lastW = w;
                s.lastH = h;
                s.lastLeft = loc[0];
                s.lastTop = loc[1];
                // 窗口帧原点：params 是「窗口相对坐标」、getLocationOnScreen 是「屏幕绝对坐标」，
                // 两者差一个 status bar 等 inset（实测本机为 (0,140)）
                s.originX = Math.round(loc[0] - tx) - afm.params.x;
                s.originY = Math.round(loc[1] - ty) - afm.params.y;
                s.active = true;
                freezePinchShadow(body, s);
                // 双指不可能是双击；同时清掉拖动基线 —— 松手后若还剩一根手指继续移动，
                // applyDragOut 会因 inited=false 按当时的触点重建基线，不会跳位。
                tapStates.remove(tag);
                dragStates.remove(tag);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (s == null || !s.active) return false;
                if (s.holding) return true; // 只剩一根手指：画面冻结，等最后一根抬起
                if (event.getPointerCount() < 2 || s.startDist <= 0f) return true;
                float[] m = pinchMetrics(event, body);
                s.smoothDist += (m[0] - s.smoothDist) * PINCH_SMOOTH_DIST;
                s.smoothMidX += (m[1] - s.smoothMidX) * PINCH_SMOOTH_MID;
                s.smoothMidY += (m[2] - s.smoothMidY) * PINCH_SMOOTH_MID;
                float ratio = clampPinchRatio(s, s.baseScale * s.smoothDist / s.startDist);
                applyPinchFrame(tag, body, s, ratio, s.smoothMidX, s.smoothMidY);
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                if (s == null || !s.active) return false;
                if (s.holding) return true; // 已在冻结态（重复分发 / 多余的抬起），忽略
                // 剩余 ≥2 指 → 绝不结束，按剩余两指重建基准继续捏合（误触的第三指/手掌边缘不中断）；
                // 剩余 1 指 → 冻结画面，等最后一根手指的 UP/CANCEL。
                if (event.getPointerCount() - 1 >= 2) {
                    int ai = event.getActionIndex();
                    int i0 = (ai == 0) ? 1 : 0;
                    int i1 = (ai <= 1) ? 2 : 1;
                    rebaselinePinch(body, s, event, i0, i1);
                    return true;
                }
                s.holding = true;
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean was = s != null && s.active;
                if (was) endPinch(tag, body, s);
                return was;
            }
            default:
                return s != null && s.active;
        }
    }

    /**
     * 比例钳制：下限 = 不小于 min_size（起点已小于 min_size 时 = 不再缩小）；
     * 上限 = 单边 8192px 的隐形天花板（GPU 纹理上限的保守值，约 6 倍屏，日常用碰不到）——
     * 完全无上限会在极限放大时撞纹理上限（黑屏/崩溃），留它只是防崩，不是体验限制。
     * 放大超过屏幕的部分由「窗口恒 ≤ 屏幕 + translation 补偿 + 表面裁切」呈现（见 applyPinchFrame）。
     */
    private static float clampPinchRatio(@NonNull PinchState s, float ratio) {
        int minSize = stickerMinSizePx();
        float minRatio = Math.max(Math.min(minSize / (float) s.startW, 1f),
                                  Math.min(minSize / (float) s.startH, 1f));
        if (ratio < minRatio) ratio = minRatio;
        float maxRatio = Math.max(minRatio,
                Math.min(8192f / s.startW, 8192f / s.startH));
        if (ratio > maxRatio) ratio = maxRatio;
        return ratio;
    }

    /**
     * 把 (比例, 两指中点) 写进真实几何 —— 每帧只做两类确定性更新：
     * <ul>
     *   <li>lp = 起始尺寸 × 比例（内容真实尺寸，可超过屏幕）；</li>
     *   <li>窗口（恒 ≤ 屏幕）+ translation 补偿 = 内容左上角期望屏幕坐标（与拖动同约定）。</li>
     * </ul>
     * 锚点钉在中点：内容左上角期望坐标 = 中点 − 锚点相对位置 × 新尺寸，与比例解耦、无滞后。
     *
     * <p><b>窗口恒不超过屏幕（关键）</b>：这台 ROM 对大于屏幕的窗口会强制重新布局/对齐，
     * 覆盖我们写入的 params/translation —— 缩小时「贴回左上角」的根源。故内容超过屏幕时
     * 不放大窗口，而是把窗口显式钉在「内容与屏幕的较小者」上，超出部分交给 translation
     * 平移、被窗口表面裁掉 —— 与拖动「滑出屏幕」完全同一套视觉机制（裁切）。放大与缩小
     * 因此走完全对称的路径，任何方向、任何尺寸都不会触发 ROM 的强制对齐。
     *
     * <p>定点恒等式：{@code params + origin + translation ≡ want}（期望坐标）。窗口被夹回屏内
     * 的差值全部由 translation（渲染层偏移）精确补偿，被补偿的内容像素本就在屏幕外，视觉无损失；
     * 锚点两侧的可见内容严格钉在手指下。
     */
    private static void applyPinchFrame(@NonNull String tag, @NonNull View body,
                                        @NonNull PinchState s, float ratio,
                                        float midX, float midY) {
        s.lastScale = ratio;
        int newW = Math.round(s.startW * ratio);
        int newH = Math.round(s.startH * ratio);
        // 锚点钉在中点：内容左上角期望屏幕坐标（不夹取 —— 夹了锚点就会偏离手指）
        float wantLeft = midX - s.anchorX * newW;
        float wantTop = midY - s.anchorY * newH;
        Point screen = screenSize();

        com.lzf.easyfloat.widget.appfloat.AppFloatManager afm =
                com.lzf.easyfloat.widget.appfloat.FloatManager.INSTANCE.getAppFloatManager(tag);
        if (afm == null || afm.getFrameLayout() == null) return;

        // 先改内容 lp（本帧 traversal 统一布局），再一次性写窗口 —— 尺寸与内容同帧一致
        ViewGroup.LayoutParams lp = body.getLayoutParams();
        if (lp != null && (lp.width != newW || lp.height != newH)) {
            lp.width = newW;
            lp.height = newH;
            body.setLayoutParams(lp);
        }

        // 窗口恒 ≤ 屏幕：超过屏幕的内容不放大窗口（否则触发 ROM 强制对齐），而是显式钉在
        // 「内容与屏幕的较小者」上，超出部分由 translation 平移、被窗口表面裁掉（视觉裁切）。
        int winW = Math.min(newW, screen.x);
        int winH = Math.min(newH, screen.y);
        if (winW != newW || winH != newH) {
            if (afm.params.width != winW || afm.params.height != winH) {
                afm.params.width = winW;
                afm.params.height = winH;
            }
        } else if (afm.params.width != WindowManager.LayoutParams.WRAP_CONTENT
                || afm.params.height != WindowManager.LayoutParams.WRAP_CONTENT) {
            // 回到屏内：窗口交回 wrap_content。此刻 body lp 已是本帧新尺寸，量出结果与
            // 显式值一致（无跳变），之后手柄改 lp 时窗口照常自动跟随。
            afm.params.width = WindowManager.LayoutParams.WRAP_CONTENT;
            afm.params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        }

        // 窗口夹在屏内（winW ≤ screen.x，区间恒合法）；差值由 translation 补偿：
        // params + origin + translation ≡ want。被补偿的内容像素本就在屏幕外，被表面裁掉。
        afm.params.x = Math.max(0, Math.min(screen.x - winW, Math.round(wantLeft) - s.originX));
        afm.params.y = Math.max(0, Math.min(screen.y - winH, Math.round(wantTop) - s.originY));
        afm.windowManager.updateViewLayout(afm.getFrameLayout(), afm.params);
        body.setTranslationX(wantLeft - (afm.params.x + s.originX));
        body.setTranslationY(wantTop - (afm.params.y + s.originY));

        s.lastW = newW;
        s.lastH = newH;
        s.lastLeft = wantLeft;
        s.lastTop = wantTop;
    }

    /**
     * 手指数量变化后按「当前画面」重建基准：lp 尺寸就是当前尺寸、比例从 1 重新计、
     * 锚点 = 新中点的相对落点 —— 画面（lp/params/translation）一个字都不用改，交换瞬间逐像素等价。
     * 剩余两指几乎并拢时无法继续缩放（比值被噪声放大成剧烈跳变），转为冻结态等手指全部抬起。
     */
    private static void rebaselinePinch(@NonNull View body, @NonNull PinchState s,
                                        @NonNull MotionEvent event, int i0, int i1) {
        float[] m = pinchMetrics(event, body, i0, i1);
        if (m[0] < MIN_PINCH_SPAN_DP * density()) {
            s.holding = true;
            return;
        }
        int[] loc = new int[2];
        body.getLocationOnScreen(loc);
        s.anchorX = (m[1] - loc[0]) / s.lastW;
        s.anchorY = (m[2] - loc[1]) / s.lastH;
        s.startDist = m[0];
        s.smoothDist = m[0];
        s.smoothMidX = m[1];
        s.smoothMidY = m[2];
        s.startW = s.lastW;
        s.startH = s.lastH;
        s.baseScale = 1f;
        s.lastScale = 1f;
        s.holding = false;
    }

    /** 结束本轮捏合：几何全程都是真实值，无事可做 —— 只需按最终尺寸重建一次阴影、清理状态。 */
    private static void endPinch(@NonNull String tag, @NonNull View body, @NonNull PinchState s) {
        // 超过屏幕的贴图【不】主动重建阴影：按最终尺寸建巨型 ALPHA_8 位图 + 软件模糊会 ANR。
        // 保持透明占位、重新打开「尺寸变化自动重建」—— 之后缩小到屏内时阴影自动回来。
        Point screen = screenSize();
        if (s.lastW > screen.x || s.lastH > screen.y) {
            ShadowLayout host = s.shadowHost;
            s.shadowHost = null;
            if (host != null) {
                host.setBackground(null);
                host.setInvalidateShadowOnSizeChanged(true);
            }
        } else {
            restorePinchShadow(tag, body, s);
        }
        pinchStates.remove(tag);
        dragStates.remove(tag); // 还剩一根手指时按新触点重建拖动基线
        tapStates.remove(tag);
    }

    /**
     * 冻结阴影：ShadowLayout 一旦尺寸变化就会新建一张全尺寸 ALPHA_8 位图并用 {@code setShadowLayer}
     * 做一次软件模糊 —— 捏合每帧跑一遍会拖垮帧率。手势期间换成透明占位，结束时重建一次。
     */
    private static void freezePinchShadow(@NonNull View body, @NonNull PinchState s) {
        s.shadowHost = (body instanceof ShadowLayout) ? (ShadowLayout) body : null;
        if (s.shadowHost == null) return;
        s.shadowHost.setInvalidateShadowOnSizeChanged(false);
        s.shadowHost.setBackground(new ColorDrawable(Color.TRANSPARENT));
    }

    /**
     * 捏合结束：按最终尺寸重建阴影（只这一次，与手柄路径松手行为一致）；新生成的阴影位图
     * alpha 是 255，由布局监听（{@link #applyShadowAlpha}）按当前不透明度压回去。
     */
    private static void restorePinchShadow(@NonNull String tag, @NonNull View body, @NonNull PinchState s) {
        ShadowLayout host = s.shadowHost;
        s.shadowHost = null;
        if (host == null) return;
        host.setBackground(null);
        host.setInvalidateShadowOnSizeChanged(true);
        host.invalidateShadow();
        applyShadowAlpha(tag, body);
    }

    /** 贴图最小边长(px)：与 ScaleImage 手柄缩放用的同一个 dimen，保证两条缩放路径下限一致 */
    private static int stickerMinSizePx() {
        try {
            return QDApplication.getContext().getResources()
                    .getDimensionPixelSize(R.dimen.sticker_min_size);
        } catch (Exception e) {
            return (int) (50 * density());
        }
    }

    /**
     * 让右下角缩放手柄（{@code ScaleImage}）与两指捏合共处：第二指落下时，把整轮手势让给捏合。
     *
     * <p>不处理会出问题：手柄在 ACTION_DOWN 就调用了
     * {@code getParent().requestDisallowInterceptTouchEvent(true)} 并消费事件 ——
     * 第一指落在手柄上时，浮窗层的 touchEvent 回调收不到后续事件，第二指落下也进不了捏合，
     * 而手柄自身仍按第一指的位移改宽高，两指一捏就变成「尺寸被拽着乱变」。
     * 故在手柄上挂监听：{@code ACTION_POINTER_DOWN} 时先交给 {@link #handlePinchGesture}，
     * 成功则本轮后续 MOVE / UP 一并转发；返回 false 时手柄行为与原先完全一致（单指拖拽缩放）。
     */
    public static void bindScaleHandlePinch(@NonNull String tag, @NonNull View body) {
        View handle = body.findViewById(R.id.scaleImage);
        if (handle == null) return;
        boolean[] pinching = {false};
        handle.setOnTouchListener((v, ev) -> {
            if (!isPinchZoomEnabled()) return false;
            int masked = ev.getActionMasked();
            if (masked == MotionEvent.ACTION_POINTER_DOWN && ev.getPointerCount() >= 2
                    && handlePinchGesture(tag, body, ev)) {
                pinching[0] = true;
                return true;                        // 本轮交给捏合，手柄不再参与
            }
            if (pinching[0]) {
                handlePinchGesture(tag, body, ev);  // MOVE / UP / CANCEL 继续转发
                if (masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL) {
                    pinching[0] = false;
                }
                return true;
            }
            return false;                           // 单指：交回 ScaleImage.onTouchEvent 做自由缩放
        });
    }

    // ===================== 缩放控制模式（设置项） =====================

    /** 当前设置下是否启用右下角手柄缩放 */
    private static boolean isHandleZoomEnabled() {
        int a = Settings.getZoomAction(QDApplication.getContext());
        return a == Settings.ZOOM_ACTION_HANDLE || a == Settings.ZOOM_ACTION_BOTH;
    }

    /** 当前设置下是否启用两指捏合缩放 */
    private static boolean isPinchZoomEnabled() {
        int a = Settings.getZoomAction(QDApplication.getContext());
        return a == Settings.ZOOM_ACTION_PINCH || a == Settings.ZOOM_ACTION_BOTH;
    }

    /**
     * 按当前设置切换某张贴图右下角缩放手柄的显隐。
     * 贴图创建时调用一次；设置里改了缩放控制模式后由 {@link #refreshZoomAction()} 对所有贴图重刷。
     * 「双指控制」下手柄整个 GONE —— 它不参与布局（贴图本体尺寸由 LayoutParams 显式决定），
     * 且 {@code TextStickerView.isTouchOnScaleImage} 会因 visibility != VISIBLE 而放行触摸。
     */
    public static void applyZoomActionToSticker(@Nullable View floatRoot) {
        if (floatRoot == null) return;
        View handle = floatRoot.findViewById(R.id.scaleImage);
        if (handle != null) {
            handle.setVisibility(isHandleZoomEnabled() ? View.VISIBLE : View.GONE);
        }
    }

    /** 缩放控制模式改变后，立即刷新所有已存在贴图的手柄显隐（双指由 handlePinchGesture 实时判定） */
    public static void refreshZoomAction() {
        for (String tag : new ArrayList<>(sliderStickerBodies.keySet())) {
            applyZoomActionToSticker(EasyFloat.getAppFloatView(tag));
        }
    }

    /**
     * 取手势中前两根手指的 {@code [间距, 中点屏幕X, 中点屏幕Y]}（屏幕坐标系）。
     *
     * <p>不能用 {@code getX(1) - getX(0)} 直接跟 {@code getRawX()} 相加：捏合期间事件坐标会被
     * View 矩阵反向映射（虽然本方案不缩放 View，但保留逆映射写法可免疫未来改动），
     * 而 {@code getRawX()} 始终是屏幕坐标。把本地坐标经 {@code body} 矩阵逆映射回父容器空间：
     * 该空间与屏幕只差一个平移，两指差值就是屏幕差值，与派发给哪个 View 无关。
     */
    private static float[] pinchMetrics(@NonNull MotionEvent e, @NonNull View body) {
        return pinchMetrics(e, body, 0, 1);
    }

    /**
     * 同上，但可指定参与计算的两根手指索引（换指后参考手指未必仍是 0/1）。
     * {@code getRawX(int)} 需要 API 29 而 minSdk 24，故用「父容器空间的相对偏移」换算：
     * 同一事件内任意两指的父空间差值等于屏幕差值，以索引 0 的 raw 为基点即可。
     */
    private static float[] pinchMetrics(@NonNull MotionEvent e, @NonNull View body, int i0, int i1) {
        float[] p0 = {e.getX(i0), e.getY(i0)};
        float[] p1 = {e.getX(i1), e.getY(i1)};
        float[] pz = {e.getX(0), e.getY(0)};
        Matrix inv = new Matrix();
        if (body.getMatrix().invert(inv)) {
            inv.mapPoints(p0);
            inv.mapPoints(p1);
            inv.mapPoints(pz);
        }
        float dx = p1[0] - p0[0];
        float dy = p1[1] - p0[1];
        float mx = (p0[0] + p1[0]) / 2f - pz[0];
        float my = (p0[1] + p1[1]) / 2f - pz[1];
        return new float[]{
                (float) Math.hypot(dx, dy),
                e.getRawX() + mx,
                e.getRawY() + my
        };
    }

    /** 已收起(最小化)的贴图 tag，便于销毁时一并清理把手 */
    private static final Set<String> collapsedTags = new HashSet<>();
    /** 贴图 tag -> 创建该贴图收起把手时使用的 Activity。磁贴/分享入口创建的贴图，其原 Activity 可能已 finish；
     *  拖动把手后需要重建(贴边条模式)时，若 currentActivity 为空则回退到此引用，避免把手消失。 */
    private static final Map<String, Activity> handleActivities = new HashMap<>();
    /** 收起把手自身的单击检测状态（与贴图本体的 tapStates 分开，把手 tag 不同于贴图 tag） */
    private static final Map<String, TapState> handleTapStates = new HashMap<>();
    /** 文字贴图 tag → 原始全文，用于复制到剪贴板 */
    private static final Map<String, String> textStickerContents = new HashMap<>();

    // 边枚举（用于判断贴图被拖向哪条边、把手应停靠在哪条边）
    private static final int EDGE_LEFT = 0;
    private static final int EDGE_RIGHT = 1;
    private static final int EDGE_TOP = 2;
    private static final int EDGE_BOTTOM = 3;
    /** 收起把手(缩略条)尺寸(dp) */
    private static final int HANDLE_W_DP = 48;
    private static final int HANDLE_H_DP = 64;
    /** 贴边条尺寸(dp)：贴向左右边时为竖条(厚 x 长)，贴向上下边时为横条(长 x 厚) */
    private static final int STRIP_THICK_DP = 12;
    private static final int STRIP_LEN_DP = 72;
    /** 贴边条触摸扩展区(dp)：紧贴视觉条的内侧，纯透明、不绘制，仅扩大 EasyFloat 窗口的点击/拖动命中区 */
    private static final int STRIP_TOUCH_EXT_DP = 24;

    private static float density() {
        return Resources.getSystem().getDisplayMetrics().density;
    }

    /** 取创建该贴图时的 Activity（强引用），兜底取当前前台 Activity */
    private static Activity currentActivity(@NonNull String tag) {
        Activity a = sliderActivities.get(tag);
        if (a == null) a = QDApplication.getCurrentActivity();
        return a;
    }

    private static Point screenSize() {
        // 不依赖 Activity：用系统 Resources 取屏幕尺寸，避免贴图创建后原 Activity 已 finish 导致取不到屏尺寸
        DisplayMetrics dm = Resources.getSystem().getDisplayMetrics();
        return new Point(dm.widthPixels, dm.heightPixels);
    }

    /** 取贴图本体在屏幕上的矩形（根 View 即 imageOutterShadow） */
    private static Rect stickerRect(@NonNull View v) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        int w = v.getWidth(), h = v.getHeight();
        if (w <= 0 || h <= 0) return null;
        return new Rect(loc[0], loc[1], loc[0] + w, loc[1] + h);
    }

    /** 判断贴图贴向哪条边：取中心点离屏幕四边最近的那条 */
    private static int edgeOf(@NonNull Rect r, @NonNull Point screen) {
        int cx = r.centerX(), cy = r.centerY();
        int dL = cx, dR = screen.x - cx, dT = cy, dB = screen.y - cy;
        int min = Math.min(Math.min(dL, dR), Math.min(dT, dB));
        if (min == dL) return EDGE_LEFT;
        if (min == dR) return EDGE_RIGHT;
        if (min == dT) return EDGE_TOP;
        return EDGE_BOTTOM;
    }

    /** 从贴图本体背景生成一张缩略图（用于收起后的缩略条把手）。
     *  文字贴图无 Bitmap 背景，则直接渲染 ShadowLayout 视图内容到 Bitmap。 */
    private static Bitmap makeThumbnail(@NonNull String tag) {
        View sv = EasyFloat.getAppFloatView(tag);
        if (sv == null) return null;
        View imageOutter = sv.findViewById(R.id.imageOutter);
        if (imageOutter != null) {
            // 图片贴图：从 imageOutter 背景取 Bitmap
            Drawable d = imageOutter.getBackground();
            Bitmap src = (d instanceof BitmapDrawable) ? ((BitmapDrawable) d).getBitmap() : null;
            if (src != null && !src.isRecycled()) {
                int th = (int) (HANDLE_H_DP * density());
                float ar = (float) src.getWidth() / src.getHeight();
                int tw = Math.max(1, Math.round(th * ar));
                tw = Math.min(tw, (int) (HANDLE_W_DP * 2 * density()));
                return Bitmap.createScaledBitmap(src, tw, th, true);
            }
        }
        // 文字贴图：没有 imageOutter，直接渲染 ShadowLayout 内容到缩略图
        View shadowLayout = sv.findViewById(R.id.imageOutterShadow);
        if (shadowLayout != null && shadowLayout.getWidth() > 0 && shadowLayout.getHeight() > 0) {
            int th = (int) (HANDLE_H_DP * density());
            float ar = (float) shadowLayout.getWidth() / shadowLayout.getHeight();
            int tw = Math.max(1, Math.round(th * ar));
            tw = Math.min(tw, (int) (HANDLE_W_DP * 2 * density()));
            Bitmap bitmap = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.scale((float) tw / shadowLayout.getWidth(), (float) th / shadowLayout.getHeight());
            // 手动 draw() 不会应用「被调用 View 自己」的 alpha，但会应用子 View 的 alpha；
            // 不透明度现在落在内容层（子 View）上，这里临时还原为 1，保证把手缩略图与以前一致
            View content = stickerContentLayer(shadowLayout);
            float contentAlpha = content.getAlpha();
            content.setAlpha(1f);
            // 根 View 的背景（阴影位图）是按「Drawable 自己的 alpha」绘制的，不受上面那条规则约束，
            // 不透明贴图会漏出半透明阴影 —— 一并临时还原，缩略图表现与以前一致
            Drawable shadowBg = shadowLayout.getBackground();
            int shadowBgAlpha = shadowBg != null ? shadowBg.getAlpha() : 255;
            if (shadowBg != null) shadowBg.setAlpha(255);
            shadowLayout.draw(canvas);
            if (shadowBg != null) shadowBg.setAlpha(shadowBgAlpha);
            content.setAlpha(contentAlpha);
            shadowLayout.invalidate();
            return bitmap;
        }
        return null;
    }


    // ---------- 对外接口 ----------

    /**
     * 处理第三方 App 的分享 Intent（ACTION_SEND / ACTION_SEND_MULTIPLE）
     * 自动检查悬浮窗权限，无权限则提示引导
     */
    public static void handleShareIntent(@NonNull Activity activity, @NonNull Intent intent) {
        String action = intent.getAction();
        String type = intent.getType();
        AppLog.d("SharePasteHelper", "收到分享 action=" + action + " type=" + type);

        if (Intent.ACTION_SEND.equals(action) && type != null) {
            if (type.startsWith("text/")) {
                String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
                if (sharedText != null) {
                    floatText(activity, sharedText);
                } else {
                    Toast.makeText(activity, "未获取到分享的文字内容", Toast.LENGTH_SHORT).show();
                }
            } else if (type.startsWith("image/")) {
                Uri imageUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (imageUri != null) {
                    pasteImage(activity, imageUri);
                }
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action) && type != null) {
            if (type.startsWith("image/")) {
                ArrayList<Uri> imageUris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (imageUris != null && !imageUris.isEmpty()) {
                    for (Uri uri : imageUris) {
                        pasteImage(activity, uri);
                    }
                }
            }
        }
    }

    /** 获取 helper 创建的图片浮窗 tag 列表（供 HomeFragment 遍历使用） */
    @NonNull
    public static List<String> getHelperImageTags() {
        return helperImageTags;
    }

    // ---------- 文字浮窗 ----------

    /**
     * 将文字贴在屏幕上（含权限检查）
     */
    public static void floatText(@NonNull Activity activity, @NonNull String content) {
        if (PermissionUtils.checkPermission(activity)) {
            showFloatText(activity, content);
        } else {
            DialogUtil.showPermissionDialog(activity, () -> showFloatText(activity, content));
        }
    }

    /**
     * 直接创建文字浮窗（已有权限时调用）
     */
    public static void showFloatText(@NonNull Activity activity, @NonNull String content) {
        if (content.trim().isEmpty()) {
            Toast.makeText(activity, activity.getText(R.string.blankContent), Toast.LENGTH_SHORT).show();
            return;
        }

        String tag = "share_text_" + content.hashCode();
        if (EasyFloat.getAppFloatView(tag) != null) {
            // 若该贴图已收起（把手可能已丢失），直接恢复而非仅提示"已粘贴"
            if (collapsedTags.contains(tag)) {
                AppLog.d("SharePasteHelper", "文字贴图已存在且已收起，恢复 tag=" + tag);
                restoreSticker(tag);
                return;
            }
            AppLog.d("SharePasteHelper", "文字贴图已存在，提示已贴出 tag=" + tag);
            Toast.makeText(activity, activity.getText(R.string.textFloated), Toast.LENGTH_SHORT).show();
            return;
        }

        // 文字贴图：使用 TextView（textIsSelectable=true）替代 Bitmap 渲染，
        // 支持长按选择文字复制 + 超长文本完整显示（拖出屏幕边缘即可翻阅）。
        showTextFloatWithTag(activity, content, tag);
    }

    // ---------- 图片浮窗 ----------

    /**
     * 从 Uri 加载 Bitmap 并贴到屏幕上（含权限检查）
     */
    public static void pasteImage(@NonNull Activity activity, @NonNull Uri imageUri) {
        if (PermissionUtils.checkPermission(activity)) {
            Bitmap bitmap = loadBitmapFromUri(activity, imageUri);
            if (bitmap != null) {
                showImageFloat(activity, bitmap);
            } else {
                Toast.makeText(activity, "无法加载图片", Toast.LENGTH_SHORT).show();
            }
        } else {
            DialogUtil.showPermissionDialog(activity, () -> {
                Bitmap bitmap = loadBitmapFromUri(activity, imageUri);
                if (bitmap != null) {
                    showImageFloat(activity, bitmap);
                }
            });
        }
    }

    /**
     * 创建图片浮窗
     */
    /**
     * 以图片贴图方式展示一张 Bitmap（截图、分享图片、文字转图等共用此路径）。
     *
     * @param naturalSize true 时按 Bitmap 原始尺寸展示（文字转图用，避免被放大）；
     *                    false 时按屏幕宽度 0.8 等比缩放（普通图片用）
     */
    private static void showImageFloatWithTag(@NonNull Activity activity, @NonNull Bitmap bitmap,
                                              @NonNull String tag, boolean naturalSize) {
        EasyFloat
                .with(activity)
                .setLayout(R.layout.image_paste)
                .setShowPattern(ShowPattern.ALL_TIME)
                .setLocation(100, 200)
                .setTag(tag)
                .registerCallbacks(new OnFloatCallbacks() {
                    @Override
                    public void createdResult(boolean isCreated, String msg, View view) { }

                    @Override
                    public void show(View view) { }

                    @Override
                    public void hide(View view) { }

                    @Override
                    public void dismiss() {
                        // 贴图浮窗被销毁时，务必带走其透明度滑块，避免残留
                        dismissOpacitySlider(tag);
                    }

                    @Override
                    public void touchEvent(View view, MotionEvent event) {
                        // 已自接管（见 .show() 后 setOnTouchListener + appFloatDragEnable(false)）
                    }

                    @Override
                    public void drag(View view, MotionEvent event) {
                    }

                    @Override
                    public void dragEnd(View view) {
                    }
                })
                .show();
        // 自接管贴图拖动 + setOnTouchListener
        EasyFloat.appFloatDragEnable(false, tag);
        View firstView = EasyFloat.getAppFloatView(tag);
        if (firstView != null) {
            View stickerBody = firstView.findViewById(R.id.imageOutterShadow);
            if (stickerBody != null) {
                stickerBody.setOnTouchListener((v, event) -> {
                    handleFloatTouch(tag, v, event);
                    return true;
                });
            }
        }

        helperImageTags.add(tag);
        View view = EasyFloat.getAppFloatView(tag);
        if (view == null) return;

        View imageOutter = view.findViewById(R.id.imageOutter);
        View imageOutterShadow = view.findViewById(R.id.imageOutterShadow);
        ViewGroup.LayoutParams lp = imageOutterShadow.getLayoutParams();

        if (naturalSize) {
            // 文字转图：用 Bitmap 真实尺寸，1:1 显示，不放大、不留空白
            lp.width = bitmap.getWidth();
            lp.height = bitmap.getHeight();
        } else {
            // 普通图片：按屏幕宽度 0.8 等比缩放
            DisplayMetrics dm = new DisplayMetrics();
            activity.getWindowManager().getDefaultDisplay().getMetrics(dm);
            int screenWidth = dm.widthPixels;
            int imgWidth = bitmap.getWidth();
            int imgHeight = bitmap.getHeight();
            float rate = 0.8f;
            lp.width = (int) (rate * screenWidth);
            lp.height = (int) (lp.width * 1.0f / imgWidth * imgHeight);
        }
        imageOutterShadow.setLayoutParams(lp);

        imageOutter.setBackground(new BitmapDrawable(activity.getResources(), bitmap));

        // 缩放最小值（px），防止 onScaled 累加到 0/负数导致布局异常
        final int minSize = activity.getResources().getDimensionPixelSize(R.dimen.sticker_min_size);
        ScaleImage scaleImage = view.findViewById(R.id.scaleImage);
        scaleImage.onScaledListener = new ScaleImage.OnScaledListener() {
            @Override
            public void onScaled(float x, float y, MotionEvent event) {
                int newWidth = (int) (lp.width + x);
                int newHeight = (int) (lp.height + y);
                if (newWidth < minSize) newWidth = minSize;
                if (newHeight < minSize) newHeight = minSize;
                lp.width = newWidth;
                lp.height = newHeight;
                imageOutterShadow.setLayoutParams(lp);
            }

            @Override
            public void onScaleChange(float scaleFactor, float focusX, float focusY) {
            }
        };

        // 手柄与捏合互斥的处理（第一指落在手柄上时，第二指落下即让位给捏合）

        // 关闭贴图不再走 X 按钮：双击贴图弹出底部编辑条，里面的「收起 / 关闭 / 关闭所有」统一处理
        // （见 bindEditBar）；拖出屏幕边缘只保留「滑出屏幕」的视觉效果，不再弹任何菜单。
        attachOpacitySlider(activity, tag, imageOutterShadow);
        applyZoomActionToSticker(view);
        bindScaleHandlePinch(tag, imageOutterShadow);

        // 记录历史贴图（持久保存，内容去重）
        HistoryStore.addImage(QDApplication.getContext(), bitmap);

        AppLog.d("SharePasteHelper", "贴图图片 tag=" + tag
                + " 尺寸=" + bitmap.getWidth() + "x" + bitmap.getHeight()
                + " naturalSize=" + naturalSize);
    }

    // ===================== 文字贴图（TextView 替代 Bitmap） =====================

    /**
     * 使用 {@code text_paste.xml}（含 {@link android.widget.TextView}）创建文字贴图浮窗，
     * 替代原有的 Bitmap 渲染路径，支持：
     * <ul>
     *   <li>拖拽选取文字并复制（TextStickerView 自定义 dispatchDraw 高亮，不依赖系统选取引擎）</li>
     *   <li>超长文本完整显示（拖出屏幕边缘即可翻阅）</li>
     *   <li>双击调节透明度、拖拽移动、ScaleImage 缩放（保留原有交互）</li>
     * </ul>
     */
    private static void showTextFloatWithTag(@NonNull Activity activity, @NonNull String text,
                                             @NonNull String tag) {
        // 保存原文供复制使用
        textStickerContents.put(tag, text);

        EasyFloat
                .with(activity)
                .setLayout(R.layout.text_paste)
                .setShowPattern(ShowPattern.ALL_TIME)
                .setLocation(100, 200)
                .setTag(tag)
                .registerCallbacks(new OnFloatCallbacks() {
                    @Override
                    public void createdResult(boolean isCreated, String msg, View view) { }

                    @Override
                    public void show(View view) { }

                    @Override
                    public void hide(View view) { }

                    @Override
                    public void dismiss() {
                        dismissOpacitySlider(tag);
                    }

                    @Override
                    public void touchEvent(View view, MotionEvent event) {
                        // 已在 TextStickerView 中自接管触摸
                    }

                    @Override
                    public void drag(View view, MotionEvent event) { }

                    @Override
                    public void dragEnd(View view) { }
                })
                .show();

        // 禁用 EasyFloat 默认拖拽——TextStickerView 使用 onInterceptTouchEvent 自接管
        EasyFloat.appFloatDragEnable(false, tag);
        helperImageTags.add(tag);

        View view = EasyFloat.getAppFloatView(tag);
        if (view == null) return;

        // 设置文字内容（文字选择由 TextStickerView 自定义处理）
        View textStickerView = view.findViewById(R.id.textStickerView);
        TextView textContent = view.findViewById(R.id.textContent);
        if (textContent != null) {
            textContent.setText(text);
        }

        // 关联 tag 到 TextStickerView（用于双击调起 opacity slider 等）
        if (textStickerView instanceof TextStickerView) {
            ((TextStickerView) textStickerView).setStickerTag(tag);
        }

        final View imageOutterShadow = view.findViewById(R.id.imageOutterShadow);

        // ---------- 首次布局后固定初始尺寸 ----------
        imageOutterShadow.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        imageOutterShadow.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        int w = imageOutterShadow.getMeasuredWidth();
                        int h = imageOutterShadow.getMeasuredHeight();
                        if (w > 0 && h > 0) {
                            ViewGroup.LayoutParams lp = imageOutterShadow.getLayoutParams();
                            lp.width = w;
                            lp.height = h;
                            imageOutterShadow.setLayoutParams(lp);
                        }
                    }
                });

        // ---------- 自动字号：随容器尺寸填满贴图 ----------
        // 在 API 26+ 上启用 AutoSizeTextType，拖动右下角改变容器大小时，
        // 字号自动调整以尽可能填满贴图（短文本放大、长文本缩小以全部显示）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            textContent.setAutoSizeTextTypeUniformWithConfiguration(
                    8, 72, 1, TypedValue.COMPLEX_UNIT_SP);
        }

        // ---------- 缩放：和图片一样直接改容器尺寸 ----------
        final int minSize = activity.getResources().getDimensionPixelSize(R.dimen.sticker_min_size);
        ScaleImage scaleImage = view.findViewById(R.id.scaleImage);
        scaleImage.onScaledListener = new ScaleImage.OnScaledListener() {
            @Override
            public void onScaled(float x, float y, MotionEvent event) {
                ViewGroup.LayoutParams lp = imageOutterShadow.getLayoutParams();
                int newWidth = Math.max(minSize, lp.width + (int) x);
                int newHeight = Math.max(minSize, lp.height + (int) y);
                lp.width = newWidth;
                lp.height = newHeight;
                imageOutterShadow.setLayoutParams(lp);
                // 容器尺寸改变 → TextView match_parent 跟随 → AutoSizeText 自动调整字号
            }

            @Override
            public void onScaleChange(float scaleFactor, float focusX, float focusY) {
            }
        };

        // 缩放控制模式：按设置决定右下角手柄是否显示
        // 手柄与捏合互斥的处理（第一指落在手柄上时，第二指落下即让位给捏合）

        // 文字选择与复制由 TextStickerView 内部处理（拖拽选字 → 自定义高亮 → 复制按钮）

        // 透明度滑块（双击由 TextStickerView.onInterceptTouchEvent 触发）
        attachOpacitySlider(activity, tag, imageOutterShadow);
        applyZoomActionToSticker(view);
        bindScaleHandlePinch(tag, imageOutterShadow);

        // 记录历史贴图（持久保存，内容去重）
        HistoryStore.addText(QDApplication.getContext(), text);

        AppLog.d("SharePasteHelper", "贴图文字 tag=" + tag + " 长度=" + text.length());
    }

    /**
     * 为文字贴图拖拽手势预初始化 DragState。
     * <p>
     * 由 {@link com.to3g.snipasteandroid.view.TextStickerView#initDragState} 在拦截拖拽前调用，
     * 传入真实的手势起点坐标，使 {@link #applyDragOut} 的 ACTION_MOVE 分支能建立正确的基线，
     * 避免因缺失 ACTION_DOWN ���致的贴图位置跳跃。
     */
    public static void initializeDrag(@NonNull String tag, @NonNull View stickerBody,
                                       float startRawX, float startRawY) {
        DragState s = dragStates.get(tag);
        if (s == null) {
            s = new DragState();
            dragStates.put(tag, s);
        }
        s.inited = true;
        s.moved = true;
        s.startRawX = (int) startRawX;
        s.startRawY = (int) startRawY;

        // 起点视觉位置 = 本体真实屏幕坐标（getLocationOnScreen 直读，对任意 params/translation 组合成立）
        int[] loc = new int[2];
        stickerBody.getLocationOnScreen(loc);
        s.startWinX = loc[0];
        s.startWinY = loc[1];
        // 窗口帧原点：屏幕坐标 = params + origin + translation（origin 对本窗口恒定）
        com.lzf.easyfloat.widget.appfloat.AppFloatManager afm =
                com.lzf.easyfloat.widget.appfloat.FloatManager.INSTANCE.getAppFloatManager(tag);
        if (afm != null) {
            s.originX = loc[0] - afm.params.x - (int) stickerBody.getTranslationX();
            s.originY = loc[1] - afm.params.y - (int) stickerBody.getTranslationY();
        }
        s.winW = stickerBody.getWidth();
        s.winH = stickerBody.getHeight();
        s.overflowX = 0;
        s.overflowY = 0;
        s.edgeX = 0;
        s.edgeY = 0;
    }

    /**
     * 为贴图登记「双击 → 弹出底部固定编辑条」所需的引用（编辑条首项即不透明度调节）。
     * 这里【不】创建任何浮窗：编辑条是全局唯一浮窗（{@link #EDIT_BAR_TAG}），
     * 双击时才创建并把编辑目标指向被双击的贴图；位置固定停靠屏幕底部，
     * 不跟随贴图移动，因此不存在坐标换算，也就不会出现「呼出定位不准」。
     */
    public static void attachOpacitySlider(@NonNull Activity activity, @NonNull String imageTag,
                                           @NonNull View stickerBody) {
        // 记录该贴图对应的本体，双击由浮窗的 touchEvent 回调统一触发（见 handleFloatTouch），
        // 不再依赖 stickerBody 的 OnLongClickListener——因为它嵌在可拖拽的 EasyFloat 内，手指微动即会取消
        // 框架长按，且内部 ScaleImage 吞掉了触摸事件，导致长按几乎不触发。
        sliderStickerBodies.put(imageTag, stickerBody);
        // 强引用保存创建贴图时的 Activity：文字/分享图片走 helper 路径时传入的是瞬态 Activity(贴出后即 finish)，
        // 若用 WeakReference 会被回收导致双击时 activity 为 null、编辑条弹不出。该 Activity 对象既然能创建贴图
        // 浮窗，就能创建编辑条浮窗；贴图销毁时由 dismissOpacitySlider 清理此引用。
        sliderActivities.put(imageTag, activity);
        // ShadowLayout 的阴影位图是「尺寸变化时重建」的，重建后 alpha 回到 255。
        // 登记一个布局监听：尺寸一变就按当前不透明度把阴影的 alpha 重新压回去。
        stickerBody.addOnLayoutChangeListener((v, left, top, right, bottom,
                                               oldLeft, oldTop, oldRight, oldBottom) ->
                applyShadowAlpha(imageTag, stickerBody));
    }

    /**
     * 双击贴图：弹出 / 收起底部固定编辑条。
     * 已有编辑条且正指向这张贴图 → 收起；指向的是别的贴图 → 直接把编辑条切换为这张贴图
     * （免得「双击另一张还得先关掉再打开」）。
     */
    public static void toggleOpacitySlider(@NonNull String imageTag) {
        if (editBarShown && imageTag.equals(editBarImageTag)) {
            hideOpacitySlider(imageTag); // 同一张贴图：再次双击收起编辑条
            return;
        }
        showEditBar(imageTag);
    }

    /** 在屏幕底部居中弹出编辑条；已存在则只切换编辑目标（不重建浮窗、不重播动画） */
    private static void showEditBar(@NonNull String imageTag) {
        // 优先用创建贴图时的 Activity（强引用，永不为 null）；万一缺失则兜底取当前前台 Activity。
        // 不能用 ApplicationContext：EasyFloat.with() 只接受 Activity。
        Activity activity = currentActivity(imageTag);
        View body = sliderStickerBodies.get(imageTag);
        if (activity == null || body == null) return;

        // 切换编辑目标：先把上一张贴图的「编辑中」高亮摘掉（双击的是同一张则原地不动，避免边框闪一下）
        if (!imageTag.equals(editBarImageTag)) setEditHighlight(editBarImageTag, false);

        // 生命周期用「创建一次 + hide/show」，【不】用 dismiss：
        // EasyFloat 的 dismiss 会先播完退场动画再移除窗口，动画期间 getAppFloatView 仍非 null，
        // 「刚收起又立刻双击另一张贴图」会误判成编辑条还活着而不再创建，表现为双击无反应。
        // hide 是瞬时隐藏（EasyFloat.hideAppFloat 走 visible(false, needAnim=false)，无动画窗口），
        // 从根上避开该竞态；showAppFloat 回来时仍带上滑入场动画。
        if (!editBarCreated) {
            EasyFloat.with(activity)
                    .setLayout(R.layout.sticker_edit_bar)
                    .setShowPattern(ShowPattern.ALL_TIME)
                    // 固定停靠屏幕底部居中：位置完全交给 EasyFloat 的 gravity 计算（与底部操作条同一套），
                    // 我们不做任何坐标换算 —— 这正是「固定位置条」不会再出现定位不准的原因。
                    .setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0,
                            (int) (EDIT_BAR_MARGIN_DP * density()))
                    .setAppFloatAnimator(new BottomSheetAnimator()) // 从屏幕正下方滑入/滑出
                    .setTag(EDIT_BAR_TAG)
                    .setDragEnable(false)
                    .registerCallbacks(new OnFloatCallbacks() {
                        @Override
                        public void createdResult(boolean isCreated, String msg, View view) { }

                        @Override
                        public void show(View view) { }

                        @Override
                        public void hide(View view) { }

                        @Override
                        public void dismiss() {
                            // 浮窗被真正销毁时同步状态，避免后续 showAppFloat 打到一个已不存在的窗口
                            editBarCreated = false;
                            editBarShown = false;
                            editBarView = null;
                            editBarImageTag = null;
                        }

                        @Override
                        public void touchEvent(View view, MotionEvent event) { }

                        @Override
                        public void drag(View view, MotionEvent event) { }

                        @Override
                        public void dragEnd(View view) { }
                    })
                    .show();
            editBarView = EasyFloat.getAppFloatView(EDIT_BAR_TAG);
            if (editBarView == null) return;
            editBarCreated = true;
            bindEditBar(editBarView);
        } else if (!editBarShown) {
            try {
                EasyFloat.showAppFloat(EDIT_BAR_TAG);
            } catch (Exception e) {
                Log.e(TAG, "showAppFloat failed: " + e.getMessage());
            }
        }

        editBarShown = true;
        editBarImageTag = imageTag;
        setEditHighlight(imageTag, true); // 高亮「正在编辑的贴图」：多张贴图同在屏幕上时一眼看出在编辑哪一张
        syncEditBar(editBarView, body);
    }

    /**
     * 绑定编辑条控件（只在浮窗创建时执行一次）。
     * 回调里通过 {@link #editBarImageTag} 动态取当前贴图，所以同一个编辑条可以服务任意贴图。
     */
    private static void bindEditBar(@NonNull View bar) {
        SeekBar seekBar = bar.findViewById(R.id.opacitySeekBar);
        if (seekBar != null) {
            seekBar.setMax(100);
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    // fromUser=false 是我们同步数值（切换编辑目标）时触发的，不能回写，否则会误改透明度
                    if (!fromUser) return;
                    String tag = editBarImageTag;
                    View target = currentEditBarBody();
                    // 内容层 + 根 View 的阴影背景一起调（见 applyStickerOpacity）：
                    // 只调内容层的话，ShadowLayout 的灰色阴影会留在屏幕上，调到 0 时残留一团灰影
                    if (tag != null && target != null) applyStickerOpacity(tag, target, progress / 100f);
                    updateOpacityValue(bar, progress);
                }

                @Override
                public void onStartTrackingTouch(SeekBar sb) { }

                @Override
                public void onStopTrackingTouch(SeekBar sb) { }
            });
        }

        // 按钮是「图标在上 + 文字在下」的 LinearLayout 容器（图标 / 文字本身不可点击，
        // 点击事件会落到容器上），所以这里统一按 View 处理。
        View done = bar.findViewById(R.id.editBarDone);
        if (done != null) done.setOnClickListener(v -> hideEditBar());

        // 保存：把贴图存进系统相册。图片贴图直接存原图；文字贴图先把文字内容渲染成图片再存。
        // 点击时先取一次编辑目标（保存过程不改变 editBarImageTag，但与其他按钮保持同一写法）。
        View save = bar.findViewById(R.id.editBarSave);
        if (save != null) save.setOnClickListener(v -> {
            String tag = editBarImageTag;
            if (tag != null) saveStickerToGallery(tag);
        });

        // 收起 / 关闭 / 关闭所有：原先由「横向拖出屏幕边缘弹出的操作条」提供，现已合并进本编辑条。
        // 点击时先取当前编辑目标（收起/关闭会连带收起编辑条，editBarImageTag 会被清空）。
        View collapse = bar.findViewById(R.id.editBarCollapse);
        if (collapse != null) collapse.setOnClickListener(v -> {
            String tag = editBarImageTag;
            if (tag != null) collapseSticker(tag); // 收起：隐藏贴图，在屏边显示可点击/可拖动的把手
        });
        View close = bar.findViewById(R.id.editBarClose);
        if (close != null) close.setOnClickListener(v -> {
            String tag = editBarImageTag;
            if (tag != null) closeSticker(tag);
        });
        View closeAll = bar.findViewById(R.id.editBarCloseAll);
        if (closeAll != null) closeAll.setOnClickListener(v -> closeAllStickers());

        applyEditBarActions(bar);
    }

    // ===================== 保存贴图到相册 =====================

    /** 保存到相册时申请存储权限的请求码（仅 Android 9 及以下会走到） */
    private static final int REQ_SAVE_STORAGE = 0x5101;
    /** 正在保存中的贴图 tag：防止连点时排队做多次大图压缩 */
    private static final Set<String> savingTags = new HashSet<>();

    /**
     * 把贴图保存到系统相册（Pictures/Snipaste）。
     * <ul>
     *   <li>图片贴图：取 {@code imageOutter} 背景中的<b>原始 Bitmap</b>，1:1 导出，
     *       不受贴图透明度、屏幕显示尺寸影响</li>
     *   <li>文字贴图：把 {@code textContent}（TextView）当前内容渲染成 Bitmap —— 所见即所得，
     *       字号 / 换行 / 背景与屏幕上完全一致</li>
     * </ul>
     * Android 10 及以上走 MediaStore 分区存储，<b>无需任何权限</b>；Android 9 及以下需要
     * WRITE_EXTERNAL_STORAGE，未授权时先发起申请，授权后再次点击「保存」即可完成。
     * <p>
     * <b>交互要点（反馈慢 / 无反馈的修复）</b>：点击后<b>立刻</b>弹「正在保存…」并把按钮置为
     * busy 态（变暗 + 文案改「保存中」），不等压缩完成 —— 大图 PNG 压缩 + MediaStore 写入
     * 可能耗时 1~3 秒，只在这一步之后才提示，用户会以为点了没反应。
     * 后台任务整体包 <b>try/catch(Throwable)</b>：压缩大图抛 OOM 等异常时线程会静默死掉，
     * 原来就是「完全没有任何提示」的主因。
     */
    public static void saveStickerToGallery(@NonNull String tag) {
        final Context appContext = QDApplication.getContext();
        if (appContext == null) return;

        // 保存本身只需要 Context（GallerySaver 走 ContentResolver），Activity 仅用于旧系统申请权限，
        // 所以这里不再因拿不到 Activity 就静默返回（磁贴 / 分享入口创建、原 Activity 已 finish 时会拿不到）。
        Activity activity = currentActivity(tag);

        // 防重入：同一张贴图正在保存时忽略后续点击
        synchronized (savingTags) {
            if (!savingTags.add(tag)) return;
        }

        // ① 立即反馈（不等压缩）：Toast 用 ApplicationContext，原 Activity 已销毁也照常显示
        Toast.makeText(appContext, R.string.save_sticker_saving, Toast.LENGTH_SHORT).show();
        setEditBarSaveBusy(true);

        // ② 旧系统（Android 9-）公共目录写入需要存储权限。申请权限必须由 Activity 发起；
        //    浮窗入口拿不到 onRequestPermissionsResult，授权后再次点击保存即可。
        if (GallerySaver.needsStoragePermission() && !GallerySaver.hasStoragePermission(appContext)) {
            Toast.makeText(appContext, R.string.save_sticker_need_permission, Toast.LENGTH_LONG).show();
            finishSave(tag, appContext, false, false);
            if (activity != null) {
                try {
                    ActivityCompat.requestPermissions(activity,
                            new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_SAVE_STORAGE);
                } catch (Exception e) {
                    Log.e(TAG, "requestPermissions failed: " + e.getMessage());
                }
            }
            return;
        }

        // ③ 取内容（读 View，必须在主线程）
        final Bitmap target = renderStickerBitmap(tag);
        if (target == null) {
            finishSave(tag, appContext, false, true);
            return;
        }

        // ④ 大图 PNG 压缩 + 落盘放后台线程：编辑条处于浮窗里，主线程卡顿会直接影响悬浮窗响应。
        new Thread(() -> {
            boolean ok = false;
            try {
                ok = GallerySaver.saveBitmap(appContext, target); // 不透明走 JPEG：比 PNG 快一个量级
                AppLog.d(TAG, "保存贴图 tag=" + tag + " 成功=" + ok
                        + " 尺寸=" + target.getWidth() + "x" + target.getHeight());
            } catch (Throwable t) {
                // 压缩大图可能抛 OOM：原来没有兜底，线程直接死掉 → 用户完全看不到任何提示
                AppLog.e(TAG, "保存贴图异常 tag=" + tag, t);
                ok = false;
            }
            finishSave(tag, appContext, ok, true);
        }, "sticker-save").start();
    }

    /**
     * 保存收尾：解除防重入标记、恢复按钮状态、在主线程弹结果提示。
     *
     * @param toastResult false 表示本次不弹结果 Toast（调用方已经给过更具体的提示，如「需要权限」）
     */
    private static void finishSave(@NonNull String tag, @NonNull Context appContext,
                                   boolean ok, boolean toastResult) {
        synchronized (savingTags) {
            savingTags.remove(tag);
        }
        sMainHandler.post(() -> {
            setEditBarSaveBusy(false);
            if (toastResult) {
                Toast.makeText(appContext,
                        ok ? R.string.save_sticker_success : R.string.save_sticker_failed,
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 编辑条「保存」按钮的进行中状态：变暗、文案改「保存中」、屏蔽重复点击 */
    private static void setEditBarSaveBusy(boolean busy) {
        View bar = editBarView;
        if (bar == null) return;
        View save = bar.findViewById(R.id.editBarSave);
        if (save != null) {
            save.setAlpha(busy ? 0.4f : 1f);
            save.setEnabled(!busy);
        }
        TextView label = bar.findViewById(R.id.editBarSaveLabel);
        if (label != null) {
            label.setText(busy ? R.string.save_sticker_saving_label : R.string.sticker_edit_save);
        }
    }

    /**
     * 把贴图渲染成可保存的 Bitmap；内容取不到时返回 null。
     * <p>
     * 只取贴图内容本身，<b>不带</b>贴图容器的透明度设置——保存的是「原图」，
     * 而不是「屏幕上正被调到 30% 透明的那张图」。
     */
    @Nullable
    private static Bitmap renderStickerBitmap(@NonNull String tag) {
        View floatView = EasyFloat.getAppFloatView(tag);
        if (floatView == null) return null;

        // 1) 图片贴图：imageOutter 的背景即创建时放入的原始 Bitmap
        View imageOutter = floatView.findViewById(R.id.imageOutter);
        if (imageOutter != null) {
            Drawable d = imageOutter.getBackground();
            if (d instanceof BitmapDrawable) {
                Bitmap src = ((BitmapDrawable) d).getBitmap();
                if (src != null && !src.isRecycled()) return src;
            }
        }

        // 2) 文字贴图：渲染 TextView 当前内容（文字贴图没有 imageOutter）
        TextView textContent = floatView.findViewById(R.id.textContent);
        if (textContent != null) {
            int w = textContent.getWidth();
            int h = textContent.getHeight();
            if (w <= 0 || h <= 0) {
                // 尚未完成布局（极少见）：按不受限规格量一次，避免导出 0 尺寸
                textContent.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                w = textContent.getMeasuredWidth();
                h = textContent.getMeasuredHeight();
            }
            if (w <= 0 || h <= 0) return null;
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            textContent.draw(new Canvas(bitmap));
            return bitmap;
        }
        return null;
    }

    // ===================== 「正在编辑的贴图」高亮边框 =====================

    /**
     * 给「正在编辑的贴图」加上 / 去掉高亮边框：多张贴图同在屏幕上时，一眼看出在编辑哪一张。
     *
     * <p>挂在贴图根 View（ShadowLayout）的 <b>foreground</b> 上，原因有二：
     * <ul>
     *   <li>ShadowLayout 自带 4dp（{@code sl_shadowRadius}）padding，内容层天然内缩，
     *       边框正好落在贴图内容之外的阴影带上，<b>不遮挡任何内容</b>，也随贴图拖动 / 缩放自动跟随；</li>
     *   <li>不碰 background —— 图片贴图的原始 Bitmap 存在 {@code imageOutter} 的 background 上，
     *       根 View 的 background 则是 ShadowLayout 自己生成的阴影位图，都不能被覆盖掉。</li>
     * </ul>
     */
    private static void setEditHighlight(@Nullable String tag, boolean on) {
        if (tag == null) return;
        View body = sliderStickerBodies.get(tag);
        if (body == null) return;
        if (on) {
            if (!stickerOriginalForegrounds.containsKey(tag)) {
                stickerOriginalForegrounds.put(tag, body.getForeground()); // 可能是 null，用 containsKey 判断
            }
            body.setForeground(new StickerHighlightDrawable(density(),
                    ContextCompat.getColor(QDApplication.getContext(), R.color.sticker_accent)));
        } else {
            body.setForeground(stickerOriginalForegrounds.remove(tag)); // 原样恢复（通常是 null，即清除）
        }
    }

    /**
     * 贴图的「内容层」：图片贴图是 {@code imageOutter}（background 即原图），文字贴图是 {@code TextStickerView}。
     *
     * <p>不透明度作用在这一层、而不是根 ShadowLayout：View 的 alpha 会乘到整棵子树（含 foreground），
     * 若作用在根上，把不透明度调到 30% 时编辑态高亮边框也会跟着淡到几乎看不见。
     * 作用在内容层后，边框始终是实色。
     *
     * <p>代价是根 View 的<b>背景</b>不在这棵子树里、得单独处理 —— 它正是 ShadowLayout 生成的
     * 灰色阴影位图，见 {@link #applyStickerOpacity}。
     */
    @NonNull
    private static View stickerContentLayer(@NonNull View body) {
        View content = body.findViewById(R.id.imageOutter);
        if (content == null) content = body.findViewById(R.id.textStickerView);
        return content != null ? content : body;
    }

    /**
     * 设置贴图不透明度。必须同时作用于两处，缺一处就会看到「残留」：
     *
     * <ul>
     *   <li><b>内容层</b>（{@link #stickerContentLayer}）—— 贴图内容本身。高亮边框挂在根 View 的
     *       foreground 上，若把 alpha 加在根 View 上边框会一起淡，故只能加在内容层；</li>
     *   <li><b>根 View 的背景</b>（{@link #applyShadowAlpha}）—— ShadowLayout 生成的
     *       <b>灰色阴影位图</b>。它不在内容层里，不一起淡的话，不透明度调到 0 之后
     *       屏幕上会残留一团半透明灰影。</li>
     * </ul>
     */
    private static void applyStickerOpacity(@NonNull String tag, @NonNull View body, float alpha) {
        float a = alpha < 0f ? 0f : (alpha > 1f ? 1f : alpha);
        stickerAlphas.put(tag, a);
        stickerContentLayer(body).setAlpha(a); // 内容层：让高亮边框保持实色
        applyShadowAlpha(tag, body);           // 根 View 背景：让灰色阴影跟着一起淡
    }

    /**
     * 把根 View 背景（ShadowLayout 生成的阴影位图）的 alpha 同步为该贴图当前的不透明度。
     * 背景还没生成时直接跳过 —— ShadowLayout 要等到第一次 {@code onSizeChanged} 才创建它，
     * 之后布局监听会再跑一次补上。
     */
    private static void applyShadowAlpha(@NonNull String tag, @NonNull View body) {
        Drawable bg = body.getBackground();
        if (bg == null) return;
        Float stored = stickerAlphas.get(tag);
        int alpha = Math.round((stored == null ? 1f : stored) * 255f);
        // 值没变就不碰：setAlpha 会 invalidate，而布局变化回调触发得很频繁
        if (bg.getAlpha() != alpha) bg.setAlpha(alpha);
    }

    /** 把滑块与百分比同步为「编辑条当前贴图」的透明度 */
    private static void syncEditBar(@NonNull View bar, @NonNull View body) {
        int progress = (int) (stickerContentLayer(body).getAlpha() * 100);
        SeekBar seekBar = bar.findViewById(R.id.opacitySeekBar);
        if (seekBar != null) seekBar.setProgress(progress); // fromUser=false，不会回写
        updateOpacityValue(bar, progress);
    }

    private static void updateOpacityValue(@NonNull View bar, int progress) {
        TextView tv = bar.findViewById(R.id.opacityValue);
        if (tv != null) tv.setText(progress + "%");
    }

    /** 编辑条当前作用的贴图本体；未显示或贴图已销毁时返回 null */
    @Nullable
    private static View currentEditBarBody() {
        return editBarImageTag == null ? null : sliderStickerBodies.get(editBarImageTag);
    }

    /**
     * 注册一项「编辑贴图」扩展操作（后续的画笔 / 裁剪 / 文字等直接调用本方法即可）。
     * 按钮样式与底部固定按钮一致（图标在上、文字在下），显示在编辑条的扩展行里。
     * 编辑条已显示则立即追加按钮；未显示则记下来，下次弹出时一并生成。
     *
     * @param iconRes 按钮图标（建议 24dp 的 vector drawable）
     * @param textRes 按钮文案
     * @param action  点击行为
     */
    public static void addEditBarAction(int iconRes, int textRes, @NonNull Runnable action) {
        editBarActions.add(new EditBarAction(iconRes, textRes, action));
        if (editBarView != null) applyEditBarActions(editBarView);
    }

    /** 按 {@link #editBarActions} 重建扩展操作按钮（幂等：每次先清空再加） */
    private static void applyEditBarActions(@NonNull View bar) {
        LinearLayout container = bar.findViewById(R.id.editBarActions);
        if (container == null) return;
        container.removeAllViews();
        for (EditBarAction a : editBarActions) {
            container.addView(createEditBarActionView(bar, a));
        }
        // 空操作区不占位：连同外层滚动容器一起隐藏，避免留下空白行
        int visibility = editBarActions.isEmpty() ? View.GONE : View.VISIBLE;
        View scroll = bar.findViewById(R.id.editBarActionsScroll);
        if (scroll != null) scroll.setVisibility(visibility);
        container.setVisibility(visibility);
    }

    /** 生成一个扩展操作按钮：图标在上、文字在下，与底部固定按钮同款 */
    @NonNull
    private static View createEditBarActionView(@NonNull View bar, @NonNull EditBarAction a) {
        LinearLayout item = new LinearLayout(bar.getContext());
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setBackgroundResource(R.drawable.bg_edit_action);
        item.setClickable(true);
        item.setFocusable(true);
        item.setPadding(dp(12), dp(8), dp(12), dp(6));
        item.setOnClickListener(v -> a.action.run());

        ImageView icon = new ImageView(bar.getContext());
        icon.setImageResource(a.iconRes);
        icon.setColorFilter(0xFFFFFFFF);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        item.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));

        TextView label = new TextView(bar.getContext());
        label.setText(a.textRes);
        label.setTextSize(11);
        label.setTextColor(0xFFFFFFFF);
        label.setMaxLines(1);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = dp(4);
        item.addView(label, labelParams);
        return item;
    }

    private static int dp(int value) {
        return (int) (value * density());
    }

    /** 收起编辑条（贴图仍存活，下次双击直接 show 回来，不重建浮窗） */
    private static void hideEditBar() {
        setEditHighlight(editBarImageTag, false); // 趁 editBarImageTag 还指着目标贴图，先摘掉高亮
        editBarShown = false;
        editBarImageTag = null;
        if (!editBarCreated) return;
        try {
            EasyFloat.hideAppFloat(EDIT_BAR_TAG);
        } catch (Exception e) {
            Log.e(TAG, "hideEditBar failed: " + e.getMessage());
        }
    }

    /** 彻底销毁编辑条浮窗（目标贴图被关闭 / 已无任何贴图时调用） */
    private static void destroyEditBar() {
        setEditHighlight(editBarImageTag, false);
        editBarCreated = false;
        editBarShown = false;
        editBarView = null;
        editBarImageTag = null;
        try {
            EasyFloat.dismissAppFloat(EDIT_BAR_TAG);
        } catch (Exception e) {
            Log.e(TAG, "destroyEditBar failed: " + e.getMessage());
        }
    }

    /**
     * 在浮窗的 touchEvent 回调里做「完成态双击」判定：必须是两次「按下→抬起且几乎未移动」的完整点击，
     * 且第二次抬手距第一次抬手 < DOUBLE_TAP_TIME，才触发 toggleOpacitySlider（弹出/收起透明度滑块）。
     * 放在浮窗层级而非 View 层级，可彻底避开内部 View 吞事件 / 框架手势被拖拽取消的问题。
     * 相比「仅比较两次 ACTION_DOWN 的时间」，完成态判定能避免单次轻触（被浮窗分发成多次相邻 DOWN、
     * 或残留上次手势时间戳）误触发滑块。原「长按」交互已改为「双击」。
     */
    public static void handleFloatTouch(@NonNull String tag, @NonNull View view, @NonNull MotionEvent event) {
        // 两指捏合缩放：手势进行中时独占本次触摸，不再走拖动 / 双击（否则两指移动会被当成拖拽）
        if (handlePinchGesture(tag, view, event)) return;
        applyDragOut(tag, view, event); // 拖出屏幕边缘的裁切视觉(与下方双击检测并行，互不干扰)
        int action = event.getAction();
        TapState s = tapStates.get(tag);
        if (s == null) {
            s = new TapState();
            tapStates.put(tag, s);
        }
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                s.downX = event.getRawX();
                s.downY = event.getRawY();
                s.moved = false;
                break;
            case MotionEvent.ACTION_MOVE: {
                float dist = (float) Math.hypot(event.getRawX() - s.downX, event.getRawY() - s.downY);
                if (dist > TAP_SLOP) {
                    s.moved = true;
                    s.firstTapPending = false; // 拖拽：取消待定的第一次点击
                }
                break;
            }
            case MotionEvent.ACTION_UP: {
                long now = System.currentTimeMillis();
                if (!s.moved) {
                    // 这是一次完成的点击
                    if (s.firstTapPending && (now - s.firstTapUpTime) < DOUBLE_TAP_TIME) {
                        s.firstTapPending = false;
                        toggleOpacitySlider(tag); // 双击：弹出/收起底部编辑条
                    } else {
                        s.firstTapPending = true;
                        s.firstTapUpTime = now;
                    }
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                s.firstTapPending = false;
                s.moved = false;
                break;
        }
    }

    private static void moveFloatWindow(@NonNull View floatView, int x, int y) {
        try {
            // getAppFloatView 返回的是我们布局的根 View，它被 EasyFloat 包在若干层容器里再加到 WindowManager。
            // 真正的「窗口根」是其中 LayoutParams 为 WindowManager.LayoutParams 的那一层。向上逐级查找它，
            // 而不是直接取 getParent()（层级结构可能不止一层），找到后更新其位置参数。
            View windowView = floatView;
            ViewParent p = floatView.getParent();
            while (p instanceof View) {
                View pv = (View) p;
                if (pv.getLayoutParams() instanceof WindowManager.LayoutParams) {
                    windowView = pv;
                    break;
                }
                p = pv.getParent();
            }
            WindowManager wm = (WindowManager) windowView.getContext().getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return;
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) windowView.getLayoutParams();
            lp.x = x;
            lp.y = y;
            wm.updateViewLayout(windowView, lp);
        } catch (Exception e) {
            Log.e(TAG, "moveFloatWindow failed: " + e.getMessage());
        }
    }

    /**
     * 通过 tag 直连 EasyFloat 内部 AppFloatManager，直接改 WindowManager.LayoutParams（对 frameLayout 操作）。
     * 这是真正能改窗口位置的方法——moveFloatWindow(View) 通过 getLayoutParams() 找 WM.LayoutParams
     * 在 ParentFrameLayout 上永远找不到（它是 FrameLayout 子类，getLayoutParams 返回 FrameLayout.LayoutParams）。
     */
    private static void moveFloatWindowByTag(@NonNull String tag, int x, int y) {
        try {
            com.lzf.easyfloat.widget.appfloat.AppFloatManager afm =
                    com.lzf.easyfloat.widget.appfloat.FloatManager.INSTANCE.getAppFloatManager(tag);
            if (afm == null) return;
            afm.params.x = x;
            afm.params.y = y;
            View frameLayout = afm.getFrameLayout();
            if (frameLayout == null) return;
            afm.windowManager.updateViewLayout(frameLayout, afm.params);
        } catch (Exception e) {
            Log.e(TAG, "moveFloatWindowByTag failed: " + e.getMessage());
        }
    }

    /**
     * 收起编辑条（贴图仍存活，下次双击可再弹出）：贴图被收起 / 关闭时调用。
     * 只作用于「编辑条当前指向的那张贴图」——正服务另一张贴图时不打断它。
     * 与 dismissOpacitySlider 的区别：这里【不】移除 sliderStickerBodies / sliderActivities，
     * 因为贴图本身还活着，下次双击仍需用它们重新弹出编辑条。
     */
    public static void hideOpacitySlider(@NonNull String imageTag) {
        tapStates.remove(imageTag); // 清掉本次双击的待判定状态，避免重复触发
        if (editBarImageTag != null && !editBarImageTag.equals(imageTag)) return;
        hideEditBar();
    }

    /** 关闭编辑条并清理该贴图的引用（贴图被真正关闭/清空时调用，防泄漏） */
    public static void dismissOpacitySlider(@NonNull String imageTag) {
        // 清理双击检测状态
        tapStates.remove(imageTag);
        pinchStates.remove(imageTag); // 捏合手势状态一并清掉，防泄漏
        // 编辑条正指着它 -> 先摘掉高亮边框（下面就要把 body 引用移除，之后就没机会了）
        if (imageTag.equals(editBarImageTag)) setEditHighlight(imageTag, false);
        sliderStickerBodies.remove(imageTag);
        stickerOriginalForegrounds.remove(imageTag);
        stickerAlphas.remove(imageTag);
        // 释放对贴图 Activity 的强引用，避免泄漏
        sliderActivities.remove(imageTag);
        // 编辑条正服务这张贴图（或已无任何贴图）→ 连浮窗一起销毁，别留一个隐藏的空窗
        if (imageTag.equals(editBarImageTag) || sliderStickerBodies.isEmpty()) destroyEditBar();
    }

    // ===================== 拖出屏幕边缘（仅裁切视觉，松手不回弹、不弹菜单） =====================

    /**
     * 在浮窗 touchEvent / drag 回调中调用。系统会把浮窗（TYPE_APPLICATION_OVERLAY）夹在屏幕物理边界内，
     * 无法真正拖出，故用「裁切」实现视觉拖出：当贴图被拖到某条边、手指继续朝该边推（窗口被系统夹住不再移动）时，
     * 按「手指超出窗口夹紧点的位移」把贴图内容沿该方向平移，超出窗口的部分被窗口裁掉，呈现「滑出屏幕」的效果。
     * 松手后不做任何处理：贴图保持在被裁切的位置，横向与纵向行为完全一致。
     * <p>
     * 关键：拖出量只由「手指 raw 坐标」与「手势起点窗口位置」推算，不依赖浮窗是否仍在移动。
     * 因此即使浮窗被系统夹紧、drag 回调不再上报位移，只要 touchEvent 持续上报手指坐标，裁切拖出依然成立。
     */
    public static void applyDragOut(@NonNull String tag, @NonNull View view, @NonNull MotionEvent event) {
        DragState s = dragStates.get(tag);
        if (s == null) {
            s = new DragState();
            dragStates.put(tag, s);
        }
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN: {
                s.moved = false;
                s.inited = true;
                s.overflowX = 0; s.overflowY = 0;
                s.edgeX = 0; s.edgeY = 0;
                s.startRawX = (int) event.getRawX();
                s.startRawY = (int) event.getRawY();
                // 起点视觉位置 = 本体真实屏幕坐标（getLocationOnScreen 直读，对任意 params/translation 组合成立）
                int[] loc = new int[2];
                view.getLocationOnScreen(loc);
                s.startWinX = loc[0];
                s.startWinY = loc[1];
                // 窗口帧原点：屏幕坐标 = params + origin + translation（origin 对本窗口恒定）。
                // 必须感知 origin：捏合后 params 可为负（窗口移出屏外），老写法不感知 origin 会在
                // 第一次把贴图推过屏幕边时跳一个 inset（「缩放后第一次拖动会跳」的根因）。
                com.lzf.easyfloat.widget.appfloat.AppFloatManager afm =
                        com.lzf.easyfloat.widget.appfloat.FloatManager.INSTANCE.getAppFloatManager(tag);
                if (afm != null) {
                    s.originX = loc[0] - afm.params.x - (int) view.getTranslationX();
                    s.originY = loc[1] - afm.params.y - (int) view.getTranslationY();
                }
                s.winW = view.getWidth();
                s.winH = view.getHeight();
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!s.inited) {
                    s.inited = true;
                    s.startRawX = (int) event.getRawX();
                    s.startRawY = (int) event.getRawY();
                    int[] loc = new int[2];
                    view.getLocationOnScreen(loc);
                    s.startWinX = loc[0];
                    s.startWinY = loc[1];
                    com.lzf.easyfloat.widget.appfloat.AppFloatManager afm2 =
                            com.lzf.easyfloat.widget.appfloat.FloatManager.INSTANCE.getAppFloatManager(tag);
                    if (afm2 != null) {
                        s.originX = loc[0] - afm2.params.x - (int) view.getTranslationX();
                        s.originY = loc[1] - afm2.params.y - (int) view.getTranslationY();
                    }
                    s.winW = view.getWidth();
                    s.winH = view.getHeight();
                }
                s.moved = true;
                Point screen = screenSize();
                if (screen.x <= 0 || screen.y <= 0 || s.winW <= 0 || s.winH <= 0) break;
                // 新视觉位置 = 起点视觉位置 + 手指位移（视觉位置 = 本体真实屏幕坐标，允许出屏）
                int newVisualX = s.startWinX + (int) (event.getRawX() - s.startRawX);
                int newVisualY = s.startWinY + (int) (event.getRawY() - s.startRawY);
                int maxX = Math.max(0, screen.x - s.winW);
                int maxY = Math.max(0, screen.y - s.winH);
                // params 夹在屏内（系统友好），被夹掉的量交给 translation 补偿；
                // 绝对位置恒等式：params + origin + translation ≡ newVisual —— 跨屏边不再跳。
                int newParamsX = Math.max(0, Math.min(maxX, newVisualX - s.originX));
                int newParamsY = Math.max(0, Math.min(maxY, newVisualY - s.originY));
                int newTx = newVisualX - (newParamsX + s.originX);
                int newTy = newVisualY - (newParamsY + s.originY);
                // 直接改 WindowManager 位置 + translation 补偿
                moveFloatWindowByTag(tag, newParamsX, newParamsY);
                view.setTranslationX(newTx);
                view.setTranslationY(newTy);
                view.invalidate();
                s.overflowX = Math.abs(newTx);
                s.edgeX = newTx < 0 ? -1 : (newTx > 0 ? 1 : 0);
                s.overflowY = Math.abs(newTy);
                s.edgeY = newTy < 0 ? -1 : (newTy > 0 ? 1 : 0);
                break;
            }
            case MotionEvent.ACTION_UP: {
                // 横向 / 纵向统一：松手后不回弹、不弹菜单，贴图保持在当前裁切位置，
                // 只保留「可以滑出屏幕」的能力（收起 / 关闭 已移到双击弹出的编辑条里）。
                dragStates.remove(tag);
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                s.inited = false;
                s.overflowX = 0; s.overflowY = 0;
                s.edgeX = 0; s.edgeY = 0;
                break;
        }
    }

    /** 关闭所有贴图的回调：由主页注册，复用主页右上角「关闭所有贴图」入口实现。
     *  注意：本回调仅在主界面(HomeFragment)存活时有效；磁贴/分享入口创建的贴图不保证主界面存活，
     *  故「关闭所有」应以 closeAllStickers() 为准、本回调仅作为最佳努力的补充。 */
    private static Runnable sCloseAllCallback;
    public static void setCloseAllCallback(Runnable cb) { sCloseAllCallback = cb; }

    /**
     * 关闭所有由本 helper 创建的贴图（磁贴/分享）。自包含、不依赖任何外部回调，
     * 因此即使主界面 HomeFragment 已销毁（如直接走磁贴/分享入口贴图后未打开主界面），
     * 也能从贴图拖出操作条里的「关闭所有贴图」按钮正常清掉它们。
     * 主界面仍在时，再额外触发其回调一并关闭主界面自身创建的贴图（最佳努力）。
     */
    public static void closeAllStickers() {
        AppLog.d("SharePasteHelper", "关闭全部贴图 count=" + helperImageTags.size());
        for (String tag : new ArrayList<>(helperImageTags)) {
            closeSticker(tag);
        }
        helperImageTags.clear();
        textStickerContents.clear();
        if (sCloseAllCallback != null) sCloseAllCallback.run();
    }

    /** 收起(最小化)到屏幕边缘：隐藏贴图本体，在拖出边显示可点击/可拖动的把手 */
    public static void collapseSticker(@NonNull String tag) {
        Point screen = screenSize();
        View sv = EasyFloat.getAppFloatView(tag);
        Rect r = sv != null ? stickerRect(sv) : null;
        int edge = (r != null) ? edgeOf(r, screen) : EDGE_RIGHT;
        collapseSticker(tag, r, screen, edge);
    }

    private static void collapseSticker(@NonNull String tag, Rect r, @NonNull Point screen, int edge) {
        hideOpacitySlider(tag); // 收起时一并收起编辑条
        // 先截缩略图再隐藏（文字贴图需要可见状态才能渲染到 Bitmap）
        Bitmap thumb = makeThumbnail(tag);
        try {
            EasyFloat.hideAppFloat(tag);
        } catch (Exception e) {
            Log.e(TAG, "hideAppFloat failed: " + e.getMessage());
            AppLog.e("SharePasteHelper", "hideAppFloat 失败 tag=" + tag, e);
        }
        collapsedTags.add(tag);
        // 按设置选择收起形式：贴边条 / 缩略图
        int mode = Settings.getCollapseMode(QDApplication.getContext());
        AppLog.d("SharePasteHelper", "收起贴图 tag=" + tag + " 形式=" + mode);
        if (mode == Settings.COLLAPSE_MODE_STRIP) {
            int cx = (r != null) ? r.centerX() : screen.x / 2;
            int cy = (r != null) ? r.centerY() : screen.y / 2;
            showStripHandle(tag, edge, screen, cx, cy);
        } else {
            showThumbHandle(tag, r, screen, thumb);
        }
    }

    /** 缩略图把手：点击恢复、可拖动(拖到别处释放后吸附回最近边)。thumb 可为 null(退化成纯色条) */
    private static void showThumbHandle(@NonNull String tag, Rect r, @NonNull Point screen, Bitmap thumb) {
        Activity activity = currentActivity(tag);
        if (activity == null) return;
        handleActivities.put(tag, activity);
        String handleTag = tag + HANDLE_SUFFIX;
        if (EasyFloat.getAppFloatView(handleTag) != null) return;
        // 无位置信息时(理论上不会)兜底放左下角
        int[] pos = (r != null) ? handlePosition(r, screen)
                : new int[]{ (int) (8 * density()), (int) (screen.y - HANDLE_H_DP * density()) };
        EasyFloat.with(activity)
                .setLayout(R.layout.image_paste_handle)
                .setShowPattern(ShowPattern.ALL_TIME)
                .setLocation(pos[0], pos[1])
                .setTag(handleTag)
                .setDragEnable(true) // 允许拖动把手（修复最小化后无法拖动）
                .registerCallbacks(newHandleCallbacks(tag, handleTag, false))
                .show();
        View handle = EasyFloat.getAppFloatView(handleTag);
        if (handle == null) return;
        ImageView iv = handle.findViewById(R.id.handleThumb);
        if (iv != null && thumb != null) iv.setImageBitmap(thumb);
    }

    /** 贴边条把手：细条贴边 + 透明触摸扩展，点击恢复、可拖动。
     *  视觉条 stripBar 仅 12dp 始终贴屏幕边；触摸扩展 stripPad 在内侧 24dp 让 EasyFloat 整个
     *  36dp 宽窗口都能命中点击/拖动。XML 已固定 FrameLayout=36dp×72dp(竖条)。 */
    private static void showStripHandle(@NonNull String tag, int edge, @NonNull Point screen) {
        showStripHandle(tag, edge, screen, screen.x / 2, screen.y / 2);
    }

    private static void showStripHandle(@NonNull String tag, int edge, @NonNull Point screen, int cx, int cy) {
        // 贴图可能已在两次 post 之间被恢复，不再需要创建把手
        if (!collapsedTags.contains(tag)) return;
        Activity activity = currentActivity(tag);
        // 磁贴/分享入口创建的贴图，其原 Activity 可能已 finish；回退到创建把手时保存的有效引用
        if (activity == null) activity = handleActivities.get(tag);
        if (activity == null) return;
        handleActivities.put(tag, activity);
        String handleTag = tag + HANDLE_SUFFIX;
        if (EasyFloat.getAppFloatView(handleTag) != null) return;
        boolean vertical = (edge == EDGE_LEFT || edge == EDGE_RIGHT);
        // 视觉条尺寸：竖条 12dp×72dp，横条 72dp×12dp
        final int barW = (int) ((vertical ? STRIP_THICK_DP : STRIP_LEN_DP) * density());
        final int barH = (int) ((vertical ? STRIP_LEN_DP : STRIP_THICK_DP) * density());
        // 窗口总尺寸 = 视觉条 + 内侧触摸扩展（仅竖/横向，对应另一维度）
        final int touchExt = (int) (STRIP_TOUCH_EXT_DP * density());
        final int winW = vertical ? barW + touchExt : barW;
        final int winH = vertical ? barH : barH + touchExt;
        int[] pos = dockedStripPosition(edge, screen, winW, winH, cx, cy);
        EasyFloat.with(activity)
                .setLayout(R.layout.image_paste_strip)
                .setShowPattern(ShowPattern.ALL_TIME)
                .setLocation(pos[0], pos[1])
                .setTag(handleTag)
                .setDragEnable(true)
                .registerCallbacks(newHandleCallbacks(tag, handleTag, true))
                .show();
        View handle = EasyFloat.getAppFloatView(handleTag);
        if (handle == null) return;
        // 按 edge 调整视觉条与触摸扩展的左右/上下位置，确保视觉条始终贴屏幕边
        applyStripEdgeOrientation(handle, vertical, edge);
    }

    /** 把 stripBar 和 stripPad 按 edge 放到窗口对应边（视觉条靠屏幕边，触摸扩展开关到对侧）。 */
    private static void applyStripEdgeOrientation(@NonNull View handle, boolean vertical, int edge) {
        View bar = handle.findViewById(R.id.stripBar);
        View pad = handle.findViewById(R.id.stripPad);
        if (bar == null || pad == null) return;
        if (!(bar.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams barLp = (FrameLayout.LayoutParams) bar.getLayoutParams();
        FrameLayout.LayoutParams padLp = (FrameLayout.LayoutParams) pad.getLayoutParams();
        int gravityBarOnEdge, gravityPadOppositeEdge;
        if (vertical) {
            gravityBarOnEdge = (edge == EDGE_LEFT) ? (Gravity.LEFT | Gravity.CENTER_VERTICAL) : (Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            gravityPadOppositeEdge = (edge == EDGE_LEFT) ? (Gravity.RIGHT | Gravity.CENTER_VERTICAL) : (Gravity.LEFT | Gravity.CENTER_VERTICAL);
        } else {
            gravityBarOnEdge = (edge == EDGE_TOP) ? (Gravity.TOP | Gravity.CENTER_HORIZONTAL) : (Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            gravityPadOppositeEdge = (edge == EDGE_TOP) ? (Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL) : (Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        }
        barLp.gravity = gravityBarOnEdge;
        padLp.gravity = gravityPadOppositeEdge;
        bar.setLayoutParams(barLp);
        pad.setLayoutParams(padLp);
    }

    /** 统一构造收起把手的回调：轻点恢复、拖拽释放后吸附回最近边 */
    @NonNull
    private static OnFloatCallbacks newHandleCallbacks(@NonNull String tag, @NonNull String handleTag, boolean isStrip) {
        return new OnFloatCallbacks() {
            @Override
            public void createdResult(boolean isCreated, String msg, View view) { }
            @Override
            public void show(View view) { }
            @Override
            public void hide(View view) { }
            @Override
            public void dismiss() { }
            @Override
            public void touchEvent(View view, MotionEvent event) {
                handleHandleTouch(tag, handleTag, view, event, isStrip);
            }
            @Override
            public void drag(View view, MotionEvent event) { }
            @Override
            public void dragEnd(View view) {
                handleHandleDragEnd(tag, handleTag, view, isStrip);
            }
        };
    }

    /** 收起把手的轻点判定：未发生明显移动则视为单击 -> 恢复贴图 */
    private static void handleHandleTouch(@NonNull String tag, @NonNull String handleTag, @NonNull View view,
                                          @NonNull MotionEvent event, boolean isStrip) {
        TapState s = handleTapStates.get(handleTag);
        if (s == null) { s = new TapState(); handleTapStates.put(handleTag, s); }
        // 贴边条仅 12dp 厚，手指自然晃动容易超出普通 TAP_SLOP，使用更大的把手专用容差
        final int slop = isStrip ? HANDLE_TAP_SLOP : TAP_SLOP;
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                s.downX = event.getRawX();
                s.downY = event.getRawY();
                s.moved = false;
                break;
            case MotionEvent.ACTION_MOVE: {
                float dist = (float) Math.hypot(event.getRawX() - s.downX, event.getRawY() - s.downY);
                if (dist > slop) s.moved = true;
                break;
            }
            case MotionEvent.ACTION_UP:
                if (!s.moved) {
                    // 必须在本次触摸派发结束后再执行恢复：restoreSticker 会 dismiss/show 浮窗，
                    // 若在 EasyFloat 的 touchEvent 回调里同步执行，会在其触摸派发过程中修改浮窗状态，
                    // 导致 EasyFloat 内部崩溃（点击把手即闪退）。推迟到主线程下一轮派发即可避免。
                    final String t = tag;
                    view.post(() -> restoreSticker(t));
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                s.moved = false;
                break;
        }
    }

    /** 收起把手拖拽结束：模仿缩略图——仅 moveFloatWindow 到停靠位置（保持原朝向，不 dismiss+重建） */
    private static void handleHandleDragEnd(@NonNull String tag, @NonNull String handleTag, @NonNull View view, boolean isStrip) {
        // 把手可能已在轻点时恢复(被销毁)，无需再处理
        if (EasyFloat.getAppFloatView(handleTag) == null) return;
        Point screen = screenSize();
        Rect hr = stickerRect(view);
        if (hr == null) return;
        final Point sc = screen;
        final String ht = handleTag;
        final int cx = hr.centerX();
        final int cy = hr.centerY();
        final boolean verticalStrip = (hr.width() < hr.height());
        final int thumbEdge = !isStrip ? edgeOf(hr, screen) : 0;
        view.post(() -> {
            if (EasyFloat.getAppFloatView(ht) == null) return;
            int[] pos;
            int targetEdge = -1;
            if (isStrip) {
                targetEdge = verticalStrip
                        ? (cx < sc.x / 2 ? EDGE_LEFT : EDGE_RIGHT)
                        : (cy < sc.y / 2 ? EDGE_TOP  : EDGE_BOTTOM);
                int barW = (int) ((verticalStrip ? STRIP_THICK_DP : STRIP_LEN_DP) * density());
                int barH = (int) ((verticalStrip ? STRIP_LEN_DP  : STRIP_THICK_DP) * density());
                int touchExt = (int) (STRIP_TOUCH_EXT_DP * density());
                int winW = verticalStrip ? barW + touchExt : barW;
                int winH = verticalStrip ? barH : barH + touchExt;
                pos = dockedStripPosition(targetEdge, sc, winW, winH, cx, cy);
            } else {
                pos = dockedThumbPosition(thumbEdge, sc,
                        (int) (HANDLE_W_DP * density()), (int) (HANDLE_H_DP * density()),
                        cx, cy);
            }
            View hv = EasyFloat.getAppFloatView(ht);
            if (hv != null) {
                moveFloatWindow(hv, pos[0], pos[1]);
                // 停靠边变化时同步切换 stripBar / stripPad 的 gravity 让视觉条始终贴屏幕边
                if (isStrip && targetEdge != -1) {
                    applyStripEdgeOrientation(hv, verticalStrip, targetEdge);
                }
            }
        });
    }

    /** 首次收起：按贴图所在边摆放缩略条(居中于该边) */
    private static int[] handlePosition(@NonNull Rect r, @NonNull Point screen) {
        int edge = edgeOf(r, screen);
        int hw = (int) (HANDLE_W_DP * density()), hh = (int) (HANDLE_H_DP * density());
        return dockedThumbPosition(edge, screen, hw, hh, r.centerX(), r.centerY());
    }

    /** 缩略图把手停靠到某边的位置；cx/cy 为把手中心(首次用贴图中心，拖拽后再停靠用把手当前中心) */
    private static int[] dockedThumbPosition(int edge, @NonNull Point screen, int w, int h, int cx, int cy) {
        int m = (int) (4 * density());
        int x, y;
        if (edge == EDGE_LEFT)       { x = m;                y = cy - h / 2; }
        else if (edge == EDGE_RIGHT) { x = screen.x - w - m; y = cy - h / 2; }
        else if (edge == EDGE_TOP)   { x = cx - w / 2;       y = m; }
        else                         { x = cx - w / 2;       y = screen.y - h - m; }
        x = Math.max(m, Math.min(x, screen.x - w - m));
        y = Math.max(m, Math.min(y, screen.y - h - m));
        return new int[]{ x, y };
    }

    /** 贴边条把手停靠到某边的位置；cx/cy 为把手中心 */
    private static int[] dockedStripPosition(int edge, @NonNull Point screen, int w, int h, int cx, int cy) {
        int m = (int) (4 * density());
        int x, y;
        if (edge == EDGE_LEFT)       { x = m;                y = cy - h / 2; }
        else if (edge == EDGE_RIGHT) { x = screen.x - w - m; y = cy - h / 2; }
        else if (edge == EDGE_TOP)   { x = cx - w / 2;       y = m; }
        else                         { x = cx - w / 2;       y = screen.y - h - m; }
        x = Math.max(m, Math.min(x, screen.x - w - m));
        y = Math.max(m, Math.min(y, screen.y - h - m));
        return new int[]{ x, y };
    }

    /** 点击收起把手 -> 恢复贴图（清除裁切，归位到屏幕内、完整可见） */
    public static void restoreSticker(@NonNull String tag) {
        AppLog.d("SharePasteHelper", "恢复贴图 tag=" + tag);
        try {
            EasyFloat.dismissAppFloat(tag + HANDLE_SUFFIX);
        } catch (Exception e) {
            Log.e(TAG, "dismiss handle failed: " + e.getMessage());
        }
        collapsedTags.remove(tag);
        handleTapStates.remove(tag + HANDLE_SUFFIX);
        handleActivities.remove(tag);
        try {
            EasyFloat.showAppFloat(tag);
        } catch (Exception e) {
            Log.e(TAG, "showAppFloat failed: " + e.getMessage());
        }
        // 恢复时清除可能的裁切偏移（拖出态），让贴图完整显示
        View sv = EasyFloat.getAppFloatView(tag);
        if (sv != null) {
            sv.setTranslationX(0);
            sv.setTranslationY(0);
        }
    }

    /** 彻底关闭贴图：关闭把手/编辑条/贴图本体（清场时调用） */
    public static void closeSticker(@NonNull String tag) {
        AppLog.d("SharePasteHelper", "关闭贴图 tag=" + tag);
        try {
            EasyFloat.dismissAppFloat(tag + HANDLE_SUFFIX);
        } catch (Exception ignored) {
        }
        collapsedTags.remove(tag);
        handleTapStates.remove(tag + HANDLE_SUFFIX);
        handleActivities.remove(tag);
        hideOpacitySlider(tag);
        helperImageTags.remove(tag);
        // 清理保存的原文
        textStickerContents.remove(tag);
        try {
            EasyFloat.dismissAppFloat(tag);
        } catch (Exception e) {
            Log.e(TAG, "dismissAppFloat failed: " + e.getMessage());
        }
    }

    private static void showImageFloat(@NonNull Activity activity, @NonNull Bitmap bitmap) {
        showImageFloatWithTag(activity, bitmap, "share_image_" + System.currentTimeMillis(), false);
    }

    /**
     * 从本地图片文件重新贴出（历史记录页「重新贴出」入口）。
     * 含悬浮窗权限检查；成功后走标准图片贴图流程（并会刷新该内容的历史记录时间）。
     */
    public static void pasteImageFromFile(@NonNull Activity activity, @NonNull String filePath) {
        File f = new File(filePath);
        if (!f.exists()) {
            Toast.makeText(activity, activity.getText(R.string.file_access_failed), Toast.LENGTH_SHORT).show();
            return;
        }
        if (PermissionUtils.checkPermission(activity)) {
            showPastedImageFromFile(activity, filePath);
        } else {
            DialogUtil.showPermissionDialog(activity, () -> showPastedImageFromFile(activity, filePath));
        }
    }

    private static void showPastedImageFromFile(@NonNull Activity activity, @NonNull String filePath) {
        Bitmap bitmap = BitmapFactory.decodeFile(filePath);
        if (bitmap == null) {
            Toast.makeText(activity, activity.getText(R.string.file_access_failed), Toast.LENGTH_SHORT).show();
            return;
        }
        showImageFloatWithTag(activity, bitmap, "history_" + System.currentTimeMillis(), false);
    }

    // ---------- 工具方法 ----------

    /**
     * 从 Content Uri 读取 Bitmap
     */
    public static Bitmap loadBitmapFromUri(@NonNull Context context, @NonNull Uri uri) {
        try {
            InputStream inputStream = context.getContentResolver().openInputStream(uri);
            if (inputStream != null) {
                Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
                inputStream.close();
                return bitmap;
            }
        } catch (Exception e) {
            Log.e(TAG, "loadBitmapFromUri failed: " + uri, e);
        }
        return null;
    }
}
