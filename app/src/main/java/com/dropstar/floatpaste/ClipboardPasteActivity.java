package com.dropstar.floatpaste;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.lzf.easyfloat.permission.PermissionUtils;
import com.dropstar.floatpaste.lib.AppLog;
import com.dropstar.floatpaste.lib.SharePasteHelper;

/**
 * 透明中转 Activity：由控制中心磁贴启动，在前台上下文中读取剪切板文字并贴成悬浮贴图，随后结束自己。
 * Android 12+ 限制后台应用读取剪切板，因此必须处于前台（有焦点的 Activity）才能读取；
 * 但磁贴拉起的活动在获得剪切板前台读取权限前有一小段窗口，读早了会返回空，所以需要多次重试。
 */
public class ClipboardPasteActivity extends Activity {

    private boolean finished = false;
    private int attempts = 0;
    private static final int MAX_ATTEMPTS = 12;
    private static final long RETRY_DELAY_MS = 250;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 透明主题，无内容视图
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!finished) {
            attemptPaste();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // 获得焦点后立刻尝试一次，通常此刻已可读取剪切板
        if (hasFocus && !finished) {
            attemptPaste();
        }
    }

    private void attemptPaste() {
        if (finished) return;
        attempts++;
        String text = readClipboardText();
        if (text != null && !text.trim().isEmpty()) {
            finished = true;
            AppLog.d("ClipboardPaste", "一键贴文读取成功 尝试次数=" + attempts + " 内容长度=" + text.length());
            doPaste(text);
            return;
        }
        if (attempts < MAX_ATTEMPTS) {
            new Handler(Looper.getMainLooper()).postDelayed(this::attemptPaste, RETRY_DELAY_MS);
        } else {
            finished = true;
            AppLog.d("ClipboardPaste", "一键贴文读取剪切板失败，重试耗尽 次数=" + attempts);
            Toast.makeText(this, getString(R.string.clipboardEmpty), Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void doPaste(String text) {
        if (PermissionUtils.checkPermission(this)) {
            AppLog.d("ClipboardPaste", "一键贴文 已有悬浮窗权限");
            SharePasteHelper.showFloatText(this, text);
            finish();
        } else {
            AppLog.d("ClipboardPaste", "一键贴文 无悬浮窗权限，请求中");
            PermissionUtils.requestPermission(this, granted -> {
                AppLog.d("ClipboardPaste", "一键贴文 权限结果 granted=" + granted);
                if (granted) {
                    SharePasteHelper.showFloatText(this, text);
                } else {
                    Toast.makeText(this, getString(R.string.needFloatingPermission), Toast.LENGTH_SHORT).show();
                }
                finish();
            });
        }
    }

    private String readClipboardText() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) {
                return null;
            }
            ClipData data = cm.getPrimaryClip();
            if (data == null || data.getItemCount() <= 0) {
                return null;
            }
            CharSequence seq = data.getItemAt(0).getText();
            return seq == null ? null : seq.toString();
        } catch (Exception e) {
            // Android 10+ 后台读取剪切板会抛异常，或尚未获得前台读取权限，稍后重试
            return null;
        }
    }
}
