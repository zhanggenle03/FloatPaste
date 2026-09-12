package com.dropstar.floatpaste;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import com.dropstar.floatpaste.lib.AppLog;

/**
 * 控制中心（Quick Settings）磁贴：一键添加后点按即读取剪切板文字并贴成悬浮贴图。
 * 通过 onTileAdded / onTileRemoved 配合本地存储跟踪磁贴的添加状态。
 */
public class ClipboardPasteTileService extends TileService {

    public static final String PREF_NAME = "tile_prefs";
    public static final String KEY_TILE_ADDED = "tile_added";

    @Override
    public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(Tile.STATE_ACTIVE);
            tile.updateTile();
        }
    }

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        AppLog.d("ClipboardPasteTile", "磁贴已被添加");
        saveAddedState(true);
    }

    @Override
    public void onTileRemoved() {
        super.onTileRemoved();
        AppLog.d("ClipboardPasteTile", "磁贴已被移除");
        saveAddedState(false);
    }

    private void saveAddedState(boolean added) {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_TILE_ADDED, added).apply();
    }

    @Override
    public void onClick() {
        super.onClick();
        AppLog.d("ClipboardPasteTile", "磁贴被点击，拉起一键贴文");
        Intent intent = new Intent(this, ClipboardPasteActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        startActivityAndCollapse(pendingIntent);
    }
}
