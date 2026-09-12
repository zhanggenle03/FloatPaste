package com.dropstar.floatpaste.lib;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 历史贴图记录存储：
 * <ul>
 *   <li>元数据(JSON) 存 SharedPreferences，图片文件存 filesDir/history/（持久保存，不随 cache 清理）</li>
 *   <li>图片按内容 md5 命名去重，文字按内容去重；同一内容重复贴出时仅刷新时间并置顶（类似 Snipaste 行为）</li>
 *   <li>上限 {@link #MAX_ITEMS} 条，超出删除最旧的</li>
 * </ul>
 */
public class HistoryStore {

    private static final String TAG = "HistoryStore";
    private static final String PREF_NAME = "sticker_history";
    private static final String KEY_ITEMS = "items";
    private static final String DIR_NAME = "history";
    /** 历史记录上限：超出后自动删除最旧的（保留最近 50 条） */
    public static final int MAX_ITEMS = 50;

    public static final String TYPE_IMAGE = "image";
    public static final String TYPE_TEXT = "text";

    /** 一条历史记录 */
    public static class HistoryItem {
        public long id;      // 唯一 id（创建时时间戳）
        public String type;  // TYPE_IMAGE / TYPE_TEXT
        public String file;  // 图片文件名（仅图片类型）
        public String text;  // 文字内容（仅文字类型）
        public long time;    // 最近一次贴出时间（毫秒）

        /** 图片记录对应的持久化文件（不存在时为 null 调用方自行判断） */
        public File imageFile(Context context) {
            return new File(historyDir(context), file);
        }
    }

    private HistoryStore() { }

    private static File historyDir(Context context) {
        File dir = new File(context.getFilesDir(), DIR_NAME);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    // ===================== 写入 =====================

    /**
     * 记录一张图片贴图：按内容 md5 去重，重复则刷新时间置顶。
     * 源文件会被复制到持久目录（原文件多位于 cache，会被系统清理）。
     * 设置里关闭「历史记录」开关时不记录。
     */
    public static HistoryItem addImage(Context context, File srcFile) {
        if (!Settings.getHistoryEnabled(context)) return null;
        if (srcFile == null || !srcFile.exists()) return null;
        try {
            String md5 = md5(srcFile);
            String fileName = "img_" + md5 + ".jpg";
            File dst = new File(historyDir(context), fileName);
            if (!dst.exists()) {
                copyFile(srcFile, dst);
            }
            return upsert(context, TYPE_IMAGE, fileName, null);
        } catch (Exception e) {
            Log.e(TAG, "addImage failed", e);
            AppLog.e(TAG, "历史记录添加图片失败", e);
            return null;
        }
    }

    /** 记录一张 Bitmap 图片贴图（分享/重新贴出场景），压缩为 JPEG 后落盘。 */
    public static HistoryItem addImage(Context context, Bitmap bitmap) {
        if (!Settings.getHistoryEnabled(context)) return null;
        if (bitmap == null || bitmap.isRecycled()) return null;
        try {
            File tmp = File.createTempFile("hist_", ".jpg", context.getCacheDir());
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, fos);
            }
            return addImage(context, tmp);
        } catch (Exception e) {
            Log.e(TAG, "addImage(bitmap) failed", e);
            AppLog.e(TAG, "历史记录添加图片(bitmap)失败", e);
            return null;
        }
    }

    /** 记录一条文字贴图：按内容去重，重复则刷新时间置顶。 */
    public static HistoryItem addText(Context context, String text) {
        if (!Settings.getHistoryEnabled(context)) return null;
        if (text == null || text.trim().isEmpty()) return null;
        return upsert(context, TYPE_TEXT, null, text);
    }

    private static HistoryItem upsert(Context context, String type, String fileName, String text) {
        long now = System.currentTimeMillis();
        List<HistoryItem> items = load(context);
        // 内容去重：已有相同记录 → 刷新时间并移到最前
        for (HistoryItem it : items) {
            boolean same = TYPE_IMAGE.equals(type)
                    ? (TYPE_IMAGE.equals(it.type) && fileName.equals(it.file))
                    : (TYPE_TEXT.equals(it.type) && text.equals(it.text));
            if (same) {
                it.time = now;
                items.remove(it);
                items.add(0, it);
                persist(context, items);
                AppLog.d(TAG, "历史记录刷新置顶 type=" + type);
                return it;
            }
        }
        HistoryItem item = new HistoryItem();
        item.id = now;
        item.type = type;
        item.file = fileName;
        item.text = text;
        item.time = now;
        items.add(0, item);
        trim(context, items);
        persist(context, items);
        AppLog.d(TAG, "历史记录新增 type=" + type + " 当前条数=" + items.size() + "/" + MAX_ITEMS);
        return item;
    }

    // ===================== 读取 =====================

    /** 全部记录，最新在前。 */
    public static List<HistoryItem> getAll(Context context) {
        return load(context);
    }

    // ===================== 删除 =====================

    /** 删除一条记录（图片同时删除持久化文件）。 */
    public static void delete(Context context, HistoryItem item) {
        if (item == null) return;
        List<HistoryItem> items = load(context);
        boolean removed = items.removeIf(it -> it.id == item.id);
        if (removed) {
            persist(context, items);
            if (TYPE_IMAGE.equals(item.type)) {
                File f = item.imageFile(context);
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            AppLog.d(TAG, "历史记录删除 type=" + item.type);
        }
    }

    /** 清空全部历史（含图片文件）。 */
    public static void clear(Context context) {
        SharedPreferences.Editor editor = prefs(context).edit();
        editor.remove(KEY_ITEMS).apply();
        File dir = historyDir(context);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        AppLog.d(TAG, "历史记录清空");
    }

    // ===================== 内部实现 =====================

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    private static List<HistoryItem> load(Context context) {
        List<HistoryItem> list = new ArrayList<>();
        String json = prefs(context).getString(KEY_ITEMS, null);
        if (json == null || json.isEmpty()) return list;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                HistoryItem it = new HistoryItem();
                it.id = o.optLong("id");
                it.type = o.optString("type");
                it.file = o.optString("file", null);
                it.text = o.optString("text", null);
                it.time = o.optLong("time");
                if (it.type == null || (TYPE_IMAGE.equals(it.type) && it.file == null)) {
                    continue; // 脏数据跳过
                }
                list.add(it);
            }
        } catch (Exception e) {
            Log.e(TAG, "load failed", e);
            AppLog.e(TAG, "历史记录读取失败", e);
        }
        // 保险：按时间倒序（写入时已倒序，这里兜底）
        list.sort((a, b) -> Long.compare(b.time, a.time));
        return list;
    }

    private static void persist(Context context, List<HistoryItem> items) {
        try {
            JSONArray arr = new JSONArray();
            for (HistoryItem it : items) {
                JSONObject o = new JSONObject();
                o.put("id", it.id);
                o.put("type", it.type);
                if (it.file != null) o.put("file", it.file);
                if (it.text != null) o.put("text", it.text);
                o.put("time", it.time);
                arr.put(o);
            }
            prefs(context).edit().putString(KEY_ITEMS, arr.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "persist failed", e);
            AppLog.e(TAG, "历史记录保存失败", e);
        }
    }

    /** 超出上限时从末尾（最旧）开始删除记录及其图片文件。 */
    private static void trim(Context context, List<HistoryItem> items) {
        while (items.size() > MAX_ITEMS) {
            HistoryItem last = items.remove(items.size() - 1);
            AppLog.d(TAG, "历史记录超限，淘汰最旧 type=" + last.type);
            if (TYPE_IMAGE.equals(last.type)) {
                File f = last.imageFile(context);
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream is = new FileInputStream(src);
             FileOutputStream os = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) > 0) {
                os.write(buf, 0, len);
            }
        }
    }

    private static String md5(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        try (InputStream is = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) > 0) {
                md.update(buf, 0, len);
            }
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
