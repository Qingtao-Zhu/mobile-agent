package com.deepseekharness.app.assistant;

import android.content.Context;
import android.provider.Settings;

import com.deepseekharness.app.DshaAccessibilityService;

/**
 * 无障碍服务「自愈」（2026-10-10 实测修的，这是 DSHA「时灵时不灵」的根因）。
 *
 * <p><b>病根</b>：Android 有个保护机制 —— 无障碍服务一旦被判定崩溃（进程被杀、OOM、
 * 强停、乃至系统重绑时序踩空），系统就把它丢进 {@code Crashed services} 名单，
 * 而且<b>之后再也不会自动重绑</b>。真机实测到的状态：
 * <pre>
 *   Enabled services:{{com.dsh.client/...DshaAccessibilityService}}   ← 开关是开的
 *   Bound services:{}                                                ← 但实际没绑
 *   Crashed services:{{com.dsh.client/...DshaAccessibilityService}}   ← 被判死过
 * </pre>
 * 于是：悬浮球挂不上、语音唤醒起不来、助手面板弹不出（只能退化成发通知），
 * 用户感知就是「这助手有点傻」。
 *
 * <p><b>验过的解法</b>（adb 实测有效）：把本服务从 {@code enabled_accessibility_services}
 * 里摘掉 → 等几百毫秒 → 再加回去，系统会重新绑定，{@code Crashed} 标记随之清空。
 * 关键是<b>必须真的改这个值</b>（置空字符串也算改动），光 delete 再 put 原值不触发刷新。
 *
 * <p><b>前提</b>：写 {@code Settings.Secure} 需要 {@code WRITE_SECURE_SETTINGS}，
 * 这个权限只有 ADB（{@code pm grant}）或系统签名能给。没有就老实返回 false，
 * 绝不硬来 —— 没权限时 {@link Settings.Secure#putString} 会静默失败，白折腾一趟。
 *
 * <p><b>克制</b>：只摘自己、只加自己，绝不碰列表里的其他无障碍服务
 * （用户可能还开着别的辅助功能，误伤它们的代价太大）。
 */
final class AccessibilityHealer {

  private static final String TAG = "DSHA/Heal";
  private static final String KEY = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;
  /** 无障碍服务的真实组件名（注意没有 .assistant 包）。 */
  private static final String ME =
      "com.dsh.client/com.deepseekharness.app.DshaAccessibilityService";

  /** 两次自愈之间的最小间隔：重绑本身有抖动，别把自己搞成抽风。 */
  private static final long COOLDOWN_MS = 60_000L;
  private static volatile long lastAttemptAt = 0L;

  private AccessibilityHealer() {}

  /** 能否自愈：得先拿到写 secure settings 的权。 */
  private static boolean canWrite(Context ctx) {
    try {
      return ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
          == android.content.pm.PackageManager.PERMISSION_GRANTED;
    } catch (Throwable ignored) {
      return false;
    }
  }

  /**
   * 检查并在必要时自愈。可以随便调（自带冷却）。
   *
   * @return 本次是否真的执行了重绑
   */
  static boolean healIfNeeded(Context ctx) {
    if (ctx == null) return false;
    // 服务好端端连着就别动 —— 重绑会让服务闪断一下，没必要
    if (DshaAccessibilityService.instance() != null) return false;
    if (!canWrite(ctx)) return false;

    long now = android.os.SystemClock.elapsedRealtime();
    if (now - lastAttemptAt < COOLDOWN_MS) return false;
    lastAttemptAt = now;

    try {
      String cur = Settings.Secure.getString(ctx.getContentResolver(), KEY);
      if (cur == null || !cur.contains(ME)) {
        // 开关压根没开 —— 那是用户的选择，不擅自打开
        android.util.Log.i(TAG, "本服务不在开启列表里，不动（尊重用户的开关）");
        return false;
      }
      android.util.Log.w(TAG, "服务没连上但开关是开的（多半被判崩溃了），自愈重绑");

      // 1) 把自己摘掉，但保留列表里其他服务
      String without = removeMe(cur);
      Settings.Secure.putString(ctx.getContentResolver(), KEY, without);
      // 2) 等系统消化这次变更。太短会被合并成"没变化"，刷新不出效果。
      try {
        Thread.sleep(700L);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
      // 3) 把自己加回去 → 系统重新绑定
      Settings.Secure.putString(ctx.getContentResolver(), KEY, addMe(without));
      android.util.Log.i(TAG, "已重新提交绑定：" + ME);
      return true;
    } catch (Throwable t) {
      android.util.Log.w(TAG, "自愈失败: " + t);
      return false;
    }
  }

  /** 从冒号分隔的服务列表里去掉自己，并把多余的冒号收拾干净。 */
  private static String removeMe(String list) {
    StringBuilder sb = new StringBuilder();
    for (String part : list.split(":")) {
      if (part == null || part.trim().isEmpty()) continue;
      if (ME.equals(part.trim())) continue;
      if (sb.length() > 0) sb.append(':');
      sb.append(part.trim());
    }
    return sb.toString();
  }

  /** 把自己追加回列表（列表为空时就不带前导冒号）。 */
  private static String addMe(String list) {
    String base = list == null ? "" : list.trim();
    if (base.isEmpty()) return ME;
    return base + ":" + ME;
  }
}
