package com.deepseekharness.app.assistant;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.util.Locale;

/**
 * 兼容层：把"不同 Android 版本 / 不同厂商 ROM / 不同屏幕"的差异收在一处。
 *
 * <p><b>为什么必须单独一层</b>（2026-10-11 大众化改造）：原来这些判断散在各处，
 * 而且只在华为 EMUI 12 / Android 11 上验过。普通用户遍布各品牌各版本，
 * 任何一处"想当然"都会变成"点了没反应"——最难排查的那类问题。
 *
 * <p><b>三条纪律</b>：
 * <ol>
 *   <li>跳系统设置页一律<b>多候选 + 失败降级</b>：厂商 ROM 常改这些 Activity 的路径，
 *       写死一个路径在别的机器上就是 ActivityNotFoundException（或静默什么都不发生）。
 *   <li>能力判断用 {@code Build.VERSION.SDK_INT} 与 {@code PackageManager} 实测，
 *       不靠"猜这个版本应该有"。
 *   <li><b>绝不擅自改系统设置</b>（如自动旋转、亮度）——只读不写。用户的手机状态归用户。
 * </ol>
 */
final class Compat {

  private Compat() {}

  // ---------------------------------------------------------------- 版本

  /** Android 11+：分区存储强制、`MANAGE_EXTERNAL_STORAGE` 可用。 */
  static boolean atLeastR() {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R; // 30
  }

  /** Android 13+：才有 `POST_NOTIFICATIONS` 运行时权限。 */
  static boolean atLeastT() {
    return Build.VERSION.SDK_INT >= 33;
  }

  /** Android 12+：`PendingIntent` 必须显式指定可变性。 */
  static boolean atLeastS() {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S; // 31
  }

  /** 无障碍截屏（`AccessibilityService#takeScreenshot`）只有 Android 11+ 有。 */
  static boolean supportsAccessibilityScreenshot() {
    return atLeastR();
  }

  // ---------------------------------------------------------------- 设备画像

  /** 厂商 ROM 标识，用于日志与兼容性报告（不做功能开关，只作记录/提示）。 */
  static String romTag() {
    String brand = safe(Build.BRAND);
    String manu = safe(Build.MANUFACTURER);
    String s = (manu + " " + brand).toLowerCase(Locale.ROOT);
    if (s.contains("huawei") || s.contains("honor")) return "EMUI/HarmonyOS";
    if (s.contains("xiaomi") || s.contains("redmi")) return "MIUI/HyperOS";
    if (s.contains("oppo") || s.contains("realme")) return "ColorOS";
    if (s.contains("vivo") || s.contains("iqoo")) return "OriginOS";
    if (s.contains("samsung")) return "OneUI";
    if (s.contains("oneplus")) return "OxygenOS";
    return manu.isEmpty() ? "unknown" : manu;
  }

  private static String safe(String v) {
    return v == null ? "" : v;
  }

  /** 设备画像字符串，写进定位规则的 `verifiedOn` 用。 */
  static String deviceProfile(Context ctx) {
    int[] size = screenSize(ctx);
    return Build.MODEL + "|android" + Build.VERSION.SDK_INT + "|" + size[0] + "x" + size[1];
  }

  /** 屏幕像素尺寸；取不到时退回 1080×1920（而不是崩）。 */
  static int[] screenSize(Context ctx) {
    try {
      WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
      if (wm != null) {
        DisplayMetrics dm = new DisplayMetrics();
        //noinspection deprecation —— getRealMetrics 在新版本上被 WindowMetrics 取代，
        // 但它在本项目支持的 30~37 全区间都可用，且不需要 Activity 上下文（面板是 Service）。
        wm.getDefaultDisplay().getRealMetrics(dm);
        if (dm.widthPixels > 0 && dm.heightPixels > 0) {
          return new int[] {dm.widthPixels, dm.heightPixels};
        }
      }
    } catch (Throwable ignored) {
    }
    return new int[] {1080, 1920};
  }

  static float density(Context ctx) {
    try {
      return ctx.getResources().getDisplayMetrics().density;
    } catch (Throwable t) {
      return 1f;
    }
  }

  /** 屏幕短边 dp。用来判断"这是小屏还是平板"，决定 UI 尺寸与布局。 */
  static int minWidthDp(Context ctx) {
    try {
      int[] px = screenSize(ctx);
      int min = Math.min(px[0], px[1]);
      return (int) (min / density(ctx));
    } catch (Throwable t) {
      return 360;
    }
  }

  // ---------------------------------------------------------------- 跳系统设置（多候选 + 降级）

  /**
   * 打开无障碍设置页。
   *
   * @return true = 至少有一个 Intent 成功启动
   */
  static boolean openAccessibilitySettings(Context ctx) {
    return startFirst(ctx,
        new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        // 部分 ROM 把无障碍藏在"辅助功能"子页，用带 fragment 的写法兜底
        new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(":settings:show_fragment", "AccessibilitySettings"),
        appDetails(ctx));
  }

  /** 打开悬浮窗权限页；ROM 不支持时降级到应用详情页（用户能在那里找到"显示在其他应用上层"）。 */
  static boolean openOverlaySettings(Context ctx) {
    Intent primary = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:" + ctx.getPackageName()));
    return startFirst(ctx, primary, appDetails(ctx));
  }

  /** 打开通知使用权（通知监听）页。 */
  static boolean openNotificationListenerSettings(Context ctx) {
    return startFirst(ctx,
        new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"),
        new Intent(Settings.ACTION_SECURITY_SETTINGS),
        appDetails(ctx));
  }

  /** 打开电池优化白名单页；部分 ROM 无此页 → 降级应用详情页。 */
  static boolean openBatteryOptimizationSettings(Context ctx) {
    return startFirst(ctx,
        new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
        appDetails(ctx));
  }

  /** 打开本应用的详情页（最终降级目标：几乎所有 ROM 都有）。 */
  static boolean openAppDetails(Context ctx) {
    return startFirst(ctx, appDetails(ctx));
  }

  private static Intent appDetails(Context ctx) {
    return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:" + ctx.getPackageName()));
  }

  /**
   * 按顺序尝试若干 Intent，返回是否有一个成功启动。
   *
   * <p>这是"厂商 ROM 差异"的统一兜底：写死一条路径的代码，换台机器就变成
   * 点了没反应（ActivityNotFoundException 被吞掉），用户只会觉得"这 App 坏了"。
   */
  private static boolean startFirst(Context ctx, Intent... candidates) {
    for (Intent i : candidates) {
      if (i == null) continue;
      try {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
        return true;
      } catch (ActivityNotFoundException e) {
        // 这台的 ROM 没这个页面 → 试下一个候选
      } catch (Throwable t) {
        android.util.Log.w("DSHA/Compat", "跳设置页失败: " + t);
      }
    }
    android.util.Log.w("DSHA/Compat", "所有候选设置页都打不开");
    return false;
  }

  // ---------------------------------------------------------------- 能力探测

  /**
   * 当前系统有没有可用的语音识别服务。
   *
   * <p>这台华为的机器上系统 ASR 被停用（随华为助手一起），所以唤醒走在线 ASR；
   * 但别的机器可能有，未来可据此选择更省电的本地识别。
   */
  static boolean hasSystemSpeechRecognizer(Context ctx) {
    try {
      return !ctx.getPackageManager()
          .queryIntentServices(new Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0)
          .isEmpty();
    } catch (Throwable t) {
      return false;
    }
  }

  /** 一句话描述当前环境，给日志/自检页用。 */
  static String describe(Context ctx) {
    int[] px = screenSize(ctx);
    return "Android " + Build.VERSION.SDK_INT
        + " (" + romTag() + ")"
        + " · " + Build.MODEL
        + " · " + px[0] + "×" + px[1]
        + " @" + (int) (density(ctx) * 160) + "dpi"
        + " · 短边 " + minWidthDp(ctx) + "dp";
  }
}
