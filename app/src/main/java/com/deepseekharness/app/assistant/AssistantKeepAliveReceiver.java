package com.deepseekharness.app.assistant;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 助手常驻的 Doze 兜底（2026-10-10 加）。
 *
 * <p><b>为什么要这个</b>：悬浮球、语音唤醒、助手面板三样东西全靠
 * {@code DshaAccessibilityService}。实测发现它一旦被系统判崩溃，就会被丢进
 * {@code Crashed services} 名单且<b>不再自动重绑</b>，于是三样全废、助手"变傻"。
 *
 * <p>而自愈逻辑本身跑在 App 进程里 —— 进程一死，谁来执行自愈？这个 receiver
 * 就是答案：用 {@code AlarmManager.setAndAllowWhileIdle} 挂一条<b>由系统持有</b>的
 * 周期性闹钟。进程活着时它几乎无事可做（服务好着就直接返回）；进程被回收后，
 * 闹钟到点照样能把进程拉起来，执行自愈重绑，把三样东西救回来。
 *
 * <p>选用 {@code setAndAllowWhileIdle} 而不是普通 set：普通闹钟进 Doze 会被无限推迟，
 * 用户第二天拿起手机时助手还是残废的。代价是系统会把间隔拉长到 ~9 分钟 —— 对
 * "常驻保活"这个目的足够，也不至于为了秒级精度把电池烧穿。
 *
 * <p><b>诚实说明</b>：用户主动"强行停止"App 时，Android 会一并清掉该 App 的闹钟，
 * 这条兜底也失效（这是系统级行为，任何 App 都绕不过）。除此之外（OOM 回收、
 * 系统省电杀后台、长时间闲置）都能自愈。
 */
public final class AssistantKeepAliveReceiver extends BroadcastReceiver {

  public static final String ACTION = "com.deepseekharness.app.ASSISTANT_KEEPALIVE";
  private static final int REQ_CODE = 77;
  /** 巡检间隔。Doze 下会被系统拉长到 ~9 分钟，可接受。 */
  private static final long INTERVAL_MS = 5 * 60_000L;

  /** 排下一次巡检（一次性闹钟，每次触发后自己重排）。 */
  public static void schedule(Context ctx) {
    try {
      AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
      if (am == null) return;
      am.setAndAllowWhileIdle(
          AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + INTERVAL_MS, pending(ctx));
    } catch (Throwable ignored) {
    }
  }

  public static void cancel(Context ctx) {
    try {
      AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
      if (am != null) am.cancel(pending(ctx));
    } catch (Throwable ignored) {
    }
  }

  private static PendingIntent pending(Context ctx) {
    Intent i =
        new Intent(ctx.getApplicationContext(), AssistantKeepAliveReceiver.class)
            .setAction(ACTION);
    return PendingIntent.getBroadcast(
        ctx.getApplicationContext(),
        REQ_CODE,
        i,
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  @Override
  public void onReceive(Context ctx, Intent intent) {
    try {
      // 1) 无障碍服务没连上就自愈重绑（写 secure settings 需要 ADB 授予的权限，
      //    没有权限时 healIfNeeded 自己会判断并返回 false，不硬来）
      AccessibilityHealer.healIfNeeded(ctx);
      // 2) 语音唤醒开着但服务掉了，补起
      if (AssistantWakeService.enabled(ctx)) AssistantWakeService.startIfEnabled(ctx);
      // 3) 悬浮球：要等服务连上后有 host 才能挂，这里不直接挂，
      //    交给 DshaAccessibilityService.onServiceConnected 处理。
    } catch (Throwable ignored) {
    } finally {
      // 无论这次干成没干成都排下一次 —— 常驻兜底不能断链
      schedule(ctx);
    }
  }
}
