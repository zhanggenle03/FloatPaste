package com.dropstar.floatpaste;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

import androidx.annotation.NonNull;

import com.dropstar.floatpaste.lib.AppLog;

public class QDApplication extends Application {

    @SuppressWarnings("StaticFieldLeak")
    private static Context context;
    // 当前前台 Activity（用于需要 Activity 上下文的场景，如 EasyFloat.with()）。无前台 Activity 时为 null。
    private static Activity currentActivity;

    public static Context getContext() {
        return context;
    }

    public static Activity getCurrentActivity() {
        return currentActivity;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        context = getApplicationContext();
        // 初始化全局日志：捕获未处理崩溃 + 记录运行日志（供「错误日志」页面排查）
        AppLog.init(context);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(@NonNull Activity activity) {
                currentActivity = activity;
            }

            @Override
            public void onActivityPaused(@NonNull Activity activity) {
                if (currentActivity == activity) currentActivity = null;
            }

            @Override
            public void onActivityCreated(@NonNull Activity activity, Bundle savedInstanceState) {
                AppLog.d("Lifecycle", "create " + activity.getClass().getSimpleName());
            }

            @Override
            public void onActivityStarted(@NonNull Activity activity) { }

            @Override
            public void onActivityStopped(@NonNull Activity activity) { }

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) { }

            @Override
            public void onActivityDestroyed(@NonNull Activity activity) { }
        });
    }
}
