package com.deepseekharness.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.deepseekharness.app.assistant.AssistantKeepAliveReceiver;
import com.deepseekharness.app.assistant.AssistantWakeService;

/**
 * 开机自启（轻量版，2026-10-10）。
 *
 * <p>原 DSHA 的 BootReceiver 还管 ADB 设备桥、自动备份、无线调试等一堆事，
 * 那些都依赖 rootfs / DeviceBridgeService，在这个轻量 App 里全都没有。
 * 这里只留助手真正需要的两件：拉起语音唤醒 + 排常驻保活闹钟。
 *
 * <p>悬浮球不用在这里挂 —— 它要等无障碍服务连上才有 host 能挂，
 * 由 {@code DshaAccessibilityService.onServiceConnected} 负责。
 */
public class BootReceiver extends BroadcastReceiver {

  @Override
  public void onReceive(Context context, Intent intent) {
    if (context == null || intent == null) return;
    String action = intent.getAction();
    boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action)
        || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
        || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
    if (!boot) return;
    try {
      AssistantWakeService.startIfEnabled(context);
      // 重启后系统清掉所有闹钟，不重排保活就断链
      AssistantKeepAliveReceiver.schedule(context);
    } catch (Throwable t) {
      android.util.Log.w("DSHA/Boot", "开机拉起助手失败: " + t);
    }
  }
}
