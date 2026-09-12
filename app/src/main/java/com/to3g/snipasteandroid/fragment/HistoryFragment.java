package com.to3g.snipasteandroid.fragment;

import android.Manifest;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.to3g.snipasteandroid.QDApplication;
import com.to3g.snipasteandroid.R;
import com.to3g.snipasteandroid.base.BaseFragment;
import com.to3g.snipasteandroid.base.BaseRecyclerAdapter;
import com.to3g.snipasteandroid.base.RecyclerViewHolder;
import com.to3g.snipasteandroid.databinding.FragmentHistoryBinding;
import com.to3g.snipasteandroid.lib.AppLog;
import com.to3g.snipasteandroid.lib.DialogUtil;
import com.to3g.snipasteandroid.lib.GallerySaver;
import com.to3g.snipasteandroid.lib.Group;
import com.to3g.snipasteandroid.lib.HistoryStore;
import com.to3g.snipasteandroid.lib.ScreenUtils;
import com.to3g.snipasteandroid.lib.SharePasteHelper;
import com.to3g.snipasteandroid.lib.TextBitmapUtil;
import com.to3g.snipasteandroid.lib.annotation.Widget;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 历史贴图记录页：展示历史上贴过的图片与文字（持久保存）。
 * 支持按类型筛选（全部/图片/文字），以及长按卡片进入批量选择后批量删除。
 */
@Widget(group = Group.Other, name = "历史记录")
public class HistoryFragment extends BaseFragment {

    private static final String TAG = "HistoryFragment";
    private static final int FILTER_ALL = 0;
    private static final int FILTER_IMAGE = 1;
    private static final int FILTER_TEXT = 2;
    private static final SimpleDateFormat TIME_FMT =
            new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
    private static final int COLOR_SELECTED_BG = 0xFFEBF9FF;
    private static final int COLOR_SELECTED_STROKE = Color.parseColor("#00A8E1");
    /** 保存到相册所需的存储权限请求码（仅 Android 9 及以下会用到） */
    private static final int REQ_SAVE_STORAGE = 0x5A1E;

    /** 主线程 Handler：保存是 IO，Toast 需回主线程弹出 */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** 因缺少存储权限被中断、待授权后自动重试保存的记录 */
    private HistoryStore.HistoryItem pendingSaveItem;
    /** 历史记录保存是否进行中：防止连点排队多次落盘 */
    private boolean savingHistory = false;

    private FragmentHistoryBinding binding;
    private final List<HistoryStore.HistoryItem> allItems = new ArrayList<>();
    private final List<HistoryStore.HistoryItem> displayItems = new ArrayList<>();
    private final Set<Long> selectedIds = new HashSet<>();
    private int filterType = FILTER_ALL;
    private boolean selectingMode = false;
    private BaseRecyclerAdapter<HistoryStore.HistoryItem> adapter;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentHistoryBinding.inflate(inflater, container, false);
        initBottomBar();
        initFilterBar();
        setupRecycler();
        refresh();
        return binding.getRoot();
    }

    @Override
    public void onResume() {
        super.onResume();
        // 每次切到本 Tab / 从其他页面回来都刷新（贴图历史可能刚新增）
        refresh();
    }

    /** 底部操作栏（仅选择模式显示）：取消 / 清空 / 删除 */
    private void initBottomBar() {
        binding.bottomCancel.setOnClickListener(v -> exitSelectMode());
        binding.bottomClear.setOnClickListener(v -> {
            AppLog.d(TAG, "clear_click");
            confirmClear();
        });
        binding.bottomDelete.setOnClickListener(v -> confirmBatchDelete());
    }

    private void initFilterBar() {
        binding.filterAll.setOnClickListener(v -> setFilter(FILTER_ALL));
        binding.filterImage.setOnClickListener(v -> setFilter(FILTER_IMAGE));
        binding.filterText.setOnClickListener(v -> setFilter(FILTER_TEXT));
        updateFilterChips();
    }

    private void setFilter(int type) {
        if (filterType == type) return;
        filterType = type;
        updateFilterChips();
        applyFilter();
    }

    private void updateFilterChips() {
        boolean all = filterType == FILTER_ALL;
        boolean img = filterType == FILTER_IMAGE;
        boolean txt = filterType == FILTER_TEXT;
        bindFilterChip(binding.filterAll, all);
        bindFilterChip(binding.filterImage, img);
        bindFilterChip(binding.filterText, txt);
    }

    private void bindFilterChip(TextView chip, boolean selected) {
        if (selected) {
            chip.setBackgroundResource(R.drawable.bg_btn_primary);
            chip.setTextColor(Color.WHITE);
        } else {
            chip.setBackgroundResource(R.drawable.bg_btn_action_gray);
            chip.setTextColor(0xFF666666);
        }
    }

    private void setupRecycler() {
        binding.recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new BaseRecyclerAdapter<HistoryStore.HistoryItem>(requireContext(), displayItems) {
            @Override
            public int getItemLayoutId(int viewType) {
                return R.layout.item_history_row;
            }

            @Override
            public void bindData(RecyclerViewHolder holder, int position, HistoryStore.HistoryItem item) {
                bindItem(holder, item);
            }
        };
        adapter.setOnItemClickListener((view, pos) -> {
            if (selectingMode) {
                toggleSelect(displayItems.get(pos));
            }
        });
        // 长按任意记录进入选择模式（长按的记录自动选中）
        adapter.setOnItemLongClickListener((view, pos) -> {
            if (!selectingMode) {
                enterSelectMode(displayItems.get(pos));
            }
        });
        binding.recyclerView.setAdapter(adapter);
    }

    private void bindItem(RecyclerViewHolder holder, HistoryStore.HistoryItem item) {
        boolean isImage = HistoryStore.TYPE_IMAGE.equals(item.type);
        // 标题 / 时间
        holder.setText(R.id.itemTitle, getString(isImage
                ? R.string.history_image_item : R.string.history_text_item));
        holder.setText(R.id.itemTime, TIME_FMT.format(new Date(item.time)));

        // 缩略图：图片贴图显示图片，文字贴图渲染文字缩略图
        ImageView iv = holder.getImageView(R.id.itemImage);
        if (isImage) {
            File f = item.imageFile(requireContext());
            if (f.exists()) {
                iv.setImageBitmap(decodeSampledBitmap(f.getAbsolutePath(), 400, 400));
            } else {
                iv.setImageResource(R.drawable.img_placeholder);
            }
        } else {
            iv.setImageBitmap(renderTextThumb(requireContext(), item.text));
        }

        // 批量选择模式下：隐藏操作按钮，卡片整体可点；显示选中态描边
        int opsVisibility = selectingMode ? View.GONE : View.VISIBLE;
        holder.getView(R.id.btnDetail).setVisibility(opsVisibility);
        holder.getView(R.id.btnRepaste).setVisibility(opsVisibility);
        holder.getView(R.id.btnSave).setVisibility(opsVisibility);
        holder.getView(R.id.btnDelete).setVisibility(opsVisibility);
        if (selectingMode) {
            holder.getView(R.id.btnDetail).setOnClickListener(null);
            holder.getView(R.id.btnRepaste).setOnClickListener(null);
            holder.getView(R.id.btnSave).setOnClickListener(null);
            holder.getView(R.id.btnDelete).setOnClickListener(null);
        } else {
            holder.getView(R.id.btnDetail).setOnClickListener(v -> showDetail(item));
            holder.getView(R.id.btnRepaste).setOnClickListener(v -> rePaste(item));
            holder.getView(R.id.btnSave).setOnClickListener(v -> saveToGallery(item));
            holder.getView(R.id.btnDelete).setOnClickListener(v -> confirmDelete(item));
        }

        // 卡片选中态
        MaterialCardView card = (MaterialCardView) holder.itemView;
        if (selectingMode && selectedIds.contains(item.id)) {
            card.setCardBackgroundColor(COLOR_SELECTED_BG);
            card.setStrokeColor(COLOR_SELECTED_STROKE);
            card.setStrokeWidth(ScreenUtils.dp2px(requireContext(), 2));
        } else {
            card.setCardBackgroundColor(Color.WHITE);
            card.setStrokeColor(Color.TRANSPARENT);
            card.setStrokeWidth(0);
        }
    }

    // ===================== 筛选 =====================

    private void applyFilter() {
        displayItems.clear();
        for (HistoryStore.HistoryItem it : allItems) {
            boolean match = filterType == FILTER_ALL
                    || (filterType == FILTER_IMAGE && HistoryStore.TYPE_IMAGE.equals(it.type))
                    || (filterType == FILTER_TEXT && HistoryStore.TYPE_TEXT.equals(it.type));
            if (match) {
                displayItems.add(it);
            }
        }
        adapter.notifyDataSetChanged();
        updateEmptyState();
    }

    private void updateEmptyState() {
        if (binding == null || adapter == null) return;
        boolean empty = displayItems.isEmpty();
        binding.emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        // 底部操作栏仅在「选择模式且有记录」时显示
        binding.bottomBar.setVisibility((!empty && selectingMode) ? View.VISIBLE : View.GONE);
    }

    // ===================== 批量选择 =====================

    /** 长按记录进入选择模式，该记录自动选中 */
    private void enterSelectMode(HistoryStore.HistoryItem first) {
        selectingMode = true;
        selectedIds.clear();
        if (first != null) {
            selectedIds.add(first.id);
        }
        updateBatchUI();
        adapter.notifyDataSetChanged();
    }

    private void exitSelectMode() {
        selectingMode = false;
        selectedIds.clear();
        updateBatchUI();
        adapter.notifyDataSetChanged();
    }

    private void toggleSelect(HistoryStore.HistoryItem item) {
        if (selectedIds.contains(item.id)) {
            selectedIds.remove(item.id);
        } else {
            selectedIds.add(item.id);
        }
        updateSelectionCount();
        adapter.notifyDataSetChanged();
    }

    /** 切换选择模式：显示/隐藏底部操作栏与计数 */
    private void updateBatchUI() {
        binding.bottomBar.setVisibility(selectingMode ? View.VISIBLE : View.GONE);
        if (selectingMode) {
            updateSelectionCount();
        }
    }

    private void updateSelectionCount() {
        int count = selectedIds.size();
        // 操作栏中间显示已选数 + 当前列表总数
        binding.bottomCount.setText(getString(R.string.history_selected_of_total,
                count, displayItems.size()));
        // 无选中时删除按钮置灰不可点
        boolean hasSelection = !selectedIds.isEmpty();
        binding.bottomDelete.setEnabled(hasSelection);
        if (hasSelection) {
            binding.bottomDelete.setBackgroundResource(R.drawable.bg_btn_danger);
            binding.bottomDelete.setTextColor(Color.WHITE);
        } else {
            binding.bottomDelete.setBackgroundResource(R.drawable.bg_btn_action_gray);
            binding.bottomDelete.setTextColor(0xFF999999);
        }
    }

    private void confirmBatchDelete() {
        if (selectedIds.isEmpty()) return;
        int count = selectedIds.size();
        DialogUtil.showConfirm(requireContext(), null,
                getString(R.string.history_batch_delete_confirm, count),
                getString(R.string.history_batch_delete), true, () -> {
                    // 按当前显示列表删除（含选中项）
                    List<HistoryStore.HistoryItem> toDelete = new ArrayList<>();
                    for (HistoryStore.HistoryItem it : displayItems) {
                        if (selectedIds.contains(it.id)) {
                            toDelete.add(it);
                        }
                    }
                    for (HistoryStore.HistoryItem it : toDelete) {
                        HistoryStore.delete(requireContext(), it);
                    }
                    Toast.makeText(requireContext(),
                            getString(R.string.history_batch_deleted, toDelete.size()),
                            Toast.LENGTH_SHORT).show();
                    selectingMode = false;
                    selectedIds.clear();
                    updateBatchUI();
                    refresh();
                });
    }

    // ===================== 单项操作 =====================

    /** 查看详情：图片全屏大图，文字弹全文 */
    private void showDetail(HistoryStore.HistoryItem item) {
        if (HistoryStore.TYPE_IMAGE.equals(item.type)) {
            File f = item.imageFile(requireContext());
            if (!f.exists()) {
                Toast.makeText(requireContext(), R.string.file_access_failed, Toast.LENGTH_SHORT).show();
                return;
            }
            Bitmap bitmap = decodeSampledBitmap(f.getAbsolutePath(), 1600, 1600);
            if (bitmap == null) {
                Toast.makeText(requireContext(), R.string.file_access_failed, Toast.LENGTH_SHORT).show();
                return;
            }
            ImageView iv = new ImageView(requireContext());
            iv.setImageBitmap(bitmap);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setBackgroundColor(0xE6000000);
            Dialog dialog = new Dialog(requireContext(), android.R.style.Theme_Translucent_NoTitleBar);
            dialog.setContentView(iv, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            dialog.setCanceledOnTouchOutside(true);
            iv.setOnClickListener(v -> dialog.dismiss());
            dialog.show();
        } else {
            DialogUtil.showConfirm(requireContext(), TIME_FMT.format(new Date(item.time)),
                    item.text != null ? item.text : "",
                    getString(R.string.history_close), false, null);
        }
    }

    /** 重新贴出：图片重新贴浮窗（权限检查内置），文字走 floatText（含权限检查） */
    private void rePaste(HistoryStore.HistoryItem item) {
        if (HistoryStore.TYPE_IMAGE.equals(item.type)) {
            File f = item.imageFile(requireContext());
            if (!f.exists()) {
                Toast.makeText(requireContext(), R.string.file_access_failed, Toast.LENGTH_SHORT).show();
                return;
            }
            SharePasteHelper.pasteImageFromFile(requireActivity(), f.getAbsolutePath());
        } else if (item.text != null && !item.text.trim().isEmpty()) {
            SharePasteHelper.floatText(requireActivity(), item.text);
        }
    }

    // ===================== 保存到相册 =====================

    /**
     * 保存这条记录到系统相册（Pictures/Snipaste）。
     * 图片记录按原始字节拷贝（不重新编码，保留画质）；文字记录先渲染成图片再存。
     * Android 9 及以下写公共目录需要存储权限，未授权时先申请，授权通过后自动续跑。
     */
    private void saveToGallery(HistoryStore.HistoryItem item) {
        if (item == null || !isAdded()) return;
        // 防重入：上一次保存还没结束就忽略这次点击（避免连点排队多次落盘）
        if (savingHistory) return;
        if (GallerySaver.needsStoragePermission() && !GallerySaver.hasStoragePermission(requireContext())) {
            pendingSaveItem = item;
            Toast.makeText(requireContext(), R.string.save_sticker_need_permission, Toast.LENGTH_LONG).show();
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_SAVE_STORAGE);
            return;
        }
        doSaveToGallery(item);
    }

    private void doSaveToGallery(HistoryStore.HistoryItem item) {
        if (item == null || !isAdded()) return;
        // 用 ApplicationContext：Fragment 已 detach 时 requireContext() 会抛异常，
        // 而 Toast / 落盘都不需要 Activity，用 Application 上下文最稳。
        Context appCtx = QDApplication.getContext();
        final Context appContext = appCtx != null ? appCtx : requireContext().getApplicationContext();
        final boolean isImage = HistoryStore.TYPE_IMAGE.equals(item.type);

        File srcFile = null;
        Bitmap textBitmap = null;
        String fileName;
        if (isImage) {
            srcFile = item.imageFile(appContext);
            if (!srcFile.exists()) {
                Toast.makeText(appContext, R.string.file_access_failed, Toast.LENGTH_SHORT).show();
                return;
            }
            // 历史图片按内容 md5 命名且统一挂 .jpg 后缀，内容却可能是 PNG：按真实格式决定扩展名
            fileName = GallerySaver.newFileName(GallerySaver.detectExtension(srcFile));
        } else {
            if (item.text == null || item.text.trim().isEmpty()) return;
            // 文字记录没有源文件，渲染成与文字贴图同款外观的位图（#e3e3e3 底 + 黑字）
            textBitmap = TextBitmapUtil.create(appContext, item.text);
            fileName = GallerySaver.newFileName();
        }

        final File src = srcFile;
        final Bitmap bitmap = textBitmap;
        final String name = fileName;

        // ① 立即反馈：落盘可能耗时（大图 IO / 文字渲染出的长图），先提示再干活，
        //    否则用户点完看不到任何反应。Toast 用 ApplicationContext，Fragment 销毁也不会崩。
        savingHistory = true;
        Toast.makeText(appContext, R.string.save_sticker_saving, Toast.LENGTH_SHORT).show();

        // ② 落盘是 IO（大图可能耗时），放后台线程；整体包 try/catch ——
        //    异常时线程静默死掉就变成「完全无反馈」，这里兜底成「保存失败」。
        new Thread(() -> {
            boolean ok = false;
            try {
                ok = isImage
                        ? GallerySaver.saveImageFile(appContext, src, name)
                        : GallerySaver.saveBitmap(appContext, bitmap); // 按内容自动选 JPEG/PNG
                AppLog.d(TAG, "保存历史记录到相册 type=" + item.type + " 成功=" + ok
                        + (isImage ? " 文件=" + name : ""));
            } catch (Throwable t) {
                AppLog.e(TAG, "保存历史记录到相册异常 type=" + item.type, t);
                ok = false;
            }
            final boolean result = ok;
            mainHandler.post(() -> {
                savingHistory = false;
                Toast.makeText(appContext,
                        result ? R.string.save_sticker_success : R.string.save_sticker_failed,
                        Toast.LENGTH_SHORT).show();
            });
        }, "history-save").start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_SAVE_STORAGE) return;
        HistoryStore.HistoryItem item = pendingSaveItem;
        pendingSaveItem = null;
        // 授权通过则自动续跑这次保存；被拒绝就到此为止（下次点击会再次申请）
        if (item != null && grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            doSaveToGallery(item);
        }
    }

    private void confirmDelete(HistoryStore.HistoryItem item) {
        DialogUtil.showConfirm(requireContext(), null, getString(R.string.history_delete_confirm),
                getString(R.string.history_delete), true, () -> {
                    HistoryStore.delete(requireContext(), item);
                    Toast.makeText(requireContext(), R.string.history_deleted, Toast.LENGTH_SHORT).show();
                    refresh();
                });
    }

    private void confirmClear() {
        DialogUtil.showConfirm(requireContext(), getString(R.string.history_clear_confirm_title),
                getString(R.string.history_clear_confirm_message),
                getString(R.string.history_clear), true, () -> {
                    HistoryStore.clear(requireContext());
                    Toast.makeText(requireContext(), R.string.history_cleared, Toast.LENGTH_SHORT).show();
                    refresh();
                });
    }

    private void refresh() {
        if (binding == null || adapter == null) return;
        allItems.clear();
        allItems.addAll(HistoryStore.getAll(requireContext()));
        // 筛选行右侧显示：已有记录数 / 上限
        binding.countHint.setText(getString(R.string.history_count_hint,
                allItems.size(), HistoryStore.MAX_ITEMS));
        // 批量模式下删除后若退出，保持筛选
        applyFilter();
    }

    /** 按目标尺寸等比采样解码，避免大图 OOM */
    private static Bitmap decodeSampledBitmap(String path, int reqW, int reqH) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, opts);
        int sample = 1;
        while (opts.outWidth / (sample * 2) >= reqW && opts.outHeight / (sample * 2) >= reqH) {
            sample *= 2;
        }
        opts.inJustDecodeBounds = false;
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(path, opts);
    }

    /** 把文字渲染成方形缩略图（模拟文字贴图外观），超出部分裁剪 */
    private static Bitmap renderTextThumb(android.content.Context context, String text) {
        int px = ScreenUtils.dp2px(context, 48);
        Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFF333333);
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9,
                context.getResources().getDisplayMetrics()));
        int pad = px / 8;
        String content = (text == null || text.isEmpty()) ? " " : text;
        StaticLayout layout = new StaticLayout(content, paint, px - pad * 2,
                Layout.Alignment.ALIGN_NORMAL, 1.1f, 0f, false);
        canvas.save();
        canvas.clipRect(0, 0, px, px);
        canvas.translate(pad, pad);
        layout.draw(canvas);
        canvas.restore();
        return bmp;
    }
}
