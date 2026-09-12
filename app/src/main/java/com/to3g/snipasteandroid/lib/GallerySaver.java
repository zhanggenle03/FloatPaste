package com.to3g.snipasteandroid.lib;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 把贴图 / 历史记录导出到系统相册（Pictures/Snipaste）。
 * <p>
 * 分两条路径：
 * <ul>
 *   <li><b>Android 10 (API 29) 及以上</b>：走 {@link MediaStore} 的
 *       {@code RELATIVE_PATH + IS_PENDING} 分区存储写法，<b>无需任何权限</b>。</li>
 *   <li><b>Android 9 (API 28) 及以下</b>：公共目录属于共享存储，写入需要
 *       {@code WRITE_EXTERNAL_STORAGE}（Manifest 中以 {@code maxSdkVersion="28"} 声明）。
 *       写完后用 {@link MediaScannerConnection} 通知相册刷新。</li>
 * </ul>
 * 落盘内容有两种来源，通过内部 {@link ContentWriter} 统一：位图压缩（贴图导出）与
 * 文件拷贝（历史记录里的图片按原始字节导出，不重新编码）。<br>
 * 本类只负责「落盘」，不弹提示、不做权限申请，由调用方决定交互。
 */
public final class GallerySaver {

    /** 相册中的子目录名（Pictures/Snipaste） */
    private static final String ALBUM_NAME = "Snipaste";
    /** 文件名前缀 */
    private static final String FILE_PREFIX = "Snipaste_";
    private static final String MIME_PNG = "image/png";
    private static final String MIME_JPEG = "image/jpeg";
    private static final String MIME_WEBP = "image/webp";
    private static final String EXT_PNG = ".png";
    private static final String EXT_JPEG = ".jpg";
    private static final String EXT_WEBP = ".webp";
    private static final String EXT_JPEG_ALT = ".jpeg";

    private GallerySaver() {
    }

    /** 当前系统版本下，保存到相册是否需要存储权限（仅 Android 9 及以下需要） */
    public static boolean needsStoragePermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q;
    }

    /** 是否已获得存储权限；Android 10+ 恒为 true（分区存储下无需权限） */
    public static boolean hasStoragePermission(Context context) {
        if (!needsStoragePermission()) return true;
        return ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 生成默认文件名：Snipaste_20260912_182054.png */
    public static String newFileName() {
        return newFileName(EXT_PNG);
    }

    /** 生成指定扩展名的文件名：Snipaste_20260912_182054[ext] */
    public static String newFileName(String ext) {
        if (ext == null || ext.isEmpty()) ext = EXT_PNG;
        return FILE_PREFIX + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ext;
    }

    /**
     * 探测图片文件的真实编码格式，返回相册保存应使用的扩展名（{@code .png} / {@code .jpg} / {@code .webp}）。
     * <p>
     * 历史记录里的图片文件名统一是 {@code .jpg}，但内容可能来自 PNG 剪切板（只按内容 md5 命名，
     * 不做转码），所以不能用文件名推格式，必须读文件头。探测失败按 {@code .jpg} 处理。
     */
    public static String detectExtension(File srcFile) {
        if (srcFile == null || !srcFile.exists()) return EXT_JPEG;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(srcFile.getAbsolutePath(), opts);
            String mime = opts.outMimeType;
            if (mime != null) {
                String lower = mime.toLowerCase(Locale.US);
                if (lower.contains("png")) return EXT_PNG;
                if (lower.contains("webp")) return EXT_WEBP;
                if (lower.contains("jpeg") || lower.contains("jpg")) return EXT_JPEG;
            }
        } catch (Throwable t) {
            AppLog.e("GallerySaver", "detectExtension failed: " + t.getMessage());
        }
        return EXT_JPEG;
    }

    /**
     * 保存 Bitmap 到相册（PNG 无损）。
     *
     * @return true 表示已成功写入（相册可立即看到）
     */
    public static boolean save(Context context, Bitmap bitmap, String fileName) {
        if (context == null || bitmap == null || bitmap.isRecycled()) return false;
        return save(context, fileName, MIME_PNG,
                os -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, os));
    }

    /**
     * 保存 Bitmap 到相册，<b>按内容自动选编码</b>：不含透明通道走 JPEG（q92），含透明才用 PNG。
     *
     * <p>PNG 的 deflate 对屏幕级位图要 1~3 秒（贴图保存「太慢」的主因），JPEG 编码快一个量级、
     * 文件也更小，照片 / 截图类内容在 q92 下视觉无损。文字贴图等含透明的仍走 PNG 保住透明通道。
     * 文件名（含与格式匹配的扩展名）由本方法自动生成，调用方不用关心。
     *
     * @return true 表示已成功写入（相册可立即看到）
     */
    public static boolean saveBitmap(Context context, Bitmap bitmap) {
        if (context == null || bitmap == null || bitmap.isRecycled()) return false;
        boolean hasAlpha = true;
        try {
            hasAlpha = bitmap.hasAlpha();
        } catch (Exception ignored) {
        }
        if (!hasAlpha) {
            return save(context, newFileName(EXT_JPEG), MIME_JPEG,
                    os -> bitmap.compress(Bitmap.CompressFormat.JPEG, 92, os));
        }
        return save(context, newFileName(EXT_PNG), MIME_PNG,
                os -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, os));
    }

    /**
     * 保存一个已存在的图片文件到相册：<b>直接流拷贝，不重新编码</b>，保留原始画质。
     * 扩展名请用 {@link #detectExtension(File)} 探测后配合 {@link #newFileName(String)} 生成。
     *
     * @return true 表示已成功写入
     */
    public static boolean saveImageFile(Context context, File srcFile, String fileName) {
        if (context == null || srcFile == null || !srcFile.exists()) return false;
        return save(context, fileName, mimeTypeOf(fileName), os -> copy(srcFile, os));
    }

    /** 落盘内容写入器：把「位图压缩」和「文件拷贝」两种来源统一起来 */
    private interface ContentWriter {
        void write(OutputStream os) throws Exception;
    }

    private static boolean save(Context context, String fileName, String mimeType, ContentWriter writer) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return saveViaMediaStore(context, fileName, mimeType, writer);
        }
        return saveViaPublicDir(context, fileName, mimeType, writer);
    }

    /** Android 10+：MediaStore 插入 + IS_PENDING 标记，无需权限，同名自动追加序号 */
    private static boolean saveViaMediaStore(Context context, String fileName, String mimeType,
                                             ContentWriter writer) {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, mimeType);
        values.put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + File.separator + ALBUM_NAME);
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri uri = null;
        try {
            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return false;
            OutputStream os = resolver.openOutputStream(uri);
            if (os == null) {
                resolver.delete(uri, null, null);
                return false;
            }
            try {
                writer.write(os);
                os.flush();
            } finally {
                try {
                    os.close();
                } catch (Exception ignored) {
                }
            }
            // 写入完成后清除 pending 标记，相册才会展示
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            return true;
        } catch (Exception e) {
            AppLog.e("GallerySaver", "saveViaMediaStore failed: " + e.getMessage());
            if (uri != null) {
                try {
                    resolver.delete(uri, null, null);
                } catch (Exception ignored) {
                }
            }
            return false;
        }
    }

    /** Android 9 及以下：直接写公共 Pictures/Snipaste 目录，再通知媒体库扫描 */
    private static boolean saveViaPublicDir(Context context, String fileName, String mimeType,
                                            ContentWriter writer) {
        File target;
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES), ALBUM_NAME);
            if (!dir.exists() && !dir.mkdirs()) {
                AppLog.e("GallerySaver", "mkdirs failed: " + dir.getAbsolutePath());
                return false;
            }
            target = new File(dir, fileName);
        } catch (Exception e) {
            AppLog.e("GallerySaver", "saveViaPublicDir prepare failed", e);
            return false;
        }

        try (FileOutputStream fos = new FileOutputStream(target)) {
            writer.write(fos);
            fos.flush();
        } catch (Exception e) {
            AppLog.e("GallerySaver", "saveViaPublicDir write failed", e);
            return false;
        }

        // 通知媒体库刷新，否则相册 App 要等下次扫描才看得到
        try {
            MediaScannerConnection.scanFile(context,
                    new String[]{target.getAbsolutePath()},
                    new String[]{mimeType}, null);
        } catch (Exception e) {
            AppLog.e("GallerySaver", "scanFile failed: " + e.getMessage());
        }
        return true;
    }

    /** 由文件名推断 MIME；仅用于本类自己生成的 .png / .jpg / .webp */
    private static String mimeTypeOf(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.US);
        if (lower.endsWith(EXT_PNG)) return MIME_PNG;
        if (lower.endsWith(EXT_WEBP)) return MIME_WEBP;
        if (lower.endsWith(EXT_JPEG) || lower.endsWith(EXT_JPEG_ALT)) return MIME_JPEG;
        return MIME_PNG;
    }

    private static void copy(File src, OutputStream os) throws Exception {
        try (InputStream is = new FileInputStream(src)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) > 0) {
                os.write(buf, 0, len);
            }
        }
    }
}
