package com.dropstar.floatpaste.fragment;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import com.dropstar.floatpaste.R;
import com.dropstar.floatpaste.base.BaseFragment;
import com.dropstar.floatpaste.databinding.FragmentWebviewExplorerBinding;
import com.dropstar.floatpaste.lib.DialogUtil;
import com.dropstar.floatpaste.lib.ScreenUtils;
import com.dropstar.floatpaste.view.QDWebView;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import android.text.TextUtils;

/**
 * 内嵌 WebView 浏览器（原基于 QMUIWebView，现改用标准 WebView）。
 */
public class QDWebExplorerFragment extends BaseFragment {
    public static final String EXTRA_URL = "EXTRA_URL";
    public static final String EXTRA_TITLE = "EXTRA_TITLE";
    public static final String EXTRA_NEED_DECODE = "EXTRA_NEED_DECODE";

    private final static int PROGRESS_PROCESS = 0;
    private final static int PROGRESS_GONE = 1;

    private FragmentWebviewExplorerBinding binding;
    private QDWebView mWebView;

    private String mUrl;
    private String mTitle;
    private ProgressHandler mProgressHandler;
    private boolean mIsPageFinished = false;
    private boolean mNeedDecodeUrl = false;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        Bundle bundle = getArguments();
        if (bundle != null) {
            String url = bundle.getString(EXTRA_URL);
            mTitle = bundle.getString(EXTRA_TITLE);
            mNeedDecodeUrl = bundle.getBoolean(EXTRA_NEED_DECODE, false);
            if (url != null && url.length() > 0) {
                handleUrl(url);
            }
        }

        mProgressHandler = new ProgressHandler();

        binding = FragmentWebviewExplorerBinding.inflate(inflater, container, false);
        initTopbar();
        initWebView();
        return binding.getRoot();
    }

    protected void initTopbar() {
        binding.topbar.setNavigationIcon(R.drawable.ic_arrow_back);
        binding.topbar.setNavigationOnClickListener(v -> popBackStack());
        updateTitle(mTitle);
    }

    private void updateTitle(String title) {
        if (!TextUtils.isEmpty(title)) {
            mTitle = title;
            binding.topbar.setTitle(mTitle);
        }
    }

    protected void initWebView() {
        mWebView = new QDWebView(requireContext());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        binding.webviewContainer.addView(mWebView, lp);
        binding.webviewContainer.setFitsSystemWindows(false);
        FrameLayout.LayoutParams containerLp = (FrameLayout.LayoutParams) binding.webviewContainer.getLayoutParams();
        containerLp.topMargin = ScreenUtils.dp2px(requireContext(), 48);
        binding.webviewContainer.setLayoutParams(containerLp);

        mWebView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
                boolean needConfirm = !url.startsWith("http://qmuiteam.com") && !url.startsWith("https://qmuiteam.com");
                if (needConfirm) {
                    final String finalURL = url;
                    DialogUtil.showConfirm(requireContext(), null, "确认下载此文件？",
                            getString(R.string.ok), false, () -> {
                                doDownload(finalURL);
                                popBackStack();
                            });
                } else {
                    doDownload(url);
                }
            }

            private void doDownload(String url) {
            }
        });

        mWebView.setWebChromeClient(getWebViewChromeClient());
        mWebView.setWebViewClient(getWebViewClient());
        mWebView.requestFocus(View.FOCUS_DOWN);
        setZoomControlGone(mWebView);
        configWebView(binding.webviewContainer, mWebView);
        mWebView.loadUrl(mUrl);
    }

    protected void configWebView(FrameLayout webViewContainer, WebView webView) {
    }

    protected WebChromeClient getWebViewChromeClient() {
        return new ExplorerWebViewChromeClient(this);
    }

    protected WebViewClient getWebViewClient() {
        return new ExplorerWebViewClient();
    }

    private void sendProgressMessage(int progressType, int newProgress, int duration) {
        Message msg = new Message();
        msg.what = progressType;
        msg.arg1 = newProgress;
        msg.arg2 = duration;
        mProgressHandler.sendMessage(msg);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mWebView != null) {
            mWebView.destroy();
        }
    }

    public static void setZoomControlGone(WebView webView) {
        // 高版本 WebView 已移除 ZoomButtonsController，隐藏缩放控件用标准 API 即可
        webView.getSettings().setDisplayZoomControls(false);
    }

    public static class ExplorerWebViewChromeClient extends WebChromeClient {
        private QDWebExplorerFragment mFragment;

        public ExplorerWebViewChromeClient(QDWebExplorerFragment fragment) {
            mFragment = fragment;
        }

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            super.onProgressChanged(view, newProgress);
            if (newProgress > mFragment.mProgressHandler.mDstProgressIndex) {
                mFragment.sendProgressMessage(PROGRESS_PROCESS, newProgress, 100);
            }
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            super.onReceivedTitle(view, title);
            mFragment.updateTitle(view.getTitle());
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            callback.onCustomViewHidden();
        }

        @Override
        public void onHideCustomView() {
        }
    }

    protected class ExplorerWebViewClient extends WebViewClient {

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            if (TextUtils.isEmpty(mTitle)) {
                updateTitle(view.getTitle());
            }
            if (mProgressHandler.mDstProgressIndex == 0) {
                sendProgressMessage(PROGRESS_PROCESS, 30, 500);
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            sendProgressMessage(PROGRESS_GONE, 100, 0);
            if (TextUtils.isEmpty(mTitle)) {
                updateTitle(view.getTitle());
            }
        }
    }

    private class ProgressHandler extends Handler {

        private int mDstProgressIndex;
        private int mDuration;
        private ObjectAnimator mAnimator;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case PROGRESS_PROCESS:
                    mIsPageFinished = false;
                    mDstProgressIndex = msg.arg1;
                    mDuration = msg.arg2;
                    binding.progressBar.setVisibility(View.VISIBLE);
                    if (mAnimator != null && mAnimator.isRunning()) {
                        mAnimator.cancel();
                    }
                    mAnimator = ObjectAnimator.ofInt(binding.progressBar, "progress", mDstProgressIndex);
                    mAnimator.setDuration(mDuration);
                    mAnimator.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            if (binding.progressBar.getProgress() == 100) {
                                sendEmptyMessageDelayed(PROGRESS_GONE, 500);
                            }
                        }
                    });
                    mAnimator.start();
                    break;
                case PROGRESS_GONE:
                    mDstProgressIndex = 0;
                    mDuration = 0;
                    binding.progressBar.setProgress(0);
                    binding.progressBar.setVisibility(View.GONE);
                    if (mAnimator != null && mAnimator.isRunning()) {
                        mAnimator.cancel();
                    }
                    mAnimator = ObjectAnimator.ofInt(binding.progressBar, "progress", 0);
                    mAnimator.setDuration(0);
                    mAnimator.removeAllListeners();
                    mIsPageFinished = true;
                    break;
                default:
                    break;
            }
        }
    }

    private void handleUrl(String url) {
        if (mNeedDecodeUrl) {
            String decodeURL;
            try {
                decodeURL = URLDecoder.decode(url, "utf-8");
            } catch (UnsupportedEncodingException ignored) {
                decodeURL = url;
            }
            mUrl = decodeURL;
        } else {
            mUrl = url;
        }
    }
}
