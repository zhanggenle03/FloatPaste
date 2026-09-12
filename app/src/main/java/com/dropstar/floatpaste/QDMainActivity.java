package com.dropstar.floatpaste;

import android.os.Bundle;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.dropstar.floatpaste.base.BaseFragmentActivity;
import com.dropstar.floatpaste.fragment.HistoryFragment;
import com.dropstar.floatpaste.fragment.HomeFragment;
import com.dropstar.floatpaste.fragment.SettingsFragment;

public class QDMainActivity extends BaseFragmentActivity {
    private static final String TAG = "QDMainActivity";
    private static final String KEY_CURRENT_TAB = "current_tab";
    private static final String TAG_HOME = "home";
    private static final String TAG_HISTORY = "history";
    private static final String TAG_SETTINGS = "settings";

    private CustomRootView mRootView;
    private HomeFragment mHomeFragment;
    private HistoryFragment mHistoryFragment;
    private SettingsFragment mSettingsFragment;
    private int mCurrentTab = CustomRootView.TAB_HOME;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 输入法弹出时使用 adjustPan，窗口整体上移使输入框可见，底部菜单栏不随窗口缩短上移
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        if (savedInstanceState != null) {
            mCurrentTab = savedInstanceState.getInt(KEY_CURRENT_TAB, CustomRootView.TAB_HOME);
        }
        mRootView = new CustomRootView(this, R.id.snipaste_demo);
        setContentView(mRootView);
        ensureFragments();
        mRootView.setOnTabSelectedListener(this::switchToTab);
        mRootView.setSelectedTab(mCurrentTab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 保证在 Fragment 就绪（含配置变更重建）后，实际显示的页面与选中态一致
        ensureFragments();
        applyTab(mCurrentTab);
    }

    /**
     * 主页(HomeFragment)、记录页(HistoryFragment)与设置页(SettingsFragment)
     * 通过 show/hide 在底部导航间切换。
     * 其余子页面（如 WebExplorer）通过 startFragment 以 replace + 返回栈方式压入同一容器。
     */
    private void ensureFragments() {
        FragmentManager fm = getSupportFragmentManager();
        if (mHomeFragment == null) {
            mHomeFragment = (HomeFragment) fm.findFragmentByTag(TAG_HOME);
            if (mHomeFragment == null) {
                mHomeFragment = new HomeFragment();
                fm.beginTransaction().add(R.id.snipaste_demo, mHomeFragment, TAG_HOME).commitNow();
            }
        }
        if (mHistoryFragment == null) {
            mHistoryFragment = (HistoryFragment) fm.findFragmentByTag(TAG_HISTORY);
            if (mHistoryFragment == null) {
                mHistoryFragment = new HistoryFragment();
                fm.beginTransaction().add(R.id.snipaste_demo, mHistoryFragment, TAG_HISTORY)
                        .hide(mHistoryFragment).commitNow();
            }
        }
        if (mSettingsFragment == null) {
            mSettingsFragment = (SettingsFragment) fm.findFragmentByTag(TAG_SETTINGS);
            if (mSettingsFragment == null) {
                mSettingsFragment = new SettingsFragment();
                fm.beginTransaction().add(R.id.snipaste_demo, mSettingsFragment, TAG_SETTINGS)
                        .hide(mSettingsFragment).commitNow();
            }
        }
    }

    private void switchToTab(int index) {
        if (index == mCurrentTab && mHomeFragment != null && mHistoryFragment != null && mSettingsFragment != null) {
            return;
        }
        ensureFragments();
        if (mHomeFragment == null || mHistoryFragment == null || mSettingsFragment == null) {
            return;
        }
        mCurrentTab = index;
        applyTab(index);
        mRootView.setSelectedTab(index);
    }

    private void applyTab(int index) {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        ft.hide(mHomeFragment).hide(mHistoryFragment).hide(mSettingsFragment);
        if (index == CustomRootView.TAB_HOME) {
            ft.show(mHomeFragment);
        } else if (index == CustomRootView.TAB_HISTORY) {
            ft.show(mHistoryFragment);
        } else {
            ft.show(mSettingsFragment);
        }
        ft.commit();
    }

    @Override
    public void onBackPressed() {
        // 优先弹出子页面返回栈
        if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
            getSupportFragmentManager().popBackStack();
            return;
        }
        // 处于「记录 / 设置」页时，返回键回到「主页」而非直接退出
        if (mCurrentTab != CustomRootView.TAB_HOME) {
            switchToTab(CustomRootView.TAB_HOME);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_CURRENT_TAB, mCurrentTab);
    }
}
