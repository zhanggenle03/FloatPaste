package com.dropstar.floatpaste.fragment;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.dropstar.floatpaste.R;
import com.dropstar.floatpaste.base.BaseFragment;
import com.dropstar.floatpaste.databinding.FragmentTabViewpagerLayoutBinding;
import com.dropstar.floatpaste.lib.Group;
import com.dropstar.floatpaste.lib.annotation.Widget;

import java.util.HashMap;
import java.util.Map;

@Widget(group = Group.Other, name = "固定宽度，内容均分")
public class QDTabSegmentFixModeFragment extends BaseFragment {
    private FragmentTabViewpagerLayoutBinding binding;
    private Map<ContentPage, View> mPageMap = new HashMap<>();
    private ContentPage mDestPage = ContentPage.Item1;
    private PagerAdapter mPagerAdapter = new PagerAdapter() {
        @Override
        public boolean isViewFromObject(View view, Object object) {
            return view == object;
        }

        @Override
        public int getCount() {
            return ContentPage.SIZE;
        }

        @Override
        public Object instantiateItem(final ViewGroup container, int position) {
            ContentPage page = ContentPage.getPage(position);
            View view = getPageView(page);
            ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            container.addView(view, params);
            return view;
        }

        @Override
        public void destroyItem(ViewGroup container, int position, Object object) {
            container.removeView((View) object);
        }

        @Override
        public CharSequence getPageTitle(int position) {
            ContentPage page = ContentPage.getPage(position);
            return page == ContentPage.Item1
                    ? getString(R.string.tabSegment_item_1_title)
                    : getString(R.string.tabSegment_item_2_title);
        }
    };

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentTabViewpagerLayoutBinding.inflate(inflater, container, false);
        initTopBar();
        initTabAndPager();
        return binding.getRoot();
    }

    private void initTopBar() {
        binding.topbar.setTitle(getString(R.string.app_name));
        binding.topbarRightChangeButton.setOnClickListener(v ->
                Toast.makeText(getContext(), "点击干嘛", Toast.LENGTH_SHORT).show());
    }

    private void initTabAndPager() {
        binding.contentViewPager.setAdapter(mPagerAdapter);
        binding.contentViewPager.setCurrentItem(mDestPage.getPosition(), false);
        binding.tabSegment.setupWithViewPager(binding.contentViewPager);
    }

    private View getPageView(ContentPage page) {
        View view = mPageMap.get(page);
        if (view == null) {
            TextView textView = new TextView(requireContext());
            textView.setGravity(Gravity.CENTER);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            textView.setTextColor(ContextCompat.getColor(requireContext(), R.color.app_color_description));

            if (page == ContentPage.Item1) {
                textView.setText(R.string.tabSegment_item_1_content);
            } else if (page == ContentPage.Item2) {
                textView.setText(R.string.tabSegment_item_2_content);
            }

            view = textView;
            mPageMap.put(page, view);
        }
        return view;
    }

    public enum ContentPage {
        Item1(0),
        Item2(1);
        public static final int SIZE = 2;
        private final int position;

        ContentPage(int pos) {
            position = pos;
        }

        public static ContentPage getPage(int position) {
            switch (position) {
                case 0:
                    return Item1;
                case 1:
                    return Item2;
                default:
                    return Item1;
            }
        }

        public int getPosition() {
            return position;
        }
    }
}
