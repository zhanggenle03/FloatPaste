package com.dropstar.floatpaste;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.appbar.MaterialToolbar;
import com.dropstar.floatpaste.base.BaseFragmentActivity;
import com.dropstar.floatpaste.lib.AppLog;

/** 错误日志查看器：展示崩溃栈 + 运行日志，支持复制与清空。 */
public class LogViewerActivity extends BaseFragmentActivity {

    private TextView logText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log_viewer);

        MaterialToolbar topbar = findViewById(R.id.topbar);
        logText = findViewById(R.id.log_text);

        topbar.setNavigationIcon(R.drawable.ic_arrow_back);
        topbar.setNavigationOnClickListener(v -> finish());
        topbar.setTitle(getString(R.string.log_viewer_title));
        topbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_copy) {
                copyLog();
                return true;
            } else if (id == R.id.action_clear) {
                AppLog.clear();
                refresh();
                Toast.makeText(this, R.string.log_cleared, Toast.LENGTH_SHORT).show();
                return true;
            }
            return false;
        });

        refresh();
    }

    private void refresh() {
        logText.setText(AppLog.getAllLog());
    }

    private void copyLog() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("crash_log", AppLog.getAllLog()));
            Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show();
        }
    }
}
