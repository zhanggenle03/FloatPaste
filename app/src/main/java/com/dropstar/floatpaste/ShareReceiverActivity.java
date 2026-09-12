package com.dropstar.floatpaste;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.lzf.easyfloat.permission.PermissionUtils;
import com.dropstar.floatpaste.lib.SharePasteHelper;

/**
 * 接收第三方 App 分享的文字/图片，直接贴在屏幕上
 * 透明 Activity，处理完成自动 finish
 */
public class ShareReceiverActivity extends Activity {

    private static final String TAG = "ShareReceiverActivity";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) {
            finish();
            return;
        }

        Log.d(TAG, "handleIntent: action=" + intent.getAction()
                + ", type=" + intent.getType());

        // 检查悬浮窗权限
        if (!PermissionUtils.checkPermission(this)) {
            // 无权限时引导用户
            Toast.makeText(this, "请先开启悬浮窗权限，再尝试分享贴图", Toast.LENGTH_LONG).show();
            // 打开主 Activity，让用户可以从界面引导中开启权限
            Intent mainIntent = new Intent(this, QDMainActivity.class);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(mainIntent);
            finish();
            return;
        }

        // 委托给 SharePasteHelper 处理
        SharePasteHelper.handleShareIntent(this, intent);

        finish();
    }
}
