package com.deepseekharness.app.assistant;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import com.deepseekharness.app.R;

/**
 * 桌面小组件：一个格子只放**一个提醒单位**（用户原话：
 * 「如果它有 4×4 的格子，那么 2×1、2×2 或者 1×1 的尺寸，就可以用来显示一个提醒单位」）。
 *
 * <p><b>尺寸怎么办</b>：桌面加小组件时给的是 4×4，之后用户随手拖改成 2×1 / 1×1 也常见。
 * 与其准备一堆固定布局，不如在 {@link #onUpdate} 里读系统给的
 * {@code OPTION_APPWIDGET_MAX_WIDTH / HEIGHT}，按面积挑一个 layout：
 *
 * <pre>
 * ≥ 220dp 高（4×4 / 2×2）→ dsha_widget_big：标题 + 大号倒计时 + 正文 + 来源
 * ≥ 120dp 宽且不太高（2×1）→ dsha_widget_mid：一行图标 + 两行文字
 * 很小（1×1）              → dsha_widget_small：一行字，够看清就行
 * </pre>
 *
 * <p><b>点击行为</b>：整块点一下开助手并把这条提醒丢给它（{@code DshaAssistActivity} 收 extra q），
 * 所以「小组件上看一眼 → 点开聊两句 → 让它改/删」是一条闭环，不用回桌面重新叫。
 *
 * <p><b>刷新</b>：倒计时每秒都在变，但小组件不用每秒刷。三件事会触发刷新：
 * ① 数据变了（{@link ReminderStore#add} / {@code markDone} 都会调 {@link #refresh}）；
 * ② {@code updatePeriodMillis} 到点（manifest 里配的每分钟一次，只在新加小组件时系统兜底）；
 * ③ 自己用 AlarmManager 挂的定时刷新（见 {@link #scheduleTick}，让「还有 3 分钟」一直准）。
 */
public final class DshaReminderWidgetProvider extends AppWidgetProvider {

  private static final String TAG = "DSHA/Widget";

  public static final String ACTION_TICK = "com.dsh.client.action.REMINDER_TICK";
  public static final int ID = 4701;

  /** framework 是用 {@code Class.newInstance()} 造它的，所以这个构造必须 public 且无参。 */
  public DshaReminderWidgetProvider() {}

  // ---------------------------------------------------------------- 对外刷新入口

  /** 所有格子的倒计时重新算一遍。数据没变也安全（重复 update 不会闪）。 */
  public static void refresh(Context ctx) {
    if (ctx == null) return;
    try {
      ReminderStore.onMain(() -> {
        try {
          AppWidgetManager wm = AppWidgetManager.getInstance(ctx);
          int[] ids = wm.getAppWidgetIds(
              new android.content.ComponentName(ctx, DshaReminderWidgetProvider.class));
          if (ids == null || ids.length == 0) return;
          for (int id : ids) wm.updateAppWidget(id, views(ctx, id, wm));
          scheduleTick(ctx);
        } catch (Throwable t) {
          Log.w(TAG, "刷新小组件失败: " + t);
        }
      });
    } catch (Throwable ignored) {
    }
  }

  /** 挂一个每分钟的刷新心跳，把「还有 3 分钟」这种倒计时拧准（后台省着用：只在有提醒时才挂）。 */
  private static void scheduleTick(Context ctx) {
    try {
      if (ReminderStore.next(ctx) == null) return; // 没有提醒就别糟蹋电
      long now = System.currentTimeMillis();
      android.app.AlarmManager am =
          (android.app.AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
      if (am == null) return;
      Intent i = new Intent(ctx, DshaReminderWidgetProvider.class);
      i.setAction(ACTION_TICK);
      int flags = PendingIntent.FLAG_UPDATE_CURRENT;
      if (Build.VERSION.SDK_INT >= 23) flags |= 0x04000000;
      // 频率压到 1 分钟一次：比 1 分钟多的开销小，比 5 分钟够准
      am.set(android.app.AlarmManager.RTC, now + 60_000L,
          PendingIntent.getBroadcast(ctx, 991, i, flags));
    } catch (Throwable t) {
      Log.w(TAG, "挂小组件心跳失败: " + t);
    }
  }

  // ---------------------------------------------------------------- AppWidgetProvider

  @Override
  public void onUpdate(Context ctx, AppWidgetManager wm, int[] ids) {
    if (ids == null) return;
    for (int id : ids) {
      try {
        wm.updateAppWidget(id, views(ctx, id, wm));
      } catch (Throwable t) {
        Log.w(TAG, "更新单个格子失败: " + t);
      }
    }
    scheduleTick(ctx);
  }

  /**
   * 这块是 BroadcastReceiver 的 onReceive 前半段：系统给 {@code ACTION_TICK} 时
   * 也会顺手 {@code onUpdate}，倒计时就自己往前走了。
   */
  @Override
  public void onReceive(Context ctx, Intent intent) {
    super.onReceive(ctx, intent);
    try {
      if (intent != null && ACTION_TICK.equals(intent.getAction())) refresh(ctx);
    } catch (Throwable ignored) {
    }
  }

  // ---------------------------------------------------------------- 视图

  private static RemoteViews views(Context ctx, int id, AppWidgetManager wm) {
    Bundle opts = wm.getAppWidgetOptions(id);
    int w = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, -1);
    int h = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, -1);
    boolean big = h >= 220 || w >= 300;
    boolean mid = !big && (w >= 200 || h >= 120);
    int layout = big ? R.layout.dsha_widget_big : mid ? R.layout.dsha_widget_mid : R.layout.dsha_widget_small;
    RemoteViews rv = new RemoteViews(ctx.getPackageName(), layout);

    int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? 0x04000000 : 0);

    ReminderStore.Reminder r = ReminderStore.next(ctx);
    if (r == null) {
      rv.setTextViewText(R.id.widget_glyph, "·");
      rv.setTextViewText(R.id.widget_title, "暂无提醒");
      rv.setTextViewText(R.id.widget_clock, "说「提醒我…」试试");
      rv.setTextViewText(R.id.widget_body, "");
      rv.setViewVisibility(R.id.widget_body, View.GONE);
      if (big) rv.setViewVisibility(R.id.widget_card, View.GONE);
      rv.setOnClickPendingIntent(R.id.widget_root, click(ctx, null, flags));
      return rv;
    }

    String title = r.title == null || r.title.isEmpty() ? "提醒" : r.title;
    String body = r.body == null ? "" : r.body;
    // kind → 圆标里那个字（不引图标包，一个 TextView 当图标最省事，也好调颜色）
    String glyph = ReminderStore.KIND_BUS.equals(r.kind) ? "车"
        : ReminderStore.KIND_TODO.equals(r.kind) ? "!" : "时";

    rv.setTextViewText(R.id.widget_glyph, glyph);
    rv.setTextViewText(R.id.widget_title, title);
    rv.setTextViewText(R.id.widget_clock, ReminderStore.countdown(r));
    rv.setTextViewText(R.id.widget_body, body);
    if (big) {
      rv.setViewVisibility(R.id.widget_card, View.VISIBLE);
      rv.setViewVisibility(R.id.widget_body, body.isEmpty() ? View.GONE : View.VISIBLE);
    } else {
      rv.setViewVisibility(R.id.widget_body, View.GONE);
    }
    rv.setOnClickPendingIntent(R.id.widget_root, click(ctx, r, flags));
    return rv;
  }

  /** 点一下：开助手，并把这条提醒原文塞进去（面板会把它当问题直接答）。 */
  private static PendingIntent click(Context ctx, ReminderStore.Reminder r, int flags) {
    Intent i = new Intent(ctx, DshaAssistActivity.class);
    if (r != null) {
      i.putExtra("q", r.title + (r.body.isEmpty() ? "" : "：" + r.body) + " 还有多久");
    } else {
      i.putExtra("q", "有哪些提醒");
    }
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    return PendingIntent.getActivity(ctx, 4711, i, flags);
  }
}
