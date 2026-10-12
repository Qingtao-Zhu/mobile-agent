package com.deepseekharness.app.assistant;

import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import com.deepseekharness.app.DshaAccessibilityService;

/**
 * V2 触发方式备选（用户已批准）：快捷设置磁贴。
 * 下拉通知栏点「DSHA 助手」= 呼出助手面板。
 *
 * <p>2026-10-07 起直接点亮 {@link AssistantPanel} 悬浮窗（不经过 Activity 转场）；
 * 无障碍服务没开时退回旧的 Activity 入口兜底。
 */
public class DshaAssistantTileService extends TileService {

  @Override
  public void onStartListening() {
    Tile tile = getQsTile();
    if (tile == null) return;
    tile.setState(Tile.STATE_INACTIVE);
    tile.updateTile();
  }

  @Override
  public void onClick() {
    // 服务没连上时走 request 排队补亮，别急着重开 Activity（同 DshaAssistActivity 的竞态）
    DshaAccessibilityService.requestAssistantPanel();
    Intent panel = new Intent(this, DshaAssistantActivity.class);
    panel.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    startActivity(panel);
  }
}
