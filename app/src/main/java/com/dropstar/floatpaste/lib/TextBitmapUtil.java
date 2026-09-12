package com.dropstar.floatpaste.lib;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

/**
 * 将文字渲染为 Bitmap，使文字贴图能复用图片贴图的缩放路径（缩放更流畅、无文字重排抖动）。
 */
public class TextBitmapUtil {

    /** 默认字号（sp），与界面文字保持一致 */
    private static final float TEXT_SIZE_SP = 16f;
    /** 背景内边距（dp） */
    private static final float PADDING_DP = 4f;
    /** 文字颜色 */
    private static final int TEXT_COLOR = Color.BLACK;
    /** 背景颜色，与原 text_paste 的 #e3e3e3 一致 */
    private static final int BG_COLOR = Color.parseColor("#e3e3e3");
    /** 最大换行宽度占屏幕宽度比例，避免单行过长 */
    private static final float MAX_WIDTH_RATIO = 0.6f;

    /**
     * 根据文字内容生成一张紧贴文字尺寸的 Bitmap（含背景与内边距）。
     *
     * @param context 上下文，用于获取屏幕密度与宽度
     * @param text    文字内容
     * @return 文字位图，尺寸为内容所需大小（不会过大也不会留空白）
     */
    public static Bitmap create(Context context, String text) {
        if (text == null) {
            text = "";
        }
        float density = context.getResources().getDisplayMetrics().density;
        float textSizePx = TEXT_SIZE_SP * density;
        int paddingPx = (int) (PADDING_DP * density);

        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(TEXT_COLOR);
        paint.setTextSize(textSizePx);
        paint.setTypeface(Typeface.DEFAULT);

        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        int maxWidth = (int) (screenWidth * MAX_WIDTH_RATIO);

        CharSequence source = text;
        StaticLayout layout;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            layout = StaticLayout.Builder.obtain(source, 0, source.length(), paint, maxWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1.0f)
                    .setIncludePad(true)
                    .build();
        } else {
            layout = new StaticLayout(source, paint, maxWidth,
                    Layout.Alignment.ALIGN_NORMAL, 1.0f, 0f, true);
        }

        int textWidth = layout.getWidth();
        int textHeight = layout.getHeight();
        int bmpWidth = textWidth + paddingPx * 2;
        int bmpHeight = textHeight + paddingPx * 2;

        Bitmap bitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(BG_COLOR);
        canvas.translate(paddingPx, paddingPx);
        layout.draw(canvas);
        return bitmap;
    }
}
