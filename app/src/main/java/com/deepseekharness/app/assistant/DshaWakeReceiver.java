package com.deepseekharness.app.assistant;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 「叫助手」按钮 / 任意唤醒广播的落点。
 *
 * <p>这是**不依赖无障碍事件**的唤醒路径里最末尾的一环：
 * 通知按钮、快捷设置磁贴、桌面快捷方式、乃至 Tasker 之类的宏 App，
 * 都可以发一个 {@link DshaWakeTile#WAKE_ACTION} 的广播到这里。
 * 这里只做一件事：把面板亮起来；服务不在就退回 {@link DshaAssistantActivity} 的兜底。
 */
public final class DshaWakeReceiver extends BroadcastReceiver {

  private static final String TAG = "DSHA/Wake";

  @Override
  public void onReceive(Context ctx, Intent intent) {
    Log.i(TAG, "wake: " + (intent == null ? "null" : intent.getAction()));
    try {
      if (!com.deepseekharness.app.DshaAccessibilityService.showAssistantPanel()) {
        // 服务没连上（通常刚 force-stop 完）：走转发壳，它会发一条去开无障碍的通知，
        // 而不是把主界面弹起来盖掉用户正在用的界面。
        Intent i = new Intent(ctx, DshaAssistantActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
      }
    } catch (Throwable t) {
      Log.w(TAG, "唤醒失败: " + t);
    }
  }
}
