package com.to3g.snipasteandroid.lib;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 「正在编辑的贴图」高亮边框。
 *
 * <p>外观：一圈<b>内实外虚</b>的强调色边框（{@code R.color.sticker_accent}，与编辑条的不透明度进度条同色）——
 * 贴着贴图内容的那一侧是全实色，朝贴图边界方向逐渐变浅、到边界处完全透明，
 * 像一圈由内向外散开的光晕；最内沿再压 1dp 深色细线，淡紫在白底截图 / 白底文字上偏浅，
 * 靠它把内容边界「切」出来。
 *
 * <p><b>为什么不用 XML 的 {@code <shape><stroke/>}</b>：一是 shape 描边以形状边界为中心绘制，
 * 外面半圈会被 View 边界裁掉，实际可见粗细只有标称值的一半，且不同版本表现不一致；
 * 二是 {@code <stroke/>} 只能画单色，做不了「内实外虚」的渐变。这里直接用 {@link Canvas#drawRect}
 * 画四条渐变带 —— 四条带的实色内沿首尾相接，正好拼成一整圈矩形边框。
 *
 * <p>由 {@code SharePasteHelper#setEditHighlight} 挂在贴图根 View（ShadowLayout）的 foreground 上：
 * ShadowLayout 自带 {@code sl_shadowRadius}(4dp) 的 padding，内容层天然内缩，
 * 所以这圈边框绝大部分落在贴图内容之外的阴影带上、不遮内容，也随贴图拖动 / 缩放自动跟随。
 */
public class StickerHighlightDrawable extends Drawable {

    /** 高亮带总宽度(dp)：从贴图边界(外)朝内容方向延伸这么宽 */
    private static final float BAND_DP = 6f;
    /** 「实色段」在整条带里的占比（自内沿算起），其余部分向外渐隐到全透明 */
    private static final float SOLID_RATIO = 0.5f;
    /** 内沿定义线宽度(dp) */
    private static final float EDGE_WIDTH_DP = 1f;
    /** 内沿定义线颜色（半透明黑），压在实色段最内侧 */
    private static final int EDGE_COLOR = 0x66000000;
    /** 带子最多占短边的比例：贴图很小时整体收缩，避免四条带糊成一块实心 */
    private static final float MAX_BAND_RATIO = 0.34f;

    private final Paint bandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final int ringColor;

    /** 当前生效的带宽 / 定义线宽(px)，由 {@link #onBoundsChange} 按尺寸重算 */
    private float band;
    private float edge;
    /** 外部设定的整体透明度（默认 255），用它推导定义线的 alpha */
    private int alpha = 255;

    private LinearGradient topGradient;
    private LinearGradient bottomGradient;
    private LinearGradient leftGradient;
    private LinearGradient rightGradient;

    /**
     * @param density   屏幕密度
     * @param ringColor 强调色。由调用方传入 {@code R.color.sticker_accent}，
     *                  保证「贴图高亮边框」与「编辑条不透明度进度条」永远同色
     */
    public StickerHighlightDrawable(float density, int ringColor) {
        this.density = density > 0 ? density : 1f;
        this.ringColor = ringColor;
        edgePaint.setStyle(Paint.Style.FILL);
        edgePaint.setColor(EDGE_COLOR);
    }

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        super.onBoundsChange(bounds);
        rebuild(bounds);
    }

    /** 按当前 bounds 重建四条边的渐变（渐变用的是绝对坐标，所以尺寸一变就得重算） */
    private void rebuild(@NonNull Rect b) {
        final int w = b.width();
        final int h = b.height();
        if (w <= 0 || h <= 0) {
            band = 0f;
            return;
        }
        band = Math.min(BAND_DP * density, Math.min(w, h) * MAX_BAND_RATIO);
        if (band < 1f) { // 贴图小到画不下，干脆不画，免得糊成一个实心块
            band = 0f;
            return;
        }
        edge = Math.min(EDGE_WIDTH_DP * density, band * 0.4f);

        // 渐变方向：内沿(实色) -> 外沿 = 贴图边界(全透明)。
        // 0 ~ SOLID_RATIO 维持全实色，之后一路渐隐到透明。
        final int visible = ringColor;
        final int clear = ringColor & 0x00FFFFFF; // 同色透明：用 Color.TRANSPARENT 会向黑/白偏色
        final int[] colors = {visible, visible, clear};
        final float[] stops = {0f, SOLID_RATIO, 1f};

        topGradient = new LinearGradient(b.left, b.top + band, b.left, b.top, colors, stops, Shader.TileMode.CLAMP);
        bottomGradient = new LinearGradient(b.left, b.bottom - band, b.left, b.bottom, colors, stops, Shader.TileMode.CLAMP);
        leftGradient = new LinearGradient(b.left + band, b.top, b.left, b.top, colors, stops, Shader.TileMode.CLAMP);
        rightGradient = new LinearGradient(b.right - band, b.top, b.right, b.top, colors, stops, Shader.TileMode.CLAMP);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        final Rect b = getBounds();
        if (band <= 0f || b.width() <= 0 || b.height() <= 0) return;

        final float l = b.left;
        final float t = b.top;
        final float r = b.right;
        final float bo = b.bottom;
        final float in = band; // 内沿距贴图边界的距离

        // 四条带各自「内实外虚」。四块区域在四角相互重叠：实色段 alpha 已满，叠加不变；
        // 只有渐隐段叠加后略深一点，形成自然的「四角稍重」，不必额外处理。
        drawBand(canvas, l, t, r, t + in, topGradient);
        drawBand(canvas, l, bo - in, r, bo, bottomGradient);
        drawBand(canvas, l, t, l + in, bo, leftGradient);
        drawBand(canvas, r - in, t, r, bo, rightGradient);

        if (edge > 0f) {
            // 内沿定义线：贴在实色段的最内侧
            canvas.drawRect(l, t + in - edge, r, t + in, edgePaint);
            canvas.drawRect(l, bo - in, r, bo - in + edge, edgePaint);
            canvas.drawRect(l + in - edge, t, l + in, bo, edgePaint);
            canvas.drawRect(r - in, t, r - in + edge, bo, edgePaint);
        }
    }

    /** 画一条渐变带：区域即最终可见范围，渐变按绝对坐标采样，所以直接画这块矩形即可 */
    private void drawBand(Canvas canvas, float l, float t, float r, float b, LinearGradient gradient) {
        if (r <= l || b <= t || gradient == null) return;
        bandPaint.setShader(gradient);
        canvas.drawRect(l, t, r, b, bandPaint);
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        bandPaint.setAlpha(alpha);
        edgePaint.setAlpha(alpha * (EDGE_COLOR >>> 24) / 255);
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        return alpha;
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        bandPaint.setColorFilter(colorFilter);
        edgePaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
