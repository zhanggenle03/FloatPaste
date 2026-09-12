package com.dropstar.floatpaste;

import android.os.Bundle;

import androidx.fragment.app.FragmentTransaction;

import com.dropstar.floatpaste.base.BaseFragmentActivity;
import com.dropstar.floatpaste.fragment.PermissionListFragment;

public class PermissionListActivity extends BaseFragmentActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_permission_list);
        if (savedInstanceState == null) {
            FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
            ft.add(R.id.snipaste_demo, new PermissionListFragment());
            ft.commit();
        }
    }
}
