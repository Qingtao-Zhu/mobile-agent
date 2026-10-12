package com.deepseekharness.app.assistant;

import android.app.Notification;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 读通知栏（2026-10-07 起，用户原话「读通知栏内容，这些可以做一下」）。
 *
 * <p><b>为什么又要一个 Service</b>：读通知不是 {@code requestPermissions} 能拿到的权限，
 * 而是**系统绑定型服务** —— 用户去「设置 → 通知 → 通知使用权」里把 DSHA 勾上，
 * 系统才会 {@code bindService} 到 {@link DshaNotificationListener}。
 * 在此之前 {@link #isConnected()} 一直是 false，助手会照实回一句「还没开通知使用权」，
 * 而不是假装读了又答不上来。
 *
 * <p><b>隐私口径跟读屏完全一致</b>（{@link AssistantGuard}）：银行 / 支付 / 密码管理类的通知
 * 一条都不进缓存、不进模型 —— 这些通知里往往有尾号和验证码。
 * 微信在白名单里（用户拍板要能读），所以微信通知是能念的。
 *
 * <p><b>为什么自己留一份摘要缓存</b>：会话刚连上时 {@code getActiveNotifications()}
 * 能补上；但设备重启 / 授权被临时摘掉之后，面板上再问「谁在找我」也至少能答上最近那几条。
 * 缓存有上限（{@code KEEP}，60 条），只留文本摘要，不存原始对象。
 */
public final class DshaNotificationListener extends NotificationListenerService {

  private static final String TAG = "DSHA/Notify";

  /** 缓存够用就行：面板一次只念 8 条，60 条是留给"刚才那会儿"回看和断连兜底。 */
  private static final int KEEP = 60;

  private static volatile boolean connected;
  /** 最新在最前（对外给的是不可变副本，免得外部改到内部结构）。 */
  private static volatile List<String> cache;

  // ---------------------------------------------------------------- 对外状态

  /** 通知使用权开了没有（系统有没有把本服务绑上来）。 */
  public static boolean isConnected() {
    return connected;
  }

  /** 缓存里有没有条目（面板用它决定要不要挂「谁在找我」那颗 chip）。 */
  static boolean hasAny() {
    List<String> c = cache;
    return c != null && !c.isEmpty();
  }

  /** 缓存条数。 */
  static int size() {
    List<String> c = cache;
    return c == null ? 0 : c.size();
  }

  /**
   * 念出通知栏摘要。
   *
   * @param want 最多念几条（面板一屏放得下的条数）
   * @return 一句话摘要；没通知就返回 null（调用方自己回「现在没通知」）
   */
  static String summarize(int want) {
    List<String> c = cache;
    if (c == null || c.isEmpty()) return null;
    StringBuilder sb = new StringBuilder();
    int n = Math.max(1, Math.min(want, c.size()));
    sb.append("通知栏 ").append(c.size()).append(" 条，");
    sb.append(c.size() > n ? "最近的 " : "").append(n).append(" 条是：");
    for (int i = 0; i < n; i++) {
      sb.append('\n').append('·').append(' ').append(clip(c.get(i), 46));
    }
    if (c.size() > n) sb.append("\n（还有 ").append(c.size() - n).append(" 条没念）");
    return sb.toString();
  }

  /** 喂给模型那条（不带"通知栏 N 条"这样的引导语，只给条目）。 */
  static String digest(int want) {
    List<String> c = cache;
    if (c == null || c.isEmpty()) return "";
    StringBuilder sb = new StringBuilder();
    int n = Math.max(1, Math.min(want, c.size()));
    for (int i = 0; i < n; i++) sb.append('\n').append('·').append(' ').append(c.get(i));
    return sb.toString();
  }

  // ---------------------------------------------------------------- 系统回调

  @Override
  public void onListenerConnected() {
    connected = true;
    try {
      // 连上那一刻把当前在屏的通知补进缓存（本服务自己就是 Context）
      refresh(this, getActiveNotifications());
    } catch (Throwable t) {
      Log.w(TAG, "连上后没刷到通知: " + t);
    }
  }

  /** 用户手动关掉授权 / 系统临时摘掉，统一当成「暂时读不到」——别让面板拿着旧缓存瞎答。 */
  @Override
  public void onListenerDisconnected() {
    connected = false;
  }

  @Override
  public void onNotificationPosted(StatusBarNotification n) {
    if (n == null) return;
    try {
      push(n);
    } catch (Throwable t) {
      Log.w(TAG, "收一条通知没存住: " + t);
    }
  }

  @Override
  public void onNotificationRemoved(StatusBarNotification n) {
    if (n == null) return;
    try {
      drop(n);
    } catch (Throwable t) {
      Log.w(TAG, "删一条通知没删干净: " + t);
    }
  }

  // ---------------------------------------------------------------- 缓存维护

  private void refresh(Context ctx, StatusBarNotification[] all) {
    if (all == null || all.length == 0) {
      cache = Collections.emptyList();
      return;
    }
    ArrayList<String> list = new ArrayList<>();
    for (StatusBarNotification n : all) {
      if (n == null) continue;
      String line = line(ctx, n);
      if (line != null) list.add(line);
    }
    trim(list);
    Collections.reverse(list); // 系统给的是最旧在前，缓存要最新在前
    cache = Collections.unmodifiableList(list);
  }

  private void push(StatusBarNotification n) {
    String line = line(this, n);
    if (line == null) return;
    ArrayList<String> list = new ArrayList<>();
    List<String> old = cache;
    if (old != null) list.addAll(old);
    list.add(0, line);
    trim(list);
    cache = Collections.unmodifiableList(list);
    // 顺手扫一眼这是不是公交到站通知 —— 是的话落一条提醒单位，
    // 桌面那块 4×4 小组件显示的「还有几分钟」就是它（2026-10-08 用户要的）。
    try {
      transit(n, line);
    } catch (Throwable t) {
      Log.w(TAG, "公交提醒没记下来: " + t);
    }
  }

  private static final java.util.regex.Pattern P_BUS_MIN =
      java.util.regex.Pattern.compile("还有\\s*([0-9]{1,2})\\s*分|([0-9]{1,2})\\s*分钟后?到站|"
          + "即将到站|马上到站|即将进站|还有\\s*([0-9]{1,2})\\s*站");

  /** 公交类 App 的包名前缀（拿不到更全的名单，够用就行：命不中就退成「不记」）。 */
  private static boolean transitPkg(String pkg) {
    if (pkg == null) return false;
    return pkg.contains("chelaile")
        || pkg.contains("baidu.map")
        || pkg.contains("autonavi")
        || pkg.contains("amap")
        || pkg.contains("bus");
  }

  /**
   * 一条公交到站通知 → 提醒单位。
   *
   * <p>只认「还有 N 分钟」「即将到站」这种能推算出到站时刻的表述；
   * 光有个站数不记 —— 站数换算成分钟的估算是拍的，宁可不记也别给一个错的倒计时。
   */
  private void transit(StatusBarNotification n, String line) {
    String pkg = n.getPackageName();
    if (AssistantGuard.isProtected(pkg)) return;
    if (!transitPkg(pkg)) return;
    java.util.regex.Matcher m = P_BUS_MIN.matcher(line);
    if (!m.find()) return;
    String hit = m.group();
    String digit = m.group(1) != null ? m.group(1) : m.group(2);
    long minutes = 1;
    if (digit != null) {
      try {
        minutes = Integer.parseInt(digit);
      } catch (Throwable ignored) {
      }
    } else if (m.group(3) != null) {
      // 只有站数：不猜时间，直接当成「马上到」，让小组件显示"已到点"不好看，
      // 所以这种也跳过，只认分钟。
      return;
    }
    ReminderStore.add(
        this,
        ReminderStore.KIND_BUS,
        "bus|" + pkg + "|" + (line.length() > 24 ? line.substring(0, 24) : line),
        "车还有 " + minutes + " 分钟",
        hit,
        System.currentTimeMillis() + minutes * 60 * 1000L);
  }

  /** 通知被划掉时按「应用名 + 标题」找回去删掉；删不掉就留着（最多念到一条已划掉的）。 */
  private void drop(StatusBarNotification n) {
    List<String> old = cache;
    if (old == null || old.isEmpty()) return;
    String line = line(this, n);
    if (line == null) return;
    String key = line.substring(0, Math.min(line.length(), 48));
    ArrayList<String> list = new ArrayList<>();
    boolean hit = false;
    for (String s : old) {
      if (key.equals(s)) hit = true;
      else list.add(s);
    }
    if (hit) cache = Collections.unmodifiableList(list);
  }

  private static void trim(ArrayList<String> list) {
    while (list.size() > KEEP) list.remove(list.size() - 1);
    list.trimToSize();
  }

  /**
   * 一条通知 → 一行可读文本；敏感应用返回 null（整条丢掉，连标题都不留）。
   * 格式：{@code 应用名 标题：正文}（标题正文都空就退成 ticker）。
   */
  private static String line(Context ctx, StatusBarNotification n) {
    try {
      String pkg = n.getPackageName();
      if (AssistantGuard.isProtected(pkg)) return null;
      CharSequence title = null;
      CharSequence body = null;
      try {
        // 走 n.getNotification().extras，而不是 n.getExtra()：这台工程编出来的
        // android.jar 是**裁剪版**，StatusBarNotification.getExtra() / getTickerText()
        // 在 javac 眼里根本不存在（编译报「找不到符号」）。
        // Notification.extras（public Bundle 字段）和 tickerText 都还在，够用。
        Notification notif = n.getNotification();
        if (notif != null) {
          Bundle ex = notif.extras;
          if (ex != null) {
            title = ex.getCharSequence(Notification.EXTRA_TITLE);
            body = ex.getCharSequence(Notification.EXTRA_TEXT);
          }
          if ((title == null || title.length() == 0) && notif.tickerText != null) {
            title = notif.tickerText;
          }
        }
      } catch (Throwable ignored) {
      }
      String t = title == null ? "" : title.toString().trim();
      String b = body == null ? "" : body.toString().trim();
      if (t.isEmpty() && b.isEmpty()) return null;
      return label(ctx, pkg) + ' ' + t + (b.isEmpty() ? "" : "：" + b);
    } catch (Throwable t) {
      Log.w(TAG, "拆一条通知失败: " + t);
      return null;
    }
  }

  /** 包名 → 应用名（拿不到就退回包名，面板上用不着精确到字面）。 */
  private static String label(Context ctx, String pkg) {
    try {
      if (pkg == null || pkg.isEmpty()) return "通知";
      PackageManager pm = ctx.getPackageManager();
      if (pm != null) {
        ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
        CharSequence name = info == null ? null : pm.getApplicationLabel(info);
        if (name != null && name.length() > 0) return name.toString();
      }
    } catch (Throwable ignored) {
    }
    return pkg;
  }

  private static String clip(String s, int max) {
    if (s == null) return "";
    String v = s.trim();
    return v.length() <= max ? v : v.substring(0, max - 1) + "…";
  }
}
