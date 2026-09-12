package com.to3g.snipasteandroid.fragment;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.to3g.snipasteandroid.ClipboardPasteTileService;
import com.to3g.snipasteandroid.LogViewerActivity;
import com.to3g.snipasteandroid.PermissionListActivity;
import com.to3g.snipasteandroid.R;
import com.to3g.snipasteandroid.base.BaseFragment;
import com.to3g.snipasteandroid.databinding.FragmentSettingsBinding;
import com.to3g.snipasteandroid.lib.AppLog;
import com.to3g.snipasteandroid.lib.DialogUtil;
import com.to3g.snipasteandroid.lib.Group;
import com.to3g.snipasteandroid.lib.ScreenUtils;
import com.to3g.snipasteandroid.lib.Settings;
import com.to3g.snipasteandroid.lib.annotation.Widget;
import com.to3g.snipasteandroid.lib.SharePasteHelper;

@Widget(group = Group.Other, name = "设置")
public class SettingsFragment extends BaseFragment {

    private FragmentSettingsBinding binding;
    private SharedPreferences tilePrefs;
    private View tileStatusRow;
    private TextView tileStatusText;
    private TextView logSummaryText;
    private SharedPreferences.OnSharedPreferenceChangeListener tilePrefListener;
    private Dialog guideDialog;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        tilePrefs = requireContext().getSharedPreferences(
                ClipboardPasteTileService.PREF_NAME, Context.MODE_PRIVATE);
        initGeneralSection();
        initGestureSection();
        initOtherSection();
        return binding.getRoot();
    }

    @Override
    public void onResume() {
        super.onResume();
        // 回到设置页时按磁贴真实状态刷新文字：手动移除/添加后也能即时反映
        refreshTileStatus();
        // 回到设置页时刷新错误日志条目（调试状态 + 崩溃提示）
        updateLogSummary();
    }

    @Override
    public void onStart() {
        super.onStart();
        // 监听本地存储变化（磁贴服务与界面同进程，写入会即时通知），实现状态实时刷新
        if (tilePrefs != null) {
            tilePrefListener = (sp, key) -> {
                if (ClipboardPasteTileService.KEY_TILE_ADDED.equals(key)) {
                    refreshTileStatus();
                }
            };
            tilePrefs.registerOnSharedPreferenceChangeListener(tilePrefListener);
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if (tilePrefs != null && tilePrefListener != null) {
            tilePrefs.unregisterOnSharedPreferenceChangeListener(tilePrefListener);
            tilePrefListener = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        dismissGuideDialog();
    }

    private void initGeneralSection() {
        addSectionTitle(binding.settingsGeneral, getString(R.string.settings_general));
        addTileStatusItem(binding.settingsGeneral);
        addHistorySwitchItem(binding.settingsGeneral);
    }

    /**
     * 「记录历史贴图」开关：关闭后贴图不再写入历史记录（已存在的历史不受影响，仍可查看/删除）。
     */
    private void addHistorySwitchItem(ViewGroup parent) {
        View row = LayoutInflater.from(requireContext()).inflate(R.layout.item_setting_switch, parent, false);
        ((TextView) row.findViewById(R.id.title)).setText(getString(R.string.setting_history_enabled));
        SwitchMaterial switchView = row.findViewById(R.id.switch_view);
        switchView.setChecked(Settings.getHistoryEnabled(requireContext()));
        switchView.setOnCheckedChangeListener((buttonView, isChecked) -> {
            AppLog.d("Settings", "history_enabled_change=" + isChecked);
            Settings.setHistoryEnabled(requireContext(), isChecked);
        });
        parent.addView(row);
    }

    /**
     * 收起形式：缩略图 / 贴边条。点击弹出单选（自绘弹窗），选择后写入 Settings，
     * 下次收起贴图时生效（已收起的贴图保持原把手，不强制重建）。
     */
    private void addCollapseModeItem(ViewGroup parent) {
        View row = LayoutInflater.from(requireContext()).inflate(R.layout.item_setting_chevron, parent, false);
        ((TextView) row.findViewById(R.id.title)).setText(getString(R.string.setting_collapse_mode));
        TextView detail = row.findViewById(R.id.detail);
        int mode = Settings.getCollapseMode(requireContext());
        detail.setText(mode == Settings.COLLAPSE_MODE_STRIP
                ? getString(R.string.collapse_mode_strip)
                : getString(R.string.collapse_mode_thumb));
        row.setOnClickListener(v -> {
            AppLog.d("Settings", "collapse_mode_click");
            int cur = Settings.getCollapseMode(requireContext());
            int checked = (cur == Settings.COLLAPSE_MODE_STRIP) ? 1 : 0;
            DialogUtil.showSingleChoice(requireContext(), getString(R.string.setting_collapse_mode),
                    new CharSequence[]{
                            getString(R.string.collapse_mode_thumb),
                            getString(R.string.collapse_mode_strip)
                    }, checked, (which, option) -> {
                        AppLog.d("Settings", "collapse_mode_select which=" + which);
                        Settings.setCollapseMode(requireContext(),
                                which == 1 ? Settings.COLLAPSE_MODE_STRIP : Settings.COLLAPSE_MODE_THUMB);
                        detail.setText(option);
                    });
        });
        parent.addView(row);
    }

    /**
     * 一键贴文：状态文字「已添加 / 未添加」。
     * 不再尝试调用 requestAddTileService（Android 限制：手动移除过的磁贴系统不再自动弹框、且静默丢弃请求），
     * 也不再跳转系统快捷设置面板（Android 对普通应用没有公开 API 能打开快捷设置/编辑界面，
     * "android.settings.QUICK_SETTINGS" 并非框架支持的 action，startActivity 会抛 ActivityNotFoundException）。
     * 改为「点击弹出应用内全屏半透明指引」，用图示 + 文字教用户手动添加，
     * 100% 兼容所有 ROM、无需悬浮窗权限。浮层不自动关闭，由用户点击任意位置手动关闭。
     * 磁贴真实添加/移除状态由 ClipboardPasteTileService 的 onTileAdded/onTileRemoved 写入本地存储并实时刷新。
     */
    private void addTileStatusItem(ViewGroup parent) {
        View row = LayoutInflater.from(requireContext()).inflate(R.layout.item_setting_chevron, parent, false);
        ((TextView) row.findViewById(R.id.title)).setText(getString(R.string.setting_tile_add));
        tileStatusText = row.findViewById(R.id.detail);
        tileStatusRow = row;
        refreshTileStatus();
        row.setOnClickListener(v -> showGuideDialog());
        parent.addView(row);
    }

    private void refreshTileStatus() {
        if (tileStatusText == null || tilePrefs == null) return;
        boolean added = tilePrefs.getBoolean(ClipboardPasteTileService.KEY_TILE_ADDED, false);
        tileStatusText.setText(added
                ? getString(R.string.tile_status_added)
                : getString(R.string.tile_status_not_added));
        tileStatusText.setTextColor(added
                ? Color.parseColor("#2E7D32")
                : Color.parseColor("#999999"));
    }

    /** 弹出应用内全屏半透明指引浮层，教用户如何手动把「一键贴文」磁贴拖入快捷设置。不自动关闭，需用户点击任意位置关闭。 */
    private void showGuideDialog() {
        if (guideDialog != null && guideDialog.isShowing()) return;
        View content = LayoutInflater.from(requireContext()).inflate(R.layout.overlay_tile_guide, null);
        content.setOnClickListener(v -> dismissGuideDialog());
        Dialog dialog = new Dialog(requireContext(), android.R.style.Theme_Translucent_NoTitleBar);
        dialog.setContentView(content);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawableResource(android.R.color.transparent);
        }
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnDismissListener(d -> guideDialog = null);
        guideDialog = dialog;
        dialog.show();
    }

    private void dismissGuideDialog() {
        if (guideDialog != null && guideDialog.isShowing()) {
            guideDialog.dismiss();
        }
        guideDialog = null;
    }

    /** 手势类型：缩放贴图 / 编辑贴图（供 addGestureActionItem 区分选项与取值）。 */
    private static final int GESTURE_ZOOM = 0;
    private static final int GESTURE_EDIT = 1;

    private void initGestureSection() {
        addSectionTitle(binding.settingsGesture, getString(R.string.settings_gesture));
        addGestureActionItem(binding.settingsGesture, R.string.setting_zoom_sticker, GESTURE_ZOOM);
        addGestureActionItem(binding.settingsGesture, R.string.setting_edit_sticker, GESTURE_EDIT);
        // 「收起形式」也属于贴图行为设置，放在手势设置（贴图设置）分区内
        addCollapseModeItem(binding.settingsGesture);
    }

    /**
     * 手势动作项（chevron 行）：右侧 detail 显示当前选中的动作名；点击弹自绘单选弹窗选择动作。
     * 可选项统一由 {@link #gestureOptions(int)} / {@link #gestureValues(int)} 给出（两者下标一一对应），
     * 以后再扩充动作只需同时往这两个数组里加一项。
     */
    private void addGestureActionItem(ViewGroup parent, int titleRes, int gestureType) {
        View row = LayoutInflater.from(requireContext()).inflate(R.layout.item_setting_chevron, parent, false);
        ((TextView) row.findViewById(R.id.title)).setText(titleRes);
        TextView detail = row.findViewById(R.id.detail);

        final CharSequence[] options = gestureOptions(gestureType);
        final int[] values = gestureValues(gestureType);
        detail.setText(options[Math.max(0, indexOf(values, gestureCurrentValue(gestureType)))]);

        row.setOnClickListener(v -> {
            AppLog.d("Settings", "gesture_action_click type=" + gestureType);
            DialogUtil.showSingleChoice(requireContext(), getString(titleRes), options,
                    Math.max(0, indexOf(values, gestureCurrentValue(gestureType))), (which, option) -> {
                        AppLog.d("Settings", "gesture_action_select type=" + gestureType + " which=" + which);
                        setGestureValue(gestureType, values[which]);
                        detail.setText(option);
                        if (gestureType == GESTURE_ZOOM) {
                            // 立即生效：已经在屏幕上的贴图马上切换右下角手柄的显隐
                            SharePasteHelper.refreshZoomAction();
                        }
                    });
        });
        parent.addView(row);
    }

    /** 手势可选动作的文案（下标与 {@link #gestureValues(int)} 一一对应） */
    private CharSequence[] gestureOptions(int gestureType) {
        if (gestureType == GESTURE_ZOOM) {
            return new CharSequence[]{
                    getString(R.string.zoom_action_handle),
                    getString(R.string.zoom_action_pinch),
                    getString(R.string.zoom_action_both)
            };
        }
        return new CharSequence[]{getString(R.string.edit_action_double_tap)};
    }

    /** 手势可选动作的取值（下标与 {@link #gestureOptions(int)} 一一对应） */
    private int[] gestureValues(int gestureType) {
        if (gestureType == GESTURE_ZOOM) {
            return new int[]{
                    Settings.ZOOM_ACTION_HANDLE,
                    Settings.ZOOM_ACTION_PINCH,
                    Settings.ZOOM_ACTION_BOTH
            };
        }
        return new int[]{Settings.EDIT_ACTION_DOUBLE_TAP};
    }

    private int gestureCurrentValue(int gestureType) {
        return (gestureType == GESTURE_ZOOM)
                ? Settings.getZoomAction(requireContext())
                : Settings.getEditAction(requireContext());
    }

    private void setGestureValue(int gestureType, int value) {
        if (gestureType == GESTURE_ZOOM) {
            Settings.setZoomAction(requireContext(), value);
        } else {
            Settings.setEditAction(requireContext(), value);
        }
    }

    private static int indexOf(int[] arr, int value) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == value) return i;
        }
        return -1;
    }

    /** 其他：错误日志（含调试模式开关）、权限清单，均以与上面一致的列表项形式呈现。 */
    private void initOtherSection() {
        addSectionTitle(binding.settingsOther, getString(R.string.settings_other));
        addLogEntry(binding.settingsOther);
        addPermissionEntry(binding.settingsOther);
    }

    /** 「错误日志」列表项：右侧开关=调试模式；点击标题/说明区域进入日志查看页。 */
    private void addLogEntry(ViewGroup parent) {
        View row = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_setting_switch_summary, parent, false);
        ((TextView) row.findViewById(R.id.title)).setText(R.string.log_entry_title);
        logSummaryText = row.findViewById(R.id.summary);
        SwitchMaterial switchView = row.findViewById(R.id.switch_view);
        switchView.setChecked(Settings.getDebugMode(requireContext()));
        switchView.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Settings.setDebugMode(requireContext(), isChecked);
            AppLog.d("Settings", "debug_mode_change=" + isChecked);
            updateLogSummary();
        });
        // 点击行（开关区域除外）进入错误日志页
        row.setOnClickListener(v -> {
            AppLog.d("Settings", "log_entry_click");
            startActivity(new Intent(requireContext(), LogViewerActivity.class));
        });
        updateLogSummary();
        parent.addView(row);
    }

    /** 刷新错误日志条目的副标题：调试状态 + 崩溃提示。 */
    private void updateLogSummary() {
        if (logSummaryText == null) return;
        boolean debug = Settings.getDebugMode(requireContext());
        String s = getString(debug ? R.string.log_summary_debug_on : R.string.log_summary_debug_off);
        if (AppLog.hasCrash()) {
            s += getString(R.string.log_summary_crash_suffix);
        }
        logSummaryText.setText(s);
        logSummaryText.setTextColor(AppLog.hasCrash()
                ? Color.parseColor("#D32F2F")
                : Color.parseColor("#999999"));
    }

    /** 「权限清单」列表项：点击进入权限清单页。 */
    private void addPermissionEntry(ViewGroup parent) {
        View row = LayoutInflater.from(requireContext()).inflate(R.layout.item_setting_chevron, parent, false);
        ((TextView) row.findViewById(R.id.title)).setText(getString(R.string.permission_list_button));
        row.setOnClickListener(v -> {
            AppLog.d("Settings", "permission_entry_click");
            startActivity(new Intent(requireContext(), PermissionListActivity.class));
        });
        parent.addView(row);
    }

    private void addSectionTitle(ViewGroup parent, String title) {
        TextView tv = new TextView(requireContext());
        tv.setText(title);
        tv.setTextSize(14);
        tv.setTextColor(Color.parseColor("#999999"));
        int padH = ScreenUtils.dp2px(requireContext(), 16);
        int padBottom = ScreenUtils.dp2px(requireContext(), 8);
        tv.setPadding(padH, padH, 0, padBottom);
        parent.addView(tv);
    }
}
