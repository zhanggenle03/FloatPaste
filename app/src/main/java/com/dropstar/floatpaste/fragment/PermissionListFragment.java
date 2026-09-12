package com.dropstar.floatpaste.fragment;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.Manifest;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.dropstar.floatpaste.R;
import com.dropstar.floatpaste.base.BaseFragment;
import com.dropstar.floatpaste.databinding.FragmentPermissionListBinding;
import com.dropstar.floatpaste.lib.AppLog;
import com.dropstar.floatpaste.lib.Group;
import com.dropstar.floatpaste.lib.annotation.Widget;

import java.util.Objects;

@Widget(group = Group.Other, name = "权限清单")
public class PermissionListFragment extends BaseFragment {

    private enum PermType { OVERLAY, CAMERA, STORAGE, CLIPBOARD }

    private FragmentPermissionListBinding binding;

    private static class PermissionItem {
        final String title;
        final String desc;
        final PermType type;

        PermissionItem(String title, String desc, PermType type) {
            this.title = title;
            this.desc = desc;
            this.type = type;
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, android.os.Bundle savedInstanceState) {
        binding = FragmentPermissionListBinding.inflate(inflater, container, false);
        initTopBar();
        initList();
        return binding.getRoot();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshStatuses();
    }

    private void initTopBar() {
        binding.topbar.setNavigationIcon(R.drawable.ic_arrow_back);
        binding.topbar.setNavigationOnClickListener(v -> popBackStack());
        binding.topbar.setTitle(getString(R.string.permission_list_title));
    }

    private void initList() {
        PermissionItem[] items = new PermissionItem[]{
                new PermissionItem(getString(R.string.perm_overlay_title), getString(R.string.perm_overlay_desc), PermType.OVERLAY),
                new PermissionItem(getString(R.string.perm_camera_title), getString(R.string.perm_camera_desc), PermType.CAMERA),
                new PermissionItem(getString(R.string.perm_storage_title), getString(R.string.perm_storage_desc), PermType.STORAGE),
                new PermissionItem(getString(R.string.perm_clipboard_title), getString(R.string.perm_clipboard_desc), PermType.CLIPBOARD),
        };
        for (PermissionItem item : items) {
            binding.permissionListContainer.addView(createRow(item));
        }
        AppLog.d("PermissionList", "onCreateView");
    }

    private View createRow(PermissionItem item) {
        View row = LayoutInflater.from(requireContext()).inflate(R.layout.item_permission, binding.permissionListContainer, false);
        ((TextView) row.findViewById(R.id.perm_title)).setText(item.title);
        ((TextView) row.findViewById(R.id.perm_desc)).setText(item.desc);
        TextView statusView = row.findViewById(R.id.perm_status);
        statusView.setTag(item.type);
        applyStatus(statusView, item.type);
        // 整行可点击打开系统设置；右侧「前往设置」文字按钮同样触发
        row.setOnClickListener(v -> openAppSettings());
        row.findViewById(R.id.perm_action).setOnClickListener(v -> openAppSettings());
        return row;
    }

    private void refreshStatuses() {
        for (int i = 0; i < binding.permissionListContainer.getChildCount(); i++) {
            View row = binding.permissionListContainer.getChildAt(i);
            TextView statusView = row.findViewById(R.id.perm_status);
            if (statusView != null && statusView.getTag() instanceof PermType) {
                applyStatus(statusView, (PermType) statusView.getTag());
            }
        }
    }

    private void applyStatus(TextView statusView, PermType type) {
        int textRes;
        int colorRes;
        if (type == PermType.CAMERA) {
            boolean granted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED;
            textRes = granted ? R.string.perm_status_granted : R.string.perm_status_denied;
            colorRes = granted ? R.color.perm_status_granted : R.color.perm_status_denied;
        } else if (type == PermType.OVERLAY) {
            boolean granted = Settings.canDrawOverlays(requireContext());
            textRes = granted ? R.string.perm_status_granted : R.string.perm_status_denied;
            colorRes = granted ? R.color.perm_status_granted : R.color.perm_status_denied;
        } else if (type == PermType.STORAGE) {
            // 保存贴图到相册：Android 10+ 分区存储无需权限，仅 Android 9 及以下需要
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                textRes = R.string.perm_status_not_required;
                colorRes = R.color.perm_status_not_required;
            } else {
                boolean granted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        == PackageManager.PERMISSION_GRANTED;
                textRes = granted ? R.string.perm_status_granted : R.string.perm_status_denied;
                colorRes = granted ? R.color.perm_status_granted : R.color.perm_status_denied;
            }
        } else {
            // 剪切板：系统隐私记录，无需授权
            textRes = R.string.perm_status_not_required;
            colorRes = R.color.perm_status_not_required;
        }
        statusView.setText(textRes);
        statusView.setTextColor(ContextCompat.getColor(requireContext(), colorRes));
    }

    private void openAppSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(getContext(), getString(R.string.perm_go_settings), Toast.LENGTH_SHORT).show();
        }
    }
}
