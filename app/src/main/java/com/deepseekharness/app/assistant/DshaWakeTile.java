package com.deepseekharness.app.assistant;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * 通知栏里那条「待命」条目 —— 快速唤醒的一条**不依赖无障碍事件**的路。
 *
 * <p>无障碍服务只能被动等事件（窗口变化、按键），但用户想要的常常是「随手一点就把助手叫出来」。
 * 常驻通知上挂一个动作按钮是系统给的现成入口：点按钮 → 广播 → 服务亮面板，
 * 全程不需要无障碍服务去截物理按键（音量键/电源键长按在这台 EMUI 上根本派发不到服务，
 * 见 {@link AssistantPanel} 的相关记录）。
 *
 * <p>收起面板时这条通知会挂回来（面板亮着时撤掉，免得挡着），
 * 所以调用方只要记住两件事：{@link AssistantPanel} 亮窗前 {@link #clear(Context)}、收起后
 * {@link #post(Context)}。
 *
 * <p><b>它的局限（别对它抱幻想）</b>：通知要 POST_NOTIFICATIONS 权限，
 * Android 13（API 33）起是运行时危险权限；没给权限这里静默跳过，
 * 唤醒路径退回到「桌面长按图标 / 下拉磁贴 / 无障碍快捷键」那几条。
 * 另外服务被杀掉时点按钮会走 {@link DshaAssistantActivity} 的兜底（发一条去开无障碍的通知）。
 */
/** public：要被 {@code com.deepseekharness.app} 包里的无障碍服务跨包调用。 */
public final class DshaWakeTile {

  /** 通知 id：固定，重复 post 是覆盖，不会在抽屉里堆一摞。 */
  static final int ID = 7101;
  static final String WAKE_ACTION = "com.dsh.client.action.WAKE";

  private DshaWakeTile() {}

  /** 面板亮着就撤、不在就挂回来。public：无障碍服务跨包调。 */
  public static void refresh(Context ctx) {
    if (ctx == null) return;
    try {
      if (AssistantPanel.visible()) clear(ctx);
      else post(ctx);
    } catch (Throwable ignored) {
    }
  }

  /** 挂一条「待命」通知（带「叫助手」按钮）。public：无障碍服务跨包调。 */
  public static void post(Context ctx) {
    if (ctx == null) return;
    try {
      // 没给通知权限就别硬来：硬发会被系统直接拒，还会在 logcat 里留一堆噪音
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
          && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
              != PackageManager.PERMISSION_GRANTED) {
        return;
      }
      NotificationManager nm =
          (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
      if (nm == null) return;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        NotificationChannel ch =
            new NotificationChannel("dsha_wake", "DSHA 助手待命",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("通知栏上直接叫助手的那个按钮");
        nm.createNotificationChannel(ch);
      }
      Intent wake = new Intent(ctx, DshaWakeReceiver.class);
      wake.setAction(WAKE_ACTION);
      int flags = PendingIntent.FLAG_UPDATE_CURRENT;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= 0x04000000; // FLAG_IMMUTABLE
      PendingIntent pi = PendingIntent.getBroadcast(ctx, ID, wake, flags);

      Notification.Builder b;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        b = new Notification.Builder(ctx, "dsha_wake");
      } else {
        b = new Notification.Builder(ctx);
      }
      b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
          .setContentTitle("DSHA 助手待命")
          .setContentText("点这里叫助手；也能下拉用磁贴，或长按桌面图标进菜单。")
          .setOngoing(false)
          .setAutoCancel(false)
          .setCategory(Notification.CATEGORY_SERVICE)
          .setContentIntent(pi)
          .addAction(android.R.drawable.ic_lock_idle_alarm, "叫助手", pi);
      nm.notify(ID, b.build());
    } catch (Throwable t) {
      android.util.Log.w("DSHA/WakeTile", "待命通知没挂上: " + t);
    }
  }

  /** 撤掉「待命」通知。public：无障碍服务跨包调。 */
  public static void clear(Context ctx) {
    if (ctx == null) return;
    try {
      NotificationManager nm =
          (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
      if (nm != null) nm.cancel(ID);
    } catch (Throwable ignored) {
    }
  }
}
