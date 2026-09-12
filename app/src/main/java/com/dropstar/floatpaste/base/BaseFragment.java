package com.dropstar.floatpaste.base;

import android.content.Context;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.dropstar.floatpaste.R;
import com.dropstar.floatpaste.fragment.QDWebExplorerFragment;

/**
 * Fragment 基类（原基于 QMUI arch，现改为标准 AndroidX Fragment）。
 * 为兼容各子 Fragment 的导航调用，这里保留 startFragment / popBackStack / onLastFragmentFinish
 * 的方法签名；后续在「去 QMUI 控件」阶段会逐步移除这些兼容方法。
 */
public abstract class BaseFragment extends Fragment {

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
    }

    /**
     * 将目标 Fragment 压入宿主 Activity 的返回栈（替代 QMUI 的 startFragment）。
     * 使用 R.id.snipaste_demo 作为统一的 Fragment 容器。
     */
    protected void startFragment(@NonNull Fragment fragment) {
        requireActivity().getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.snipaste_demo, fragment)
                .addToBackStack(null)
                .commit();
    }

    /** 弹出当前 Fragment（替代 QMUI 的 popBackStack）。 */
    protected void popBackStack() {
        requireActivity().getSupportFragmentManager().popBackStack();
    }

    /**
     * 兼容方法：当该 Fragment 位于返回栈底部、用户按返回时 QMUI 会回调它来决定
     * 下一个显示的 Fragment。迁移到 AndroidX 后由 Activity 统一管理返回栈，此方法暂不参与逻辑。
     */
    public Object onLastFragmentFinish() {
        return null;
    }

    /**
     * 兼容方法：原 QMUI 侧滑返回判定。决策 #4 已移除侧滑返回，这里固定返回 false。
     */
    protected boolean canDragBack() {
        return false;
    }

    protected boolean canDragBack(android.content.Context context, int dragDirection, int moveEdge) {
        return false;
    }

    protected void goToWebExplorer(@NonNull String url, @Nullable String title) {
        Bundle args = new Bundle();
        args.putString(QDWebExplorerFragment.EXTRA_URL, url);
        args.putString(QDWebExplorerFragment.EXTRA_TITLE, title);
        QDWebExplorerFragment fragment = new QDWebExplorerFragment();
        fragment.setArguments(args);
        startFragment(fragment);
    }
}
