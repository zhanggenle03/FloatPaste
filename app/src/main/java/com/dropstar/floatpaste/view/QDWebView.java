package com.dropstar.floatpaste.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.webkit.WebSettings;
import android.webkit.WebView;

import com.dropstar.floatpaste.BuildConfig;
import com.dropstar.floatpaste.lib.PackageUtils;
import com.dropstar.floatpaste.lib.ScreenUtils;

/**
 * 封装的 WebView（原基于 QMUIWebView，现改为标准 Android WebView）。
 */
public class QDWebView extends WebView {

    public QDWebView(Context context) {
        this(context, null);
    }

    public QDWebView(Context context, AttributeSet attrs) {
        this(context, attrs, android.R.attr.webViewStyle);
    }

    public QDWebView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    @SuppressLint("SetJavaScriptEnabled")
    protected void init(Context context) {
        WebSettings webSettings = getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setSupportZoom(true);
        webSettings.setBuiltInZoomControls(true);
        webSettings.setDefaultTextEncodingName("GBK");
        webSettings.setUseWideViewPort(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        webSettings.setTextZoom(100);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }

        String screen = ScreenUtils.getScreenWidth(context) + "x" + ScreenUtils.getScreenHeight(context);
        String userAgent = "SnipasteAndroid/" + PackageUtils.getAppVersion(context)
                + " (Android; " + Build.VERSION.SDK_INT
                + "; Screen/" + screen + "; Scale/" + ScreenUtils.getDensity(context) + ")";
        String agent = getSettings().getUserAgentString();
        if (agent == null || !agent.contains(userAgent)) {
            getSettings().setUserAgentString(agent + " " + userAgent);
        }

        // 开启调试
        if (BuildConfig.DEBUG && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            setWebContentsDebuggingEnabled(true);
        }
    }

    public void exec(final String jsCode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            evaluateJavascript(jsCode, null);
        } else {
            loadUrl(jsCode);
        }
    }
}
