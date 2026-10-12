package com.deepseekharness.app.assistant;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * V2 面板入口（保留作兜底转发）。
 *
 * <p><b>2026-10-07 起面板本体不再是 Activity</b>：Activity 窗口在这台 EMUI 12 上，
 * 窗口里没画到的像素会被 HWC 合成成纯黑（transparentRegionHint 恒空）——
 * 卡片圆角外全是黑边，setFormat(TRANSLUCENT) 也救不回来；而悬浮窗
 * （TYPE_ACCESSIBILITY_OVERLAY）的透明像素实测能逐像素透出下层。
 * 面板搬进了 {@link AssistantPanel}，由无障碍服务挂窗。
 *
 * <p>本类现在只剩两件事：亮面板（服务在线时）/ 兜底进主界面（服务离线时），然后 finish。
 * 助手键资格（RoleManager 要求有 ASSIST activity）由 {@link DshaAssistActivity} 承担，
 * 快捷磁贴走 {@link DshaAssistantTileService}，两者都不再依赖本类。
 */
public class DshaAssistantActivity extends Activity {

  public static final String EXTRA_FROM_ASSIST = "from_assist";

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    // 2026-10-11 大众化：第一次点开先走引导，别直接把悬浮球塞给用户。
    //
    // 为什么必须这样：开发者知道"要先去系统设置开无障碍"，普通用户不知道。
    // 原来点开图标什么反馈都没有（面板挂不上就默默跳无障碍设置页），
    // 用户只会得出"这 App 是坏的"这个结论。引导页会把"要什么、为什么、去哪开"讲清楚。
    if (!OnboardingActivity.finished(this)) {
      OnboardingActivity.open(this);
      finish();
      return;
    }

    if (!com.deepseekharness.app.DshaAccessibilityService.showAssistantPanel()) {
      // 轻量版没有主界面：面板挂不上只可能是无障碍没开，直接把用户送到系统无障碍页。
      try {
        Intent fallback = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
        fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(fallback);
      } catch (Throwable ignored) {
      }
    }
    finish();
  }
}
