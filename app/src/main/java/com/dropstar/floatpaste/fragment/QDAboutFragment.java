package com.dropstar.floatpaste.fragment;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.dropstar.floatpaste.R;
import com.dropstar.floatpaste.base.BaseFragment;
import com.dropstar.floatpaste.databinding.FragmentAboutBinding;
import com.dropstar.floatpaste.lib.ClipBoardUtil;
import com.dropstar.floatpaste.lib.Group;
import com.dropstar.floatpaste.lib.PackageUtils;
import com.dropstar.floatpaste.lib.annotation.Widget;

import java.text.SimpleDateFormat;
import java.util.Locale;

import static com.dropstar.floatpaste.fragment.QDWebExplorerFragment.EXTRA_URL;
import static com.dropstar.floatpaste.fragment.QDWebExplorerFragment.EXTRA_TITLE;

/**
 * 关于界面
 */
public class QDAboutFragment extends BaseFragment {

    private FragmentAboutBinding binding;
    private static final String TAG = "QDAboutFragment";

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentAboutBinding.inflate(inflater, container, false);

        initTopBar();

        binding.version.setText(PackageUtils.getAppVersion(requireContext()));

        addAboutItem(getResources().getString(R.string.about_item_homepage), "https://qmuiteam.com/android");
        addAboutItem(getResources().getString(R.string.about_item_github), "https://github.com/Tencent/QMUI_Android");

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy", Locale.CHINA);
        String currentYear = dateFormat.format(new java.util.Date());
        binding.copyright.setText(String.format(getResources().getString(R.string.about_copyright), currentYear));

        return binding.getRoot();
    }

    private void initTopBar() {
        binding.topbar.setNavigationIcon(R.drawable.ic_arrow_back);
        binding.topbar.setNavigationOnClickListener(v -> popBackStack());
        binding.topbar.setTitle(getResources().getString(R.string.about_title));
    }

    private void addAboutItem(String title, String url) {
        View row = LayoutInflater.from(requireContext()).inflate(R.layout.item_about_row, binding.aboutList, false);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.setOnClickListener(v -> {
            Bundle bundle = new Bundle();
            bundle.putString(EXTRA_URL, url);
            bundle.putString(EXTRA_TITLE, title);
            Fragment fragment = new QDWebExplorerFragment();
            fragment.setArguments(bundle);
            startFragment(fragment);
        });
        binding.aboutList.addView(row);
    }

    @Override
    public void onResume() {
        super.onResume();
        String content = ClipBoardUtil.get(getContext());
        Log.d(TAG, "onResume: " + content);
    }
}
