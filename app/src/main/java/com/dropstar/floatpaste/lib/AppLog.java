package com.dropstar.floatpaste.lib;

import android.content.Context;
import android.os.Build;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 轻量全局日志：捕获未处理崩溃 + 记录运行日志，供「错误日志」页面查看/复制。
 * 仅当设置里开启「调试模式」时才会记录（默认关闭，避免日常无谓 IO）。
 * 不依赖任何第三方库，仅写应用私有 files/logs 目录（无需存储权限）。
 */
public class AppLog {
    private static final int MAX_RUNTIME_LINES = 2000;
    private static final long MAX_RUNTIME_FILE_BYTES = 1024 * 1024; // 1MB
    private static final Object LOCK = new Object();

    private static Context appContext;
    private static File logDir;
    private static final List<String> runtimeBuffer = new ArrayList<>();
    private static Thread.UncaughtExceptionHandler defaultHandler;
    private static boolean initialized = false;

    public static void init(Context context) {
        if (initialized) return;
        initialized = true;
        appContext = context.getApplicationContext();
        logDir = new File(appContext.getFilesDir(), "logs");
        if (!logDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            logDir.mkdirs();
        }

        if (!Settings.getDebugMode(appContext)) return;

        d("AppLog", "===== App 启动 =====");
        d("AppLog", "version: " + versionInfo(appContext));
        d("AppLog", "device: " + Build.MANUFACTURER + " " + Build.MODEL + " (SDK " + Build.VERSION.SDK_INT + ")");

        defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            writeCrash(thread, ex);
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, ex);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(1);
            }
        });
    }

    /** 调试模式是否开启（决定是否记录日志） */
    public static boolean isDebugEnabled() {
        return appContext != null && Settings.getDebugMode(appContext);
    }

    private static String versionInfo(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName
                    + "(" + context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode + ")";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** 记录一条运行日志（含时间戳），同时写入运行日志文件。 */
    public static void d(String tag, String msg) {
        if (!isDebugEnabled()) return;
        write("D", tag, msg);
    }

    /** 记录一条错误日志（含堆栈摘要）。 */
    public static void e(String tag, String msg) {
        if (!isDebugEnabled()) return;
        write("E", tag, msg);
    }

    /** 记录一条异常日志：完整堆栈（用于排查任何异常）。 */
    public static void e(String tag, String msg, Throwable throwable) {
        if (!isDebugEnabled()) return;
        StringBuilder sb = new StringBuilder(msg);
        if (throwable != null) {
            StringWriter sw = new StringWriter();
            throwable.printStackTrace(new PrintWriter(sw));
            sb.append("\n").append(sw);
        }
        write("E", tag, sb.toString());
    }

    private static void write(String level, String tag, String msg) {
        String line = ts() + " [" + level + "][" + tag + "] " + msg;
        synchronized (LOCK) {
            runtimeBuffer.add(line);
            if (runtimeBuffer.size() > MAX_RUNTIME_LINES) {
                runtimeBuffer.remove(0);
            }
        }
        appendRuntimeFile(line);
    }

    private static void appendRuntimeFile(String line) {
        if (logDir == null) return;
        File f = new File(logDir, "runtime.log");
        try (FileOutputStream fos = new FileOutputStream(f, true)) {
            fos.write((line + "\n").getBytes("UTF-8"));
        } catch (IOException ignored) {
            // 日志写入失败不应影响主流程
        }
        if (f.length() > MAX_RUNTIME_FILE_BYTES) {
            trimRuntimeFile(f);
        }
    }

    private static void trimRuntimeFile(File f) {
        try {
            List<String> lines = readLines(f);
            if (lines.size() > MAX_RUNTIME_LINES) {
                rewrite(f, lines.subList(lines.size() - MAX_RUNTIME_LINES, lines.size()));
            }
        } catch (IOException ignored) {
        }
    }

    private static void writeCrash(Thread thread, Throwable ex) {
        if (logDir == null) return;
        String time = ts();
        File crashFile = new File(logDir, "crash_" + time.replaceAll("[:\\s]", "_") + ".log");
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("===== 崩溃 " + time + " =====");
        pw.println("thread: " + (thread != null ? thread.getName() : "null"));
        pw.println("device: " + Build.MANUFACTURER + " " + Build.MODEL + " SDK " + Build.VERSION.SDK_INT);
        ex.printStackTrace(pw);
        Throwable cause = ex.getCause();
        while (cause != null) {
            pw.println("\n----- Caused by -----");
            cause.printStackTrace(pw);
            cause = cause.getCause();
        }
        pw.flush();
        try (FileOutputStream fos = new FileOutputStream(crashFile)) {
            fos.write(sw.toString().getBytes("UTF-8"));
        } catch (IOException ignored) {
        }
        d("AppLog", "捕获到崩溃，已写入 " + crashFile.getName());
    }

    public static boolean hasCrash() {
        File[] files = listCrashFiles();
        return files != null && files.length > 0;
    }

    public static String getCrashLog() {
        File[] files = listCrashFiles();
        if (files == null || files.length == 0) return "（暂无崩溃记录）";
        try {
            return readFile(files[files.length - 1]);
        } catch (IOException e) {
            return "（读取崩溃日志失败：" + e.getMessage() + "）";
        }
    }

    public static String getRuntimeLog() {
        synchronized (LOCK) {
            return TextUtils.join("\n", runtimeBuffer);
        }
    }

    /** 拼装「错误日志」页面内容：崩溃日志 + 最近运行日志。调试模式关闭时给出提示。 */
    public static String getAllLog() {
        if (!isDebugEnabled()) {
            return "（调试模式未开启，未记录日志）\n请在 设置 → 其他 → 调试模式 开启后，复现问题再回来查看。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("========== 崩溃日志 ==========\n");
        sb.append(getCrashLog());
        sb.append("\n\n========== 最近运行日志 ==========\n");
        sb.append(getRuntimeLog());
        if (sb.length() == 0) sb.append("（无日志）");
        return sb.toString();
    }

    public static void clear() {
        if (logDir != null) {
            File[] files = logDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
        synchronized (LOCK) {
            runtimeBuffer.clear();
        }
    }

    private static File[] listCrashFiles() {
        if (logDir == null) return null;
        File[] files = logDir.listFiles((dir, name) -> name.startsWith("crash_"));
        if (files == null) return null;
        List<File> list = new ArrayList<>();
        Collections.addAll(list, files);
        list.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        return list.toArray(new File[0]);
    }

    private static List<String> readLines(File f) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            String l;
            while ((l = br.readLine()) != null) lines.add(l);
        }
        return lines;
    }

    private static String readFile(File f) throws IOException {
        return TextUtils.join("\n", readLines(f));
    }

    private static void rewrite(File f, List<String> lines) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            for (String l : lines) fos.write((l + "\n").getBytes("UTF-8"));
        }
    }

    private static String ts() {
        return new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
    }
}
