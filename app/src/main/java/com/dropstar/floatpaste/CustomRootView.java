package com.dropstar.floatpaste;

import android.content.Context;
import android.graphics.PorterDuff;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

public class CustomRootView extends FrameLayout {

    public static final int TAB_HOME = 0;
    public static final int TAB_HISTORY = 1;
    public static final int TAB_SETTINGS = 2;

    public interface OnTabSelectedListener {
        void onTabSelected(int index);
    }

    private FrameLayout fragmentContainer;
    private LinearLayout tabHome;
    private LinearLayout tabHistory;
    private LinearLayout tabSettings;
    private ImageView tabHomeIcon;
    private ImageView tabHistoryIcon;
    private ImageView tabSettingsIcon;
    private TextView tabHomeLabel;
    private TextView tabHistoryLabel;
    private TextView tabSettingsLabel;
    private OnTabSelectedListener listener;

    public CustomRootView(Context context, int fragmentContainerId) {
        super(context);

        // 用一个竖直 LinearLayout 包裹「内容容器(权重1) + 底部导航栏」两部分，
        // 使底部菜单常驻且内容不与其重叠。
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        fragmentContainer = new FrameLayout(context);
        fragmentContainer.setId(fragmentContainerId);
        root.addView(fragmentContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        View bottomNav = LayoutInflater.from(context).inflate(R.layout.bottom_nav_bar, root, false);
        root.addView(bottomNav, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addView(root);

        tabHome = bottomNav.findViewById(R.id.tab_home);
        tabHistory = bottomNav.findViewById(R.id.tab_history);
        tabSettings = bottomNav.findViewById(R.id.tab_settings);
        tabHomeIcon = bottomNav.findViewById(R.id.tab_home_icon);
        tabHistoryIcon = bottomNav.findViewById(R.id.tab_history_icon);
        tabSettingsIcon = bottomNav.findViewById(R.id.tab_settings_icon);
        tabHomeLabel = bottomNav.findViewById(R.id.tab_home_label);
        tabHistoryLabel = bottomNav.findViewById(R.id.tab_history_label);
        tabSettingsLabel = bottomNav.findViewById(R.id.tab_settings_label);

        tabHome.setOnClickListener(v -> {
            if (listener != null) listener.onTabSelected(TAB_HOME);
        });
        tabHistory.setOnClickListener(v -> {
            if (listener != null) listener.onTabSelected(TAB_HISTORY);
        });
        tabSettings.setOnClickListener(v -> {
            if (listener != null) listener.onTabSelected(TAB_SETTINGS);
        });

        setSelectedTab(TAB_HOME);
    }

    public void setOnTabSelectedListener(OnTabSelectedListener l) {
        this.listener = l;
    }

    public void setSelectedTab(int index) {
        int selectedColor = ContextCompat.getColor(getContext(), R.color.app_color_blue);
        int normalColor = ContextCompat.getColor(getContext(), R.color.app_color_gray_6);
        tabHomeIcon.setColorFilter(index == TAB_HOME ? selectedColor : normalColor, PorterDuff.Mode.SRC_IN);
        tabHomeLabel.setTextColor(index == TAB_HOME ? selectedColor : normalColor);
        tabHistoryIcon.setColorFilter(index == TAB_HISTORY ? selectedColor : normalColor, PorterDuff.Mode.SRC_IN);
        tabHistoryLabel.setTextColor(index == TAB_HISTORY ? selectedColor : normalColor);
        tabSettingsIcon.setColorFilter(index == TAB_SETTINGS ? selectedColor : normalColor, PorterDuff.Mode.SRC_IN);
        tabSettingsLabel.setTextColor(index == TAB_SETTINGS ? selectedColor : normalColor);
    }
}
