package com.deepseekharness.app.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Vibrator;
import android.util.Log;

/**
 * DSHA 自己的闹钟响铃入口。
 *
 * <p><b>为什么不用标准 {@code AlarmClock.ACTION_SET_ALARM}</b>：这台机（EMUI 12 / HarmonyOS）
 * 把时钟 App 里处理 {@code SET_ALARM} 和 {@code SET_TIMER} 的 Activity 设成了
 * {@code com.android.alarm.permission.SET_ALARM} 保护 —— 那是签名级权限，第三方 App 拿不到。
 * 真机报错原文：
 * <pre>
 * Permission Denial: starting Intent { act=android.intent.action.SET_ALARM ... } from
 *   ProcessRecord{...com.dsh.client...} requires com.android.alarm.permission.SET_ALARM
 * </pre>
 * 所以「定闹钟」这条路在标准协议上是死的，DSHA 只能自己当闹钟：用
 * {@code AlarmManager.setAlarmClock} 挂一个到点唤醒自己的 PendingIntent，
 * 到点后由本类响铃 + 震动 + 弹全屏通知（锁屏上也会直接顶出来）。
 *
 * <p>好处是闭环全在 DSHA 手上：设了就是设了，能读出来、能取消、响的时候面板随后亮起。
 */
public final class DshaAlarmReceiver extends BroadcastReceiver {

  private static final String TAG = "DSHA/Alarm";
  public static final String ACTION_FIRED = "com.deepseekharness.app.action.ALARM_FIRED";
  public static final String ACTION_DISMISS = "com.deepseekharness.app.action.ALARM_DISMISS";
  /** 响多久就自己停。闹钟不会一直响下去，60 秒够吵醒人也还不太让人抓狂。 */
  private static final long RING_MS = 60_000L;
  private static final String CHANNEL = "dsha_alarm";

  @Override
  public void onReceive(Context ctx, Intent intent) {
    String act = intent == null ? null : intent.getAction();
    if (ACTION_DISMISS.equals(act)) {
      // 点「知道了」：把还在响的铃掐掉，通知本来就是 autoCancel
      stopRingtone();
      try {
        ((NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE))
            .cancel(4711);
      } catch (Throwable ignored) {
      }
      return;
    }
    String msg = intent == null ? null : intent.getStringExtra("msg");
    if (msg == null || msg.trim().isEmpty()) msg = "闹钟时间到了";
    // D-11（2026-10-11 日志隐私）：msg 是用户设置的提醒内容（Intent extra 穿进来的，
    // 例如"给妈妈打电话"）—— 它该在通知里显示给用户看，但不该留在 logcat。
    // 日志只记"有没有内容 + 多长"，足以定位是空提醒还是带内容的提醒。
    Log.i(TAG, LogFormat.alarmFired(msg));

    // 先出声：闹钟铃声走 ALARM 类型的默认音，静音/勿扰下也能靠震动顶过去
    playRingtone(ctx);
    try {
      Vibrator vb = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
      if (vb != null) vb.vibrate(new long[] {0, 900, 600, 900, 600, 900}, 0);
    } catch (Throwable t) {
      Log.w(TAG, "震动没起来: " + t);
    }
    showNotification(ctx, msg);
    // 别一直响：到点过 RING_MS 自己收声。用主线程的 Handler 就行，
    // 省得再挂一个停止用的闹钟，而闹钟的闹钟最容易赖着不走。
    try {
      new android.os.Handler(android.os.Looper.getMainLooper())
          .postDelayed(DshaAlarmReceiver::stopRingtone, RING_MS);
    } catch (Throwable t) {
      Log.w(TAG, "定时停铃没挂上: " + t);
    }
  }

  /**
   * 响铃。用 MediaPlayer 而不是 {@code Ringtone}：这台工程编出来的 android.jar 是裁剪过的，
   * 里面根本没有 {@code android.app.Ringtone}（编译报「找不到符号 / 类 Ringtone」），
   * 换个写法比动工具链省事。上一轮没关掉的播放器先掐掉，不然连着定两个闹钟会叠着响。
   */
  private static MediaPlayer player;

  private static synchronized void playRingtone(Context ctx) {
    stopRingtone();
    try {
      Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
      if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
      if (uri == null) return;
      MediaPlayer mp = new MediaPlayer();
      mp.setDataSource(ctx, uri);
      mp.setAudioStreamType(android.media.AudioManager.STREAM_ALARM);
      mp.setLooping(true);
      mp.setVolume(1f, 1f);
      mp.prepare();
      mp.start();
      player = mp;
    } catch (Throwable t) {
      Log.w(TAG, "铃声没响起来（不影响通知）: " + t);
    }
  }

  private static synchronized void stopRingtone() {
    try {
      if (player != null) {
        player.stop();
        player.release();
        player = null;
      }
    } catch (Throwable ignored) {
      player = null;
    }
  }

  private static void showNotification(Context ctx, String msg) {
    try {
      NotificationManager nm =
          (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
      if (nm == null) return;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        NotificationChannel ch =
            new NotificationChannel(
                CHANNEL,
                "DSHA 闹钟",
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("助手定的闹钟和倒计时到点从这里响");
        ch.enableVibration(true);
        nm.createNotificationChannel(ch);
      }

      // 点通知进面板：顺便把这句话带给助手，用户可以直接问「还有多久」
      Intent open = new Intent(ctx, DshaAssistActivity.class);
      open.putExtra("q", "闹钟时间到了：" + msg);
      open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
      PendingIntent piOpen =
          PendingIntent.getActivity(
              ctx,
              4711,
              open,
              PendingIntent.FLAG_UPDATE_CURRENT
                  | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                      ? PendingIntent.FLAG_IMMUTABLE
                      : 0));

      Intent dismiss = new Intent(ctx, DshaAlarmReceiver.class);
      dismiss.setAction(ACTION_DISMISS);
      dismiss.putExtra("msg", msg);
      PendingIntent piOff =
          PendingIntent.getBroadcast(
              ctx,
              4712,
              dismiss,
              PendingIntent.FLAG_UPDATE_CURRENT
                  | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                      ? PendingIntent.FLAG_IMMUTABLE
                      : 0));

      Notification.Builder b;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        b = new Notification.Builder(ctx, CHANNEL);
      } else {
        b = new Notification.Builder(ctx);
      }
      b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
          .setContentTitle("DSHA 闹钟")
          .setContentText(msg)
          .setCategory(Notification.CATEGORY_ALARM)
          .setAutoCancel(true)
          .setVisibility(Notification.VISIBILITY_PUBLIC)
          .setFullScreenIntent(piOpen, true)
          .addAction(android.R.drawable.ic_menu_close_clear_cancel, "知道了", piOff);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) b.setPriority(Notification.PRIORITY_MAX);
      nm.notify(4711, b.build());
    } catch (Throwable t) {
      Log.w(TAG, "通知没发出来: " + t);
    }
  }
}
