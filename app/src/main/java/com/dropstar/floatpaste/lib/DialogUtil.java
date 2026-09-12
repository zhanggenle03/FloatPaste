package com.dropstar.floatpaste.lib;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.lzf.easyfloat.permission.PermissionUtils;
import com.dropstar.floatpaste.R;

/**
 * 自绘弹窗工具：与系统 AlertDialog 主题完全解耦，规避 Material3 应用主题 + 部分 ROM 下
 * 按钮文字被动态主题覆盖不可见 / select_dialog inflate 失败等兼容问题。
 * 统一视觉：阴影描边圆角卡片 + 灰胶囊取消 + 实底胶囊确认（danger 红色 / 常规蓝色）。
 */
public class DialogUtil {

    /** 单选弹窗回调 */
    public interface OnChoiceListener {
        void onSelect(int index, CharSequence option);
    }

    private DialogUtil() { }

    /**
     * 确认弹窗：标题可选（null 不显示），消息必填。
     * danger=true 确认按钮红色实底（删除/清空），否则蓝色实底。
     * onPositive 为 null 时确认按钮仅关闭弹窗（用作「详情/关闭」）。
     */
    public static void showConfirm(Context context, CharSequence title, CharSequence message,
                                   CharSequence positiveText, boolean danger, Runnable onPositive) {
        View content = LayoutInflater.from(context).inflate(R.layout.dialog_confirm, null);
        TextView titleView = content.findViewById(R.id.dialogTitle);
        TextView messageView = content.findViewById(R.id.dialogMessage);
        TextView cancel = content.findViewById(R.id.dialogCancel);
        TextView confirm = content.findViewById(R.id.dialogConfirm);
        if (title != null && title.length() > 0) {
            titleView.setText(title);
            titleView.setVisibility(View.VISIBLE);
        }
        messageView.setText(message);
        confirm.setText(positiveText);
        if (danger) {
            confirm.setBackgroundResource(R.drawable.bg_btn_danger);
        }

        Dialog dialog = new Dialog(context, android.R.style.Theme_Translucent_NoTitleBar);
        dialog.setContentView(content);
        configWindow(context, dialog);
        dialog.setCanceledOnTouchOutside(true);
        cancel.setOnClickListener(v -> dialog.dismiss());
        confirm.setOnClickListener(v -> {
            dialog.dismiss();
            if (onPositive != null) onPositive.run();
        });
        dialog.show();
    }

    /**
     * 单选列表弹窗：选项行点击即选中关闭；当前项浅蓝高亮 + 右侧蓝色对勾。
     */
    public static void showSingleChoice(Context context, CharSequence title, CharSequence[] options,
                                        int checkedIndex, OnChoiceListener listener) {
        View content = LayoutInflater.from(context).inflate(R.layout.dialog_single_choice, null);
        TextView titleView = content.findViewById(R.id.dialogTitle);
        titleView.setText(title);
        Dialog dialog = new Dialog(context, android.R.style.Theme_Translucent_NoTitleBar);
        LinearLayout list = content.findViewById(R.id.listContainer);
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            View row = LayoutInflater.from(context)
                    .inflate(R.layout.item_dialog_option, list, false);
            TextView optionText = row.findViewById(R.id.optionText);
            ImageView check = row.findViewById(R.id.optionCheck);
            optionText.setText(options[i]);
            boolean selected = (i == checkedIndex);
            row.setSelected(selected);
            check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
            row.setOnClickListener(v -> {
                dialog.dismiss();
                if (listener != null) listener.onSelect(index, options[index]);
            });
            list.addView(row);
        }
        TextView cancel = content.findViewById(R.id.dialogCancel);

        dialog.setContentView(content);
        configWindow(context, dialog);
        dialog.setCanceledOnTouchOutside(true);
        cancel.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    /**
     * 悬浮窗权限引导弹窗（贴图/分享主流程高频弹窗）：
     * 提示文案固定为 floatingPermissionText，「去开启」后请求权限，授予成功才回调 onGranted。
     */
    public static void showPermissionDialog(Activity activity, Runnable onGranted) {
        showConfirm(activity, null, activity.getText(R.string.floatingPermissionText),
                activity.getText(R.string.toOpen), false, () ->
                        PermissionUtils.requestPermission(activity, granted -> {
                            AppLog.d("DialogUtil", "悬浮窗权限申请结果 granted=" + granted);
                            if (granted) {
                                onGranted.run();
                            } else {
                                Toast.makeText(activity,
                                        activity.getText(R.string.needFloatingPermission),
                                        Toast.LENGTH_SHORT).show();
                            }
                        }));
    }

    private static void configWindow(Context context, Dialog dialog) {
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout((int) (context.getResources().getDisplayMetrics().widthPixels * 0.8f),
                    WindowManager.LayoutParams.WRAP_CONTENT);
            window.setBackgroundDrawableResource(android.R.color.transparent);
        }
    }
}
