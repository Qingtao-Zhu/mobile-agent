package com.deepseekharness.app.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 提醒单位（2026-10-08 起）。**小组件、助手指令、通知解析三处共用同一份数据** ——
 * 只要往这里写了一条形，桌面那块 4×4 的小组件立刻就能显示它。
 *
 * <p><b>为什么不做数据库 / Room</b>：提醒就那么几条（最多几十条），
 * 一个 {@code SharedPreferences} 里的 JSON 数组足够，且不带 OnUpgrade  nightmare、
 * 不需要迁移脚本。代价是每次读写都要整个数组 round-trip —— 这个量级无所谓。
 *
 * <p><b>到点怎么响</b>：复用 {@link com.deepseekharness.app.DshaAlarmReceiver} 那套
 * （{@code AlarmManager.setAlarmClock} 在本机是唯一走得通的闹钟通道，
 * 标准 {@code ACTION_SET_ALARM} 被华为保护了）。每条提醒 add 的时候顺手挂一个
 * 到点的 PendingIntent；数据在 store 里，到点时由 {@link DshaReminderWidgetProvider}
 * 和 {@link AssistantPanel} 一起读最新状态。
 *
 * <p><b>key 是什么</b>：公交那种「同一辆车刷屏式推通知」的通知必须按 key 去重更新，
 * 否则每来一条通知就多一行，桌面小组件会被刷成流水账。key 一般取「包名 + 线路/车次」。
 */
public final class ReminderStore {

  private static final String TAG = "DSHA/Reminder";
  private static final String PREF = "dsha_reminders";
  private static final String KEY_JSON = "items";

  public static final String KIND_BUS = "bus";
  public static final String KIND_TODO = "todo";
  public static final String KIND_TIMER = "timer";

  private ReminderStore() {}

  /** 一条提醒。public 字段比 getter 好使（跨包用，别在调用点写一堆 getXxx()）。 */
  public static final class Reminder {
    public long id;
    /** 去重键，同一辆车/同一件事重复写入会覆盖同一条，见 {@link #add}。 */
    public String key;
    public String kind = KIND_TODO;
    public String title = "";
    public String body = "";
    /** 到期时刻（epoch millis）。 */
    public long at;
    public boolean done;

    @Override
    public String toString() {
      return (key == null ? "" : key + "|") + kind + "|" + title + "|" + at;
    }
  }

  // ---------------------------------------------------------------- 写

  /**
   * 写一条提醒（同 key 覆盖旧的那条）。
   *
   * @param key 传 null = 不去重，每次都新加一条
   */
  public static void add(Context ctx, String kind, String key, String title, String body, long at) {
    if (ctx == null) return;
    try {
      List<Reminder> all = read(ctx);
      if (key != null) {
        for (Reminder r : all) {
          if (key.equals(r.key)) {
            r.kind = kind == null ? r.kind : kind;
            r.title = title == null ? r.title : title;
            r.body = body == null ? r.body : body;
            r.at = at;
            r.done = false;
            write(ctx, all);
            arm(ctx, r);
            DshaReminderWidgetProvider.refresh(ctx);
            return;
          }
        }
      }
      Reminder r = new Reminder();
      r.id = System.currentTimeMillis();
      r.key = key;
      r.kind = kind == null ? KIND_TODO : kind;
      r.title = title == null ? "" : title;
      r.body = body == null ? "" : body;
      r.at = at;
      r.done = false;
      all.add(r);
      write(ctx, all);
      arm(ctx, r);
      DshaReminderWidgetProvider.refresh(ctx);
    } catch (Throwable t) {
      Log.w(TAG, "写提醒失败: " + t);
    }
  }

  /** 划掉 / 到点之后把这条标成已办，小组件就不会再显示它。 */
  public static void markDone(Context ctx, long id) {
    try {
      List<Reminder> all = read(ctx);
      boolean changed = false;
      for (Reminder r : all) {
        if (r.id == id) {
          r.done = true;
          changed = true;
        }
      }
      if (changed) {
        write(ctx, all);
        DshaReminderWidgetProvider.refresh(ctx);
      }
    } catch (Throwable t) {
      Log.w(TAG, "标已办失败: " + t);
    }
  }

  public static void remove(Context ctx, long id) {
    try {
      List<Reminder> all = read(ctx);
      for (int i = all.size() - 1; i >= 0; i--) {
        if (all.get(i).id == id) all.remove(i);
      }
      write(ctx, all);
      DshaReminderWidgetProvider.refresh(ctx);
    } catch (Throwable t) {
      Log.w(TAG, "删提醒失败: " + t);
    }
  }

  /** 到期提醒已经响过了：把过期且还没完成的清掉，别让小组件永远显示一个黑历史。 */
  private static void sweepExpired(List<Reminder> all) {
    long now = System.currentTimeMillis();
    for (int i = all.size() - 1; i >= 0; i--) {
      Reminder r = all.get(i);
      if (!r.done && r.at < now - 30L * 60 * 1000) all.get(i).done = true;
    }
  }

  // ---------------------------------------------------------------- 读

  /** 所有还没办完的，按时间升序（最着急的在最前面，小组件取 [0]）。 */
  public static List<Reminder> list(Context ctx) {
    try {
      List<Reminder> all = read(ctx);
      sweepExpired(all);
      List<Reminder> live = new ArrayList<>();
      for (Reminder r : all) if (!r.done) live.add(r);
      live.sort(Comparator.comparingLong(r -> r.at));
      return live;
    } catch (Throwable t) {
      Log.w(TAG, "读提醒失败: " + t);
      return Collections.emptyList();
    }
  }

  /** 最着急的那一条（小组件显示的就是它 —— 一个格子只放得下一个提醒单位）。 */
  public static Reminder next(Context ctx) {
    List<Reminder> l = list(ctx);
    return l.isEmpty() ? null : l.get(0);
  }

  /**
   * 小组件那一行的文案。
   *
   * <p>「还有 3 分钟」这种是给 {@code 2×1/1×1} 用的短句；
   * {@code 4×4} 有空间，用长句（标题 + 剩余 + 正文）由 provider 自己拼。
   */
  public static String countdown(Reminder r) {
    if (r == null) return "";
    long ms = r.at - System.currentTimeMillis();
    if (ms <= 0) return "已到点";
    long s = ms / 1000;
    long m = s / 60;
    if (m < 1) return "不到 1 分钟";
    if (m < 60) return m + " 分钟";
    return String.format(Locale.CHINA, "%d 小时 %d 分", m / 60, m % 60);
  }

  // ---------------------------------------------------------------- 存储

  private static List<Reminder> read(Context ctx) {
    ArrayList<Reminder> out = new ArrayList<>();
    try {
      SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
      String json = sp.getString(KEY_JSON, "");
      if (json == null || json.isEmpty()) return out;
      JSONArray arr = new JSONArray(json);
      for (int i = 0; i < arr.length(); i++) {
        JSONObject o = arr.getJSONObject(i);
        Reminder r = new Reminder();
        r.id = o.optLong("id", System.currentTimeMillis() + i);
        r.key = o.optString("key", null);
        r.kind = o.optString("kind", KIND_TODO);
        r.title = o.optString("title", "");
        r.body = o.optString("body", "");
        r.at = o.optLong("at", 0);
        r.done = o.optBoolean("done", false);
        out.add(r);
      }
    } catch (Throwable t) {
      Log.w(TAG, "解析提醒失败: " + t);
    }
    return out;
  }

  private static void write(Context ctx, List<Reminder> all) {
    try {
      JSONArray arr = new JSONArray();
      for (Reminder r : all) {
        JSONObject o = new JSONObject();
        o.put("id", r.id);
        o.put("key", r.key == null ? "" : r.key);
        o.put("kind", r.kind);
        o.put("title", r.title);
        o.put("body", r.body);
        o.put("at", r.at);
        o.put("done", r.done);
        arr.put(o);
      }
      ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
          .edit()
          .putString(KEY_JSON, arr.toString())
          .apply();
    } catch (Throwable t) {
      Log.w(TAG, "存提醒失败: " + t);
    }
  }

  // ---------------------------------------------------------------- 到点

  /** 给一条提醒挂到点闹钟（复用 DshaAlarmReceiver 那条通道）。 */
  static void arm(Context ctx, Reminder r) {
    try {
      long at = r.at;
      if (at <= System.currentTimeMillis()) at = System.currentTimeMillis() + 1500L;
      Intent i = new Intent(ctx, DshaAlarmReceiver.class);
      i.setAction(DshaAlarmReceiver.ACTION_FIRED);
      i.putExtra("msg", r.title + (r.body.isEmpty() ? "" : "：" + r.body));
      int flags = PendingIntent.FLAG_UPDATE_CURRENT;
      if (Build.VERSION.SDK_INT >= 23) flags |= 0x04000000; // FLAG_IMMUTABLE（裁剪 jar 里没有这个常量）
      PendingIntent pi = PendingIntent.getBroadcast(ctx, (int) (r.id & 0x7fffffff), i, flags);
      android.app.AlarmManager am = (android.app.AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
      if (am == null) return;
      if (Build.VERSION.SDK_INT >= 21) {
        am.setAlarmClock(new android.app.AlarmManager.AlarmClockInfo(at, pi), pi);
      } else {
        am.set(android.app.AlarmManager.RTC_WAKEUP, at, pi);
      }
    } catch (Throwable t) {
      Log.w(TAG, "挂到点闹钟失败: " + t);
    }
  }

  /**
   * 到点那条通知（跟闹钟共用一个通道）：面板已经被收走了就往通知栏丢一条，
   * 别让用户因为面板不在就漏掉提醒。
   */
  public static void notifyDue(Context ctx, String msg) {
    if (ctx == null || msg == null) return;
    try {
      NotificationManager nm =
          (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        NotificationChannel ch =
            new NotificationChannel("dsha_remind", "DSHA 提醒", NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(ch);
      }
      Intent open = new Intent(ctx, DshaAssistActivity.class);
      open.putExtra("q", msg);
      open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      int flags = PendingIntent.FLAG_UPDATE_CURRENT;
      if (Build.VERSION.SDK_INT >= 23) flags |= 0x04000000;
      PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, flags);
      Notification n =
          new Notification.Builder(ctx, "dsha_remind")
              // 这台工程的 android.jar 是裁剪版：stat_sys_vibrate 这类符号根本没有，
              // 全项目统一用 ic_lock_idle_alarm（也是纯白剪影，状态栏上能显示）。
              .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
              .setContentTitle("DSHA 提醒")
              .setContentText(msg)
              .setAutoCancel(true)
              .setContentIntent(pi)
              .build();
      nm.notify(4723, n);
    } catch (Throwable t) {
      Log.w(TAG, "到点通知失败: " + t);
    }
  }

  /** 主线程跑一小段（小组件刷新要在主线程）。 */
  static void onMain(Runnable r) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      r.run();
    } else {
      new Handler(Looper.getMainLooper()).post(r);
    }
  }
}
