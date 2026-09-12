package com.to3g.snipasteandroid.fragment;

import android.content.Context;
import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.FileProvider;
import androidx.core.content.ContextCompat;

import com.lzf.easyfloat.EasyFloat;
import com.lzf.easyfloat.enums.ShowPattern;
import com.lzf.easyfloat.interfaces.OnFloatCallbacks;
import com.lzf.easyfloat.permission.PermissionUtils;
import com.to3g.snipasteandroid.R;
import com.to3g.snipasteandroid.base.BaseFragment;
import com.to3g.snipasteandroid.databinding.HomeLayoutBinding;
import com.to3g.snipasteandroid.lib.AppLog;
import com.to3g.snipasteandroid.lib.ClipBoardUtil;
import com.to3g.snipasteandroid.lib.DialogUtil;
import com.to3g.snipasteandroid.lib.Group;
import com.to3g.snipasteandroid.lib.HistoryStore;
import com.to3g.snipasteandroid.lib.ImageUtil;
import com.to3g.snipasteandroid.lib.SharePasteHelper;
import com.to3g.snipasteandroid.lib.annotation.Widget;
import com.to3g.snipasteandroid.view.ScaleImage;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Widget(group = Group.Other, name = "Home")
public class HomeFragment extends BaseFragment {

    private static final String TAG = "HomeFragment";

    private HomeLayoutBinding binding;
    private List<String> floatingImages = new ArrayList<>();

    /** 拍照临时文件：TakePicture 契约需要一个可写的 Uri */
    private File cameraTempFile;

    /** 选图（GetContent）：返回 content:// Uri */
    private final ActivityResultLauncher<String> albumLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), this::onAlbumResult);

    /** 拍照（TakePicture）：需要一个可写的 Uri */
    private final ActivityResultLauncher<Uri> cameraLauncher =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), this::onCameraResult);

    /** 相机权限请求：授权后启动拍照，拒绝时只提示、不崩溃 */
    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (Boolean.TRUE.equals(granted)) {
                    launchCamera();
                } else if (getContext() != null) {
                    Toast.makeText(getContext(), R.string.camera_permission_denied, Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = HomeLayoutBinding.inflate(inflater, container, false);
        binding.helpTextView.setText(R.string.helpText);
        binding.pasteTextButton.setOnClickListener(v -> onPasteTextButtonClick());
        binding.pasteClipboardButton.setOnClickListener(v -> onPasteClickboardButtonClick());
        binding.albumButton.setOnClickListener(v -> onAlbumButtonClick());
        binding.cameraButton.setOnClickListener(v -> onCameraButtonClick());
        binding.clearTextButton.setOnClickListener(v -> binding.editText.setText(""));
        SharePasteHelper.setCloseAllCallback(this::clearAllFloatViews);
        return binding.getRoot();
    }

    /**
     * 点击相机按钮：先要悬浮窗权限，再确认相机权限，最后打开系统相机。
     * 使用系统原生 TakePicture 契约，照片写入 App 私有 cache 目录，无需存储权限。
     */
    protected void onCameraButtonClick() {
        ensureFloatPermissionThen(this::ensureCameraPermissionThenLaunch);
    }

    /**
     * 打开相机前确认 CAMERA 权限。未授予则弹系统授权框；被拒绝时只提示、不崩溃。
     * 注意：即使使用系统 TakePicture 契约，声明了 CAMERA 权限的 App 在较新 Android 上
     * 仍需运行时获得该权限，否则启动相机 Intent 会抛 SecurityException 导致闪退。
     */
    private void ensureCameraPermissionThenLaunch() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            launchCamera();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void launchCamera() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        File dir = context.getExternalCacheDir();
        if (dir == null) {
            Toast.makeText(context, R.string.file_access_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        cameraTempFile = new File(dir, "snipaste_camera_" + System.currentTimeMillis() + ".jpg");
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", cameraTempFile);
        cameraLauncher.launch(uri);
    }

    private void onCameraResult(Boolean success) {
        if (Boolean.TRUE.equals(success) && cameraTempFile != null && cameraTempFile.exists()) {
            Log.d(TAG, "onCameraResult: " + cameraTempFile.getAbsolutePath());
            initImageView(cameraTempFile.getAbsolutePath());
        }
        cameraTempFile = null;
    }

    /**
     * 点击相册按钮：先要悬浮窗权限，再打开系统/原生照片选择器。
     * 使用系统原生 GetContent，Android 13+ 走 Photo Picker，无需存储权限。
     */
    protected void onAlbumButtonClick() {
        ensureFloatPermissionThen(this::launchAlbum);
    }

    private void launchAlbum() {
        albumLauncher.launch("image/*");
    }

    private void onAlbumResult(Uri uri) {
        if (uri == null) {
            return;
        }
        Log.d(TAG, "onAlbumResult: " + uri);
        String path = copyUriToTempFile(uri);
        if (path != null) {
            initImageView(path);
        } else if (getContext() != null) {
            Toast.makeText(getContext(), R.string.file_access_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 把系统返回的 content:// Uri 拷贝到 App 私有 cache 目录，得到真实文件路径，
     * 以便复用现有的 initImageView(String path) 流程（ImageUtil 取尺寸 + Drawable.createFromPath）。
     */
    private String copyUriToTempFile(Uri uri) {
        Context context = getContext();
        if (context == null) {
            return null;
        }
        File dir = context.getExternalCacheDir();
        if (dir == null) {
            return null;
        }
        File out = new File(dir, "snipaste_album_" + System.currentTimeMillis() + ".jpg");
        try (InputStream is = context.getContentResolver().openInputStream(uri);
             FileOutputStream os = new FileOutputStream(out)) {
            if (is == null) {
                return null;
            }
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) > 0) {
                os.write(buf, 0, len);
            }
            return out.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, "copyUriToTempFile failed", e);
            return null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        SharePasteHelper.setCloseAllCallback(null);
    }

    private ViewGroup.LayoutParams getDefaultParams(String path, ViewGroup.LayoutParams layoutParams) {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        getActivity().getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        int screenWidth = displayMetrics.widthPixels;

        Size size = ImageUtil.getImageSize(path);
        int imgWidth = size.getWidth();
        int imgHeight = size.getHeight();
        Log.d(TAG, String.format("initImageView: image size：%d, %d", imgWidth, imgHeight));

        float rate = 0.8f;

        layoutParams.width = (int) (rate * screenWidth);
        layoutParams.height = (int) (layoutParams.width * 1.0f / imgWidth * imgHeight);
        Log.d(TAG, String.format("initImageView: layout size：%d, %d", layoutParams.width, layoutParams.height));
        return layoutParams;
    }

    private ViewGroup.LayoutParams getDefaultParams(Bitmap bitmap, ViewGroup.LayoutParams layoutParams) {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        getActivity().getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        int screenWidth = displayMetrics.widthPixels;

        int imgWidth = bitmap.getWidth();
        int imgHeight = bitmap.getHeight();
        Log.d(TAG, String.format("initImageView: image size：%d, %d", imgWidth, imgHeight));

        float rate = 0.8f;

        layoutParams.width = (int) (rate * screenWidth);
        layoutParams.height = (int) (layoutParams.width * 1.0f / imgWidth * imgHeight);
        Log.d(TAG, String.format("initImageView: layout size：%d, %d", layoutParams.width, layoutParams.height));
        return layoutParams;
    }

    private void initImageView(String path) {
        EasyFloat
                .with(Objects.requireNonNull(getActivity()))
                .setLayout(R.layout.image_paste)
                .setShowPattern(ShowPattern.ALL_TIME)
                .setLocation(100, 200)
                .setTag(path)
                .registerCallbacks(new OnFloatCallbacks() {
                    @Override
                    public void createdResult(boolean isCreated, String msg, View view) {
                    }

                    @Override
                    public void show(View view) {
                    }

                    @Override
                    public void hide(View view) {
                    }

                    @Override
                    public void dismiss() {
                        SharePasteHelper.dismissOpacitySlider(path);
                        floatingImages.remove(path);
                    }

                    @Override
                    public void touchEvent(View view, MotionEvent event) {
                        // 已自接管（见 .show() 后 setOnTouchListener + appFloatDragEnable(false)）
                    }

                    @Override
                    public void drag(View view, MotionEvent event) {
                    }

                    @Override
                    public void dragEnd(View view) {
                    }
                })
                .show();
        EasyFloat.appFloatDragEnable(false, path);
        {
            View first = EasyFloat.getAppFloatView(path);
            if (first != null) {
                View body = first.findViewById(R.id.imageOutterShadow);
                if (body != null) {
                    body.setOnTouchListener((v, e) -> {
                        SharePasteHelper.handleFloatTouch(path, v, e);
                        return true;
                    });
                }
            }
        }
        floatingImages.add(path);
        View view = EasyFloat.getAppFloatView(path);
        assert view != null;
        View imageOutter = view.findViewById(R.id.imageOutter);
        View imageOutterShadow = view.findViewById(R.id.imageOutterShadow);

        ViewGroup.LayoutParams layoutParams = imageOutterShadow.getLayoutParams();
        imageOutterShadow.setLayoutParams(getDefaultParams(path, layoutParams));

        imageOutter.setBackground(Drawable.createFromPath(path));

        // 缩放最小值（px），防止 onScaled 累加到 0/负数导致布局异常
        final int minSize = getResources().getDimensionPixelSize(R.dimen.sticker_min_size);
        ScaleImage scaleImage = view.findViewById(R.id.scaleImage);
        scaleImage.onScaledListener = new ScaleImage.OnScaledListener() {
            @Override
            public void onScaled(float x, float y, MotionEvent event) {
                int newWidth = (int) (layoutParams.width + x);
                int newHeight = (int) (layoutParams.height + y);
                if (newWidth < minSize) newWidth = minSize;
                if (newHeight < minSize) newHeight = minSize;
                layoutParams.width = newWidth;
                layoutParams.height = newHeight;
                imageOutterShadow.setLayoutParams(layoutParams);
            }

            @Override
            public void onScaleChange(float scaleFactor, float focusX, float focusY) {
            }
        };
        SharePasteHelper.applyZoomActionToSticker(view);
        SharePasteHelper.attachOpacitySlider(getActivity(), path, imageOutterShadow);
        // 记录历史贴图（持久保存，内容去重；相册/拍照的源文件在 cache 目录，会被复制到持久目录）
        HistoryStore.addImage(requireContext(), new File(path));

        AppLog.d("HomeFragment", "主页贴图图片 path=" + path);
    }

    private void initImageView(Bitmap bitmap) {
        ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(0, 0);
        lp = getDefaultParams(bitmap, lp);
        showImageFloatByBitmap("bitmap", bitmap, lp.width, lp.height);
        // 记录历史贴图（持久保存，内容去重）
        HistoryStore.addImage(requireContext(), bitmap);
        AppLog.d("HomeFragment", "主页贴图图片(bitmap) 尺寸=" + bitmap.getWidth() + "x" + bitmap.getHeight());
    }

    private void showImageFloatByBitmap(String tagName, Bitmap bitmap, int initWidth, int initHeight) {
        EasyFloat
                .with(Objects.requireNonNull(getActivity()))
                .setLayout(R.layout.image_paste)
                .setShowPattern(ShowPattern.ALL_TIME)
                .setLocation(100, 200)
                .setTag(tagName)
                .registerCallbacks(new OnFloatCallbacks() {
                    @Override
                    public void createdResult(boolean isCreated, String msg, View view) {
                    }

                    @Override
                    public void show(View view) {
                    }

                    @Override
                    public void hide(View view) {
                    }

                    @Override
                    public void dismiss() {
                        SharePasteHelper.dismissOpacitySlider(tagName);
                        floatingImages.remove(tagName);
                    }

                    @Override
                    public void touchEvent(View view, MotionEvent event) {
                        // 已自接管（见 .show() 后 setOnTouchListener + appFloatDragEnable(false)）
                    }

                    @Override
                    public void drag(View view, MotionEvent event) {
                    }

                    @Override
                    public void dragEnd(View view) {
                    }
                })
                .show();
        EasyFloat.appFloatDragEnable(false, tagName);
        {
            View first = EasyFloat.getAppFloatView(tagName);
            if (first != null) {
                View body = first.findViewById(R.id.imageOutterShadow);
                if (body != null) {
                    body.setOnTouchListener((v, e) -> {
                        SharePasteHelper.handleFloatTouch(tagName, v, e);
                        return true;
                    });
                }
            }
        }
        floatingImages.add(tagName);
        View view = EasyFloat.getAppFloatView(tagName);
        assert view != null;
        View imageOutter = view.findViewById(R.id.imageOutter);
        View imageOutterShadow = view.findViewById(R.id.imageOutterShadow);

        ViewGroup.LayoutParams layoutParams = imageOutterShadow.getLayoutParams();
        layoutParams.width = initWidth;
        layoutParams.height = initHeight;
        imageOutterShadow.setLayoutParams(layoutParams);

        imageOutter.setBackground(new BitmapDrawable(getResources(), bitmap));

        // 缩放最小值（px），防止 onScaled 累加到 0/负数导致布局异常
        final int minSize = getResources().getDimensionPixelSize(R.dimen.sticker_min_size);
        ScaleImage scaleImage = view.findViewById(R.id.scaleImage);
        scaleImage.onScaledListener = new ScaleImage.OnScaledListener() {
            @Override
            public void onScaled(float x, float y, MotionEvent event) {
                int newWidth = (int) (layoutParams.width + x);
                int newHeight = (int) (layoutParams.height + y);
                if (newWidth < minSize) newWidth = minSize;
                if (newHeight < minSize) newHeight = minSize;
                layoutParams.width = newWidth;
                layoutParams.height = newHeight;
                imageOutterShadow.setLayoutParams(layoutParams);
            }

            @Override
            public void onScaleChange(float scaleFactor, float focusX, float focusY) {
            }
        };
        SharePasteHelper.applyZoomActionToSticker(view);
        SharePasteHelper.attachOpacitySlider(getActivity(), tagName, imageOutterShadow);
    }

    protected void onPasteTextButtonClick() {
        floatText(binding.editText.getText().toString());
    }

    protected void onPasteClickboardButtonClick() {
        String content = ClipBoardUtil.get(Objects.requireNonNull(getContext()));
        Log.d(TAG, "clipboard content: " + content);
        binding.editText.setText(content);
        floatText(content);
    }

    private void showFloatText(String content) {
        if (content == null || content.trim().isEmpty()) {
            Toast.makeText(getContext(), getText(R.string.blankContent), Toast.LENGTH_SHORT).show();
            return;
        }
        // 统一走 SharePasteHelper 路径：TextView 替代 Bitmap，支持选择复制 + 超长文本
        SharePasteHelper.showFloatText(requireActivity(), content);
    }

    private void floatText(String content) {
        ensureFloatPermissionThen(() -> showFloatText(content));
    }

    private void ensureFloatPermissionThen(Runnable action) {
        if (PermissionUtils.checkPermission(Objects.requireNonNull(getContext()))) {
            action.run();
        } else {
            DialogUtil.showPermissionDialog(requireActivity(), action);
        }
    }

    /**
     * 清空所有浮窗（本 Fragment 与 SharePasteHelper 创建的）
     */
    private void clearAllFloatViews() {
        for (String path : new ArrayList<>(floatingImages)) {
            SharePasteHelper.closeSticker(path);
        }
        floatingImages.clear();
        for (String tag : new ArrayList<>(SharePasteHelper.getHelperImageTags())) {
            SharePasteHelper.closeSticker(tag);
        }
        SharePasteHelper.getHelperImageTags().clear();
    }
}
