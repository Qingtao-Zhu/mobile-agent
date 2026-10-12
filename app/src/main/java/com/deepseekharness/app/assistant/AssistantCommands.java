package com.deepseekharness.app.assistant;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.StatFs;
import android.provider.AlarmClock;
import android.view.KeyEvent;

import com.deepseekharness.app.DshaAccessibilityService;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 面板的**本地指令**：像 Siri 那样，一部分话根本不用问模型，本机就能秒回、断网也能用。
 *
 * <p>命中就 {@link #run} 返回 {@code handled=true} 和一句可直接显示的答复，面板不再发网络请求；
 * 没命中返回 {@code handled=false}，照旧交给模型。能做的：
 * <ul>
 *   <li>时间 / 日期 / 星期
 *   <li>四则运算（含括号、乘方、取余）
 *   <li>打开应用（按名字模糊匹配已安装应用）/ 打开网址
 *   <li>打电话、发短信
 *   <li>网页搜索
 *   <li>设闹钟、倒计时、提醒
 *   <li>系统键：回桌面 / 返回 / 最近任务 / 锁屏 / 通知栏 / 快捷设置
 *   <li>媒体键：播放暂停 / 下一首 / 上一首
 * </ul>
 *
 * <p>为什么本地做：① 零延迟，说「现在几点」要等 3 秒网络返回很傻；② 断网可用；
 * ③ 不把「打开微信」这种意图丢给模型去猜。
 */
final class AssistantCommands {

  private static final String TAG = "DSHA/Commands";

  /** 执行结果。 */
  static final class Outcome {
    final boolean handled;
    /** 面板上直接显示的短句。 */
    final String reply;
    /** 做完要不要把面板收起来（打开应用/拨号这类，面板挡着没意义）。 */
    final boolean dismiss;
    /**
     * 任务型回答（后续操作跑起来了）：面板<b>别自动收</b>，用户要看执行进度；
     * 用户自己拖把手收面板时，执行器照旧停手（hide() 里的 cancelAll 仍生效）。
     */
    final boolean keepOpen;

    Outcome(boolean handled, String reply, boolean dismiss) {
      this(handled, reply, dismiss, false);
    }

    Outcome(boolean handled, String reply, boolean dismiss, boolean keepOpen) {
      this.handled = handled;
      this.reply = reply;
      this.dismiss = dismiss;
      this.keepOpen = keepOpen;
    }

    static Outcome no() {
      return new Outcome(false, null, false);
    }
  }

  private AssistantCommands() {}

  private static final Pattern P_TIME =
      Pattern.compile("(现在|目前)?\\s*(几点|什么时间|什么时辰)");
  private static final Pattern P_DATE =
      Pattern.compile("(今天|明天|后天)?\\s*(几号|几日|日期|星期几|周几)");
  private static final Pattern P_CALC =
      Pattern.compile("^[\\s0-9+\\-*/().%^*×÷=，,、算一下计算等于多少是]*$");
  private static final Pattern P_CALC_STRIP =
      Pattern.compile("(算一下|计算|算算|等于|是多少|等于多少|[，,、\\s])");
  // 2026-10-07 放宽：以前只认「打开X」，用户一句「帮我打开X / 打开一下X」就直接落空，
  // 落到模型兜底，模型又编出「需要在应用列表里面找」这种让用户自己动手的废话
  // （用户原话："我这手机没有应用列表呀！" —— 助手该自己查表打开，不该把活推给人）。
  private static final Pattern P_OPEN =
      Pattern.compile(
          "^(帮我|给我|去|请|麻烦)?\\s*(打开|启动|运行|进入|开|开启)\\s*(一下|一下下|个|下)?\\s*[:：，,、\\s]*(.+)$");
  /** 打开应用时目标尾部的口语音气词，命中就剥掉，别让它变成查不到应用的理由。 */
  private static final Pattern P_TAIL_NOISE =
      Pattern.compile("(啊|吧|呢|了|嘛|哦|呗|行不行|好不好|可以吗|一下)+$");
  /** 2026-10-07 补：用户抱怨助手让他自己"去应用列表里找"，但这台手机上他根本不想翻。
   *  说一句「有哪些应用 / 应用列表」就直接把真机上装的可启动应用报出来。 */
  private static final Pattern P_APP_LIST =
      Pattern.compile(
          "^(.*(有哪些|哪些|什么)应用(列表)?|(打开|看看|列出|报一下|告诉我).{0,4}应用(列表)?)"
              + "|(应用列表|装了哪些应用|所有的应用)$");
  /** 「有哪些技能」这类自述。 */
  private static final Pattern P_SKILL_LIST =
      Pattern.compile("^(?:(?:有哪些|哪些|什么|会些|会什么|会哪些|列出|列一下|说说|讲讲|报一下|报一报)"
              + "(?:的)?(?:技能|技巧|招式|skill)s?|技能(?:列表|清单|本子)?|所有技能|我的技能)$",
          Pattern.CASE_INSENSITIVE);
  /** 「忘掉技能 付款提醒」。 */
  private static final Pattern P_SKILL_DELETE =
      Pattern.compile("^(?:忘掉|忘记|删掉|删除|去掉|丢掉|不要|撤掉)(?:那个|这个|那|这)?"
              + "(?:技能|技巧|招式|skill)?\\s*[:：，,]?\\s*(.+)$",
          Pattern.CASE_INSENSITIVE);
  /**
   * 「建个技能：这单什么时候付，先报金额和到期日」。
   *
   * <p>动词那一段故意放宽（建/做个/创建/添加/来个/写个/记个/存个/定…），
   * 但后面**必须跟"技能/技巧/招式/skill"**，不然「记一下」「做一下」这种日常话会被误当成建技能。
   */
  private static final Pattern P_SKILL_CREATE =
      Pattern.compile("^(?:帮我|给我|请|麻烦)?\\s*(?:新\\s*建|建|做|创\\s*建|添\\s*加|加|来|写|记|存|留|定)"
              + "(?:一\\s*个|个)?\\s*(?:新)?\\s*(?:技能|技巧|招式|skill)\\s*[:：，,]?\\s*(.+)$",
          Pattern.CASE_INSENSITIVE);
  /** 静音 / 震动 / 恢复响铃（MODIFY_AUDIO_SETTINGS，不需要弹权限框）。
   *
   * <p>前缀那串是照着真机口语补的：第一版只有"把/给我/调成/转成/设为/变成/切到"，
   * 「恢复响铃」「切回响铃」「开震动」全都没接住，直接丢给模型去答（handled=false），
   * 白白等网络一圈还答得不对。所以把"恢复/切回/改回/还原/换成/开/打开/开启"都放进来。 */
  private static final Pattern P_SILENT =
      Pattern.compile("^(?:把|给我|恢复|切回|改回|还原|换成|开|打开|开启|调成|转成|设为|变成"
          + "|切到)?(?:手机|设备|铃声)?(?:到)?\\s*"
          + "(静音|震动|响铃|铃声|响)(?:模式)?$");
  /** 音量调到百分之多少。 */
  private static final Pattern P_VOLUME =
      Pattern.compile("^音量(?:调|设|改)?(?:到|至|为|成)?\\s*([0-9]{1,3})\\s*%?$");
  /** 导航到某地：唤起本机装着的地图（高德/百度/腾讯，装哪个起哪个）。 */
  private static final Pattern P_NAV =
      Pattern.compile("^(?:帮我|给我)?(导航|带路|导到|带我到|指路|导航到)\\s*"
          + "(?:去|到|向)?\\s*([^\\s，,。]{2,20})$");
  /** 离线兜底的自述：断网、没配 Key 也能答的一小抄。 */
  private static final Pattern P_CAPABILITY =
      Pattern.compile("^(?:(你能干什么|你能做什么|你都会啥|有什么用|你会什么|"
          + "介绍一下你自己|你是谁|你是什么)(?:吗|呢|啊)?)$");
  private static final Pattern P_CALL_NUM =
      Pattern.compile("(打电话给|打电话|拨打|拨|呼叫)\\s*([0-9+\\-\\s()]{5,20})");
  private static final Pattern P_CALL_NAME =
      Pattern.compile("给\\s*([^\\s]{1,12}?)\\s*打电话");
  private static final Pattern P_SMS =
      Pattern.compile("(发短信给|发信息给|发条短信给|短信给)\\s*([^\\s]{1,20})(?:\\s*(说|内容|：|:)\\s*(.*))?");
  private static final Pattern P_SEARCH =
      Pattern.compile("^(搜索|搜一下|查一下|百度一下|google一下|google|百度)\\s*(.+)$");
  private static final Pattern P_URL =
      Pattern.compile("^(https?://\\S+)|((?:[a-z0-9-]+\\.)+(?:com|cn|net|org|io|co|me|tv|cc|xyz|top|info)(?:\\S*))$");
  private static final String PERIOD = "(早上|上午|中午|下午|晚上|凌晨)";
  private static final String HOUR = "([0-9一二三四五六七八九十两]{1,3})";
  private static final String MIN = "([0-9一二三四五六七八九十]{1,3})?";
  private static final Pattern P_CLOCK =
      Pattern.compile(PERIOD + "?\\s*" + HOUR + "\\s*[点時时:]\\s*" + MIN + "\\s*(半)?");
  private static final Pattern P_ALARM =
      Pattern.compile(PERIOD + "?\\s*" + HOUR + "\\s*[点時时:]\\s*" + MIN
          + "\\s*(半)?[^，。]{0,6}?(闹钟|叫醒我|叫我起床|叫我)");
  private static final Pattern P_ALARM_ANY =
      Pattern.compile("^(设|设置|定)(个)?(.{0,6})(闹钟|闹铃)$");
  private static final Pattern P_TIMER =
      Pattern.compile("([0-9一二三四五六七八九十]{1,4})\\s*(秒|秒钟|分钟|分|小时|个小时)(的)?(倒计时|计时|定时器|定时)?");
  private static final Pattern P_REMIND = Pattern.compile("提醒我(.+)");

  private static final String[] MEDIA_NEXT = {"下一首", "下首歌", "切歌", "下一曲"};
  private static final String[] MEDIA_PREV = {"上一首", "上首歌", "上一曲"};
  private static final String[] MEDIA_PLAY = {"播放音乐", "放首歌", "继续播放", "播放", "放音乐"};
  private static final String[] MEDIA_PAUSE = {"暂停音乐", "暂停播放", "别放了", "暂停"};

  static Outcome run(Context ctx, String raw) {
    if (ctx == null || raw == null) return Outcome.no();
    String q = raw.trim();
    if (q.isEmpty()) return Outcome.no();

    // ---------- 时间 / 日期 ----------
    if (P_TIME.matcher(q).find()) {
      Calendar c = Calendar.getInstance();
      return new Outcome(
          true,
          String.format(Locale.CHINA, "现在 %d:%02d。", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)),
          false);
    }
    if (P_DATE.matcher(q).find()) {
      Calendar c = Calendar.getInstance();
      if (q.contains("明天")) c.add(Calendar.DAY_OF_MONTH, 1);
      if (q.contains("后天")) c.add(Calendar.DAY_OF_MONTH, 2);
      String[] week = {"日", "一", "二", "三", "四", "五", "六"};
      return new Outcome(
          true,
          String.format(
              Locale.CHINA,
              "%d 年 %d 月 %d 日，星期%s。",
              c.get(Calendar.YEAR),
              c.get(Calendar.MONTH) + 1,
              c.get(Calendar.DAY_OF_MONTH),
              week[c.get(Calendar.DAY_OF_WEEK) - 1]),
          false);
    }

    // ---------- 通知栏摘要 ----------
    // 必须抢在下面「展开通知栏」那一条前面：那一条是靠 contains("通知栏") 判的，
    // 否则「通知栏有啥」会被当成"下拉通知栏"处理掉，摘要永远问不出来（2026-10-07 排顺序时踩到）。
    Outcome nt = notifyOps(ctx, q);
    if (nt != null) return nt;

    // ---------- 系统键 ----------
    if (q.contains("回到桌面") || q.contains("回主屏") || q.contains("回主页") || q.contains("回桌面")
        || q.equals("主屏幕") || q.equals("桌面")) {
      key("home");
      return new Outcome(true, "已经回到桌面。", true);
    }
    if (q.equals("返回") || q.equals("后退") || q.contains("返回上一页")) {
      key("back");
      return new Outcome(true, "已返回。", true);
    }
    if (q.contains("最近任务") || q.contains("多任务") || q.contains("任务列表") || q.contains("最近应用")) {
      key("recents");
      return new Outcome(true, "打开最近任务。", true);
    }
    if (q.contains("锁屏")) {
      key("lock");
      return new Outcome(true, "已锁屏。", true);
    }
    if (q.contains("通知栏") || q.contains("下拉通知")) {
      key("notifications");
      return new Outcome(true, "展开通知栏。", true);
    }
    if (q.contains("快捷设置") || q.contains("下拉设置") || q.contains("控制中心")) {
      key("quicksettings");
      return new Outcome(true, "展开快捷设置。", true);
    }

    // ---------- 媒体 ----------
    for (String s : MEDIA_NEXT)
      if (q.contains(s)) return media(ctx, KeyEvent.KEYCODE_MEDIA_NEXT, "下一首。");
    for (String s : MEDIA_PREV)
      if (q.contains(s)) return media(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS, "上一首。");
    for (String s : MEDIA_PLAY)
      if (q.contains(s)) return media(ctx, KeyEvent.KEYCODE_MEDIA_PLAY, "继续播放。");
    for (String s : MEDIA_PAUSE)
      if (q.contains(s)) return media(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE, "已暂停。");

    // ---------- 计算 ----------
    if (P_CALC.matcher(q).matches() && hasDigit(q) && (q.contains("+") || q.contains("-")
        || q.contains("*") || q.contains("/") || q.contains("×") || q.contains("÷")
        || q.contains("^") || q.contains("%"))) {
      String expr = P_CALC_STRIP.matcher(q).replaceAll("");
      if (!expr.isEmpty()) {
        try {
          double v = new Calc(expr).eval();
          String shown = (Math.abs(v - Math.rint(v)) < 1e-9)
              ? String.valueOf((long) Math.rint(v))
              : String.format(Locale.CHINA, "%.6f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
          return new Outcome(true, q + " = " + shown, false);
        } catch (Throwable ignored) {
          // 解析不了就交给模型
        }
      }
    }

    // ---------- 悬浮球：关得掉也要叫得回来 ----------
    // 必须排在「打开应用」前面：不然「打开悬浮球」会被当成"打开一个叫悬浮球的应用"去查表，
    // 查不到就丢给模型，模型再编一句客套话 —— 用户要的是那颗球立刻回来。
    Outcome pb = pebbleOps(ctx, q);
    if (pb != null) return pb;

    // ---------- 主动发声开关 ----------
    // 同样要抢在 P_OPEN 前面：「打开声音跟我说话」不该被当成"打开一个叫声音跟我说话的应用"。
    Outcome vm = voiceModeOps(ctx, q);
    if (vm != null) return vm;

    // ---------- 语音唤醒开关 ----------
    Outcome wk = wakeOps(ctx, q);
    if (wk != null) return wk;

    // ---------- 系统级动作 + 离线自述 ----------
    // 放在「打开应用」之前：不然「打开 Wi-Fi」会被 P_OPEN 当成应用名吃掉。
    Outcome sys = systemOps(ctx, q);
    if (sys != null) return sys;

    // ---------- 打电话 ----------
    Matcher m = P_CALL_NUM.matcher(q);
    if (m.find()) {
      String num = m.group(2).replaceAll("[\\s()\\-]", "");
      if (num.matches("[0-9+*#]{3,20}")) {
        Intent i = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(num)));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
          ctx.startActivity(i);
          return new Outcome(true, "帮你拨 " + num + "。", true);
        } catch (Throwable ignored) {
        }
      }
    }
    m = P_CALL_NAME.matcher(q);
    if (m.find()) {
      String name = m.group(1);
      Intent i = new Intent(Intent.ACTION_DIAL);
      i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      try {
        ctx.startActivity(i);
        return new Outcome(true, "打开拨号界面了，你自己选「" + name + "」吧（联系人我没权限读）。", true);
      } catch (Throwable ignored) {
      }
    }

    // ---------- 发短信 ----------
    m = P_SMS.matcher(q);
    if (m.find()) {
      String to = m.group(2);
      String body = m.group(4) == null ? "" : m.group(4);
      Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(to)));
      if (!body.isEmpty()) i.putExtra("sms_body", body);
      i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      try {
        ctx.startActivity(i);
        return new Outcome(true, "短信界面打开了，收件人 " + to + "。", true);
      } catch (Throwable ignored) {
      }
    }

    // ---------- 搜索 ----------
    m = P_SEARCH.matcher(q);
    if (m.matches()) {
      String kw = m.group(2).trim();
      if (!kw.isEmpty()) {
        Intent i = new Intent(Intent.ACTION_WEB_SEARCH);
        i.putExtra(Search.EXTRA, kw);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
          ctx.startActivity(i);
          return new Outcome(true, "帮你搜「" + kw + "」。", true);
        } catch (Throwable ignored) {
        }
      }
    }

    // ---------- 闹钟 / 倒计时 / 提醒 ----------
    m = P_TIMER.matcher(q);
    if ((q.contains("倒计时") || q.contains("计时") || q.contains("定时器") || q.contains("定时"))
        && m.find()) {
      int secs = toSeconds(m.group(1), m.group(2));
      if (secs > 0) {
        long at = System.currentTimeMillis() + secs * 1000L;
        scheduleWakeup(ctx, at, humanSeconds(secs) + "倒计时到点啦");
        return new Outcome(true, "倒计时 " + humanSeconds(secs) + " 开始数了，到点我会告诉你。", true);
      }
    }
    m = P_ALARM.matcher(q);
    if (m.find()) {
      int hour = toHour(m.group(2), m.group(1));
      int minute = 0;
      if (m.group(3) != null && !m.group(3).isEmpty()) minute = toInt(m.group(3));
      if ("半".equals(m.group(4))) minute = 30;
      if (hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59) {
        int off = dayOffset(q);
        String when = off == 1 ? "明天" : off == 2 ? "后天" : "今天";
        String msg = when + " " + hour + ":" + String.format(Locale.CHINA, "%02d", minute);
        scheduleWakeup(ctx, atTime(hour, minute, off), msg);
        return new Outcome(true, "闹钟定好了：" + when + " " + hour + "点"
            + (minute == 0 ? "" : minute + "分") + " 响。", true);
      }
    }
    m = P_ALARM_ANY.matcher(q);
    if (m.matches()) {
      // 没听出时间就别瞎猜一个点，把时钟界面打开让他自己来，并说清楚这事只能手动
      try {
        Intent i = new Intent(AlarmClock.ACTION_SET_ALARM);
        i.putExtra(AlarmClock.EXTRA_SKIP_UI, false);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
      } catch (Throwable ignored) {
      }
      return new Outcome(true,
          "你还没说几点响呢。说「明天7点叫我起床」这种，我就能自己定。", false);
    }

    m = P_REMIND.matcher(q);
    if (m.find()) {
      String what = m.group(1).trim();
      Matcher t = P_CLOCK.matcher(what);
      if (t.find()) {
        int hour = toHour(t.group(2), t.group(1));
        int minute = t.group(3) == null || t.group(3).isEmpty() ? 0 : toInt(t.group(3));
        if ("半".equals(t.group(4))) minute = 30;
        if (hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59) {
          String msg = t.replaceFirst("").replaceFirst("^[^\\u4e00-\\u9fa5A-Za-z0-9]+", "").trim();
          if (msg.isEmpty()) msg = what;
          int off = dayOffset(what);
          String label = (off == 1 ? "明天" : off == 2 ? "后天" : "") + hour + ":" + minute;
          // 到点响铃 + 落一条提醒单位：桌面小组件立刻就能看见这一条（2026-10-08 起）。
          scheduleWakeup(ctx, atTime(hour, minute, off), label + " " + msg);
          ReminderStore.add(ctx, ReminderStore.KIND_TODO, null, label + " " + msg, "",
              atTime(hour, minute, off));
          return new Outcome(true, "记住了，" + label + " 提醒你" + (msg.isEmpty() ? "" : "：" + msg),
              true);
        }
      }
      // 没听出时间 -> 让模型去理解（可能是一句「提醒我喝水」这种没时间的）
    }

    // ---------- 公交 / 到站倒计时 ----------
    Outcome bus = busOps(ctx, q);
    if (bus != null) return bus;

    // ---------- 提醒清单 ----------
    Outcome rem = remindOps(ctx, q);
    if (rem != null) return rem;

    // ---------- 有哪些应用 ----------
    if (P_APP_LIST.matcher(q.trim()).matches()) {
      List<String[]> apps = installedApps(ctx);
      // 按标签排序，用户看着像"列表"；名字太多先给个够用的条数，别把面板撑爆
      List<String[]> sorted = new ArrayList<>(apps);
      sorted.sort((a, b) -> a[0].compareToIgnoreCase(b[0]));
      StringBuilder sb = new StringBuilder("这台手机上能直接打开的应用有 "
          + sorted.size() + " 个：");
      int n = Math.min(sorted.size(), 24);
      List<String> names = new ArrayList<>();
      for (int i = 0; i < n && i < sorted.size(); i++) names.add(sorted.get(i)[0]);
      sb.append(String.join("、", names));
      if (sorted.size() > n) sb.append("，等一共 ").append(sorted.size()).append(" 个");
      return new Outcome(true, sb.toString(), false);
    }

    // ---------- 技能本子：建 / 列 / 忘 ----------
    Outcome sk = skillOps(ctx, q);
    if (sk != null) return sk;

    // ---------- 任务：「在 X 上给 Y 发 Z / 在 X 里搜 Y」 ----------
    // 2026-10-09 用户原话：目前只到打开应用的层级，"在 Telegram 上给机器人小飞鱼发你好"
    // 只开了 Telegram 没有后续。这里接住任务语句：(a) 有网 → 打开应用后交给
    // AssistantTaskRunner 的模型执行循环接着办；(b) 没网 → 只开应用，并如实说后面的要自己来。
    // 必须排在 P_OPEN 前面：这类话里往往没有"打开"两个字，P_OPEN 根本接不住；
    // 而"打开 Telegram 跟小飞鱼说你好"这种带打开字样的，也不能只开个应用就完事。
    Outcome tk = taskOps(ctx, q);
    if (tk != null) return tk;

    // ---------- 打开应用 / 网址 ----------
    // P_OPEN 的分组：1=帮我/请 2=打开/启动 3=一下 4=**应用名**
    // （别写死成 group(2)：把它当成动词，应用名就丢了 —— 2026-10-07 我自己踩过）
    m = P_OPEN.matcher(q);
    if (m.matches()) {
      String target = m.group(4).trim();
      target = P_TAIL_NOISE.matcher(target).replaceFirst("");
      target = target.trim();
      if (!target.isEmpty()) {
        Matcher u = P_URL.matcher(target.toLowerCase(Locale.ROOT));
        if (u.find()) {
          String url = target.startsWith("http") ? target : "https://" + target;
          Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
          i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
          try {
            ctx.startActivity(i);
            return new Outcome(true, "打开 " + url, true);
          } catch (Throwable ignored) {
          }
        }
        String label = launchApp(ctx, target);
        if (label != null) return new Outcome(true, "打开了" + label + "。", true);
        // 找不到：把**真机上装着的**最接近几个报出来，绝不要再让模型去让用户翻应用列表。
        // 口语里被叫出来的名字可能带一串口水（"火星 Explorer xyz"），回显时截断一下，
        // 否则用户听到"没装「火星 Explorer xyz」"懵半天，其实想说的是"火星"。
        String said = target.length() <= 10 ? target : target.substring(0, 10) + "…";
        List<String> near = nearestApps(ctx, target, 3);
        if (!near.isEmpty()) {
          return new Outcome(
              true,
              "这台手机上没有「" + said + "」。"
                  + "装着的比较像的有：" + String.join("、", near)
                  + "。你说「打开" + near.get(0) + "」我就开。",
              false);
        }
        return new Outcome(
            true, "这台手机上没装「" + said + "」，也没找到名字相近的应用。", false);
      }
    }

    return Outcome.no();
  }

  /**
   * 本地指令的统一入口。这里包一层总兜底是<b>血的教训</b>：
   *
   * <p>2026-10-07 真机，一句「手机静音」把整个助手干掉了 ——
   * <pre>
   * E AndroidRuntime: java.lang.SecurityException: Not allowed to change Do Not Disturb state
   * E AndroidRuntime:   at android.media.AudioManager.setRingerMode(AudioManager.java:1177)
   * E AndroidRuntime:   at AssistantCommands.systemOps(AssistantCommands.java:527)
   * E AndroidRuntime:   at AssistantPanel.runText(AssistantPanel.java:263)
   * </pre>
   * 调用栈是从无障碍服务里发出去的，异常没人接就一路顶出去把 <b>app 进程连同无障碍服务一起带走</b>，
   * 面板、读屏、助手全停摆，还得去设置里把无障碍重新打开一次才活过来（而且崩过一次的服务系统
   * 会挂进 `Crashed services`，不会自动重绑，必须 delete 掉再 put 回 enabled_accessibility_services）。
   *
   * <p>一句话总结：<b>本地指令抛异常 ≠ 这条指令失败，是整台手机的助手没了。</b>
   * 所以这里统一兜住，给一句人话答复，让下一条指令还有机会接上。
   */
  /**
   * 技能本子的三个入口：建 / 列 / 忘（命中返回 {@link Outcome}，没命中返回 null）。
   *
   * <p>「自己写 skill」落在 {@link AssistantSkills}：一个技能就是私有目录里一个
   * {@code *.skill.md} 文本文件，助手自己写、你也能拿文件管理器改。这条命令只负责
   * 把**自然语言切成几段**再交给它落盘。
   *
   * <p>说一句就成，例如：
   * <pre>
   * 建个技能：这单什么时候付，先报金额和到期日，chip:这单我该啥时候付
   * </pre>
   * 解析是「段首关键字」+「位置」两路，任一路命中就算：
   * <ul>
   *   <li>第 1 段 / {@code 触发词:} —— 以后哪句话算触发这个技能；</li>
   *   <li>第 2 段 / {@code 要求:}（也可写 风格: 样式: 怎么答:）—— 以后怎么答；</li>
   *   <li>第 3 段起 / {@code chip:}（也可写 推荐: 建议:）—— 挂到推荐位上那句话。</li>
   * </ul>
   */
  private static Outcome skillOps(Context ctx, String q) {
    String s = q.trim();
    if (P_SKILL_LIST.matcher(s).matches()) {
      return new Outcome(true, AssistantSkills.listText(ctx), false);
    }
    Matcher md = P_SKILL_DELETE.matcher(s);
    if (md.matches()) {
      String name = md.group(1).trim();
      name = name.replaceAll("^(?:那个|这个|叫|名字叫|技能|技巧|招式)\\s*", "").trim();
      if (name.isEmpty()) {
        return new Outcome(true, "要忘掉哪个技能？说「忘掉技能 付款提醒」这样。", false);
      }
      return new Outcome(
          true,
          AssistantSkills.delete(ctx, name)
              ? "技能「" + name + "」忘了。"
              : "没找到叫「" + name + "」的技能。",
          false);
    }
    Matcher mc = P_SKILL_CREATE.matcher(s);
    if (mc.matches()) return buildSkill(ctx, mc.group(1));
    return null;
  }

  /** 把一句自然语言切成技能文件的三段内容，落盘，回一句人话。 */
  private static Outcome buildSkill(Context ctx, String body) {
    List<String> triggers = new ArrayList<>();
    StringBuilder style = new StringBuilder();
    StringBuilder chip = new StringBuilder();
    int seg = 0;
    for (String part : body.split("[,，]")) {
      final String raw = part.trim();
      seg++;
      if (raw.isEmpty()) continue;
      String key = raw;
      String val = null;
      int colon = raw.indexOf(':');
      if (colon < 0) colon = raw.indexOf('：');
      // 只有前面是几个字的小标签才算"段首关键字"，别把「这单什么时候付：」里的整句当 key
      if (colon > 0 && colon <= 8) {
        key = raw.substring(0, colon);
        val = raw.substring(colon + 1).trim();
      }
      String k = key.toLowerCase(Locale.ROOT);
      boolean named = true;
      if (k.startsWith("触发") || k.startsWith("trigger")) {
        addTriggers(triggers, val == null ? raw : val);
      } else if (k.startsWith("chip") || k.startsWith("推荐") || k.startsWith("建议")) {
        chip.append(' ').append(val == null ? raw : val);
      } else if (k.startsWith("要求") || k.startsWith("风格") || k.startsWith("样式")
          || k.startsWith("style") || k.startsWith("答法") || k.startsWith("怎么答")) {
        style.append(' ').append(val == null ? raw : val);
      } else {
        named = false; // 没写标签 → 按位置猜
      }
      if (!named) {
        if (seg <= 1) addTriggers(triggers, raw);
        else if (seg == 2) style.append(' ').append(raw);
        else chip.append(' ').append(raw);
      }
    }
    if (triggers.isEmpty()) {
      return new Outcome(
          true,
          "技能没记成：没听出以后哪句话算触发。说「建个技能：这单什么时候付，先报金额」这样。",
          false);
    }
    String want = style.toString().trim();
    try {
      String saved = AssistantSkills.create(
          ctx, triggers.get(0), triggers, want, chip.toString().trim());
      StringBuilder reply = new StringBuilder("技能「").append(saved).append("」记下了：");
      reply.append("以后你说「").append(triggers.get(0)).append("」，我就");
      reply.append(want.isEmpty() ? "照这个口径答（没另加要求）。" : want + "。");
      return new Outcome(true, reply.toString(), false);
    } catch (Throwable t) {
      // 别让一句"建技能"变成一句废话：把正确说法直接说给用户
      return new Outcome(
          true,
          "技能没记成（" + t.getClass().getSimpleName()
              + "）。说「建个技能：这单什么时候付，先报金额」这样就行。",
          false);
    }
  }

  /** 触发词可能一口气说好几句（「帮我看看该付多少 / 这单啥时候付」），拆开各算一条。 */
  private static void addTriggers(List<String> out, String raw) {
    for (String t : raw.split("[、/,|]")) {
      String k = t.trim();
      if (!k.isEmpty() && !out.contains(k)) out.add(k);
    }
  }

  static Outcome runGuarded(Context ctx, String raw) {
    try {
      return run(ctx, raw);
    } catch (Throwable t) {
      android.util.Log.e(TAG, "本地指令炸了，已兜住，没让进程挂掉: " + t);
      return new Outcome(
          true,
          "这条我没干成：" + t.getClass().getSimpleName() + "。其他指令照常用，"
              + "问一句「你能干什么」我给你列一遍。",
          false);
    }
  }

  /** 把用户嘴里的应用名规范化：去空白、去符号、去语气词，方便跟应用标签对表。 */
  private static String keyOf(String s) {
    return s == null
        ? ""
        : s.toLowerCase(Locale.ROOT)
            .replaceAll("[\\s_\\-\\uff08\\uff09()\\[\\]【】·:：,，。!！?？'\"+&]", "")
            .replaceAll("(版|官方|客户端)$", "");
  }

  /** 已安装可启动应用（标签 + 包名），每次现查，拿不到就返回空表。 */
  private static List<String[]> installedApps(Context ctx) {
    List<String[]> out = new ArrayList<>();
    try {
      PackageManager pm = ctx.getPackageManager();
      Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
      List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);
      if (apps == null) return out;
      for (ResolveInfo ri : apps) {
        if (ri.activityInfo == null) continue;
        String p = ri.activityInfo.packageName;
        if (p == null || p.isEmpty() || p.equals(ctx.getPackageName())) continue;
        CharSequence lab = ri.loadLabel(pm);
        String label = lab == null ? p : lab.toString();
        if (label.isEmpty()) continue;
        out.add(new String[] {label, p});
      }
    } catch (Throwable ignored) {
    }
    return out;
  }

  /**
   * 按名字在已安装的可启动应用里找，返回匹配到的应用名；找不到返回 null。
   *
  /** 离线也能答的自述（没网、没配 Key 都不影响）。 */
  private static final String CAPABILITY_TEXT =
      "我能在本机直接干这些，不用联网：问时间日期、算数、打开你装的应用、列有哪些应用、"
          + "打电话发短信、定闹钟和倒计时、静音或调音量、开关 Wi-Fi、查电量存储和网络、"
          + "导航唤起地图、以及看你现在屏幕上是什么。想做什么直接说，这些我都秒回；"
          + "需要聊天闲扯的那种我会交给模型。"
          + "另外我会自己攒技能：你说「建个技能：这单什么时候付，先报金额」就记下一条，"
          + "以后再提这事我按那个口径答；「有哪些技能」我能念给你听，「忘掉技能 付款提醒」能删掉。"
          + "用久了，你最常说的那几句会自己爬到推荐位最前面。"
          + "还可以开手电筒（要相机权限，只为开闪光灯）、问飞行模式开关（这台手机不给第三方动的话，"
          + "我把快捷设置拉出来）、以及「谁在找我」——念通知栏里最近的几条，"
          + "银行和支付类应用的通知按你的规矩一条都不念。"
          + "跟你聊天的上下文我留得比较长，越聊越记得住前面说过啥。";

  /** 装了哪个地图就起哪个（高德 / 百度 / 腾讯 / 谷歌），一个都没有才走网页。 */
  private static final String[] MAP_PKGS =
      new String[] {"com.autonavi.minimap", "com.baidu.BaiduMap", "com.tencent.map",
          "com.google.android.apps.maps"};

  private static String gb(long bytes) {
    return String.format(Locale.CHINA, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
  }

  /** 「明天 / 后天」这种相对日子，换算成几天后给闹钟用。 */
  private static int dayOffset(String q) {
    return q.contains("后天") ? 2 : q.contains("明天") ? 1 : 0;
  }

  /**
   * 挂一个"到点叫 DSHA 自己响"的闹钟。
   *
   * <p>标准 {@code AlarmClock.ACTION_SET_ALARM} 在这台机上是死的（华为把 HandleSetAlarm
   * 保护起来了，见 {@link DshaAlarmReceiver} 的类注释和真机 Permission Denial 日志），
   * 所以走 {@link android.app.AlarmManager#setAlarmClock} 让系统到点唤醒 DSHA 自己的接收器，
   * 响铃 / 震动 / 全屏通知全在 DSHA 手上，反而更可控：能读出来、能取消。
   *
   * @param atMillis 到点的绝对时间
   */
  private static void scheduleWakeup(Context ctx, long atMillis, String msg) {
    try {
      android.app.AlarmManager am =
          (android.app.AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
      if (am == null) return;
      Intent i = new Intent(ctx, DshaAlarmReceiver.class);
      i.setAction(DshaAlarmReceiver.ACTION_FIRED);
      i.putExtra("msg", msg);
      int flags = PendingIntent.FLAG_UPDATE_CURRENT;
      // 裁剪过的 android.jar 里 PendingIntent.FLAG_IMMUTABLE 也可能编不过，按版本判断 + 写死
      if (android.os.Build.VERSION.SDK_INT >= 23) flags |= 0x04000000; // FLAG_IMMUTABLE
      PendingIntent pi =
          PendingIntent.getBroadcast(ctx, (int) (atMillis & 0x7fffffff), i, flags);
      if (android.os.Build.VERSION.SDK_INT >= 21) {
        am.setAlarmClock(
            new android.app.AlarmManager.AlarmClockInfo(atMillis, pi), pi);
      } else {
        am.set(android.app.AlarmManager.RTC_WAKEUP, atMillis, pi);
      }
    } catch (Throwable t) {
      android.util.Log.w(TAG, "闹钟没挂上: " + t);
    }
  }

  /** 把「小时:分」算成绝对时刻。已经过去的时刻顺延到明天，别让用户设了个昨天的时间。 */
  private static long atTime(int hour, int minute, int dayOffset) {
    Calendar c = Calendar.getInstance();
    if (dayOffset > 0) c.add(Calendar.DAY_OF_MONTH, dayOffset);
    c.set(Calendar.HOUR_OF_DAY, hour);
    c.set(Calendar.MINUTE, minute);
    c.set(Calendar.SECOND, 0);
    c.set(Calendar.MILLISECOND, 0);
    if (c.getTimeInMillis() <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_MONTH, 1);
    return c.getTimeInMillis();
  }

  // ---------------------------------------------------------------- 提醒 / 公交（2026-10-08）

  /**
   * 公交到站倒计时。
   *
   * <p><b>数据源只认一条真能长期跑通的路</b>：公交 App 推的**到站通知**（车来了、百度/高德地图的
   * 公交提醒）。实时到站查询的公开接口要么要地图厂商的 key、有配额，要么是内网地址会过期，
   * 接进来迟早烂掉 —— 所以不硬接。听到「公交」相关的话，就把最近那条 bus 提醒读出来答；
   * 一条都没有就照实说没有，并告诉用户去哪能让它自动记下倒计时。
   */
  private static Outcome busOps(Context ctx, String q) {
    if (ctx == null || q == null) return null;
    boolean transit =
        q.contains("公交") || q.contains("巴士") || q.contains("地铁")
            || q.contains("班车") || q.contains("校车") || q.contains("大巴");
    if (!transit) return null;
    boolean wantsWhen =
        q.contains("还有多久") || q.contains("多久") || q.contains("到站")
            || q.contains("车来") || q.contains("来没") || q.contains("几点") || q.contains("还有几站");
    List<ReminderStore.Reminder> l = ReminderStore.list(ctx);
    for (ReminderStore.Reminder r : l) {
      if (!ReminderStore.KIND_BUS.equals(r.kind)) continue;
      String left = ReminderStore.countdown(r);
      String body = (r.body == null || r.body.isEmpty()) ? "" : "，" + r.body;
      if (wantsWhen) {
        return new Outcome(true, r.title + " 还有" + left + body + "。", false);
      }
      return new Outcome(true, "已经在桌面小组件上了：" + r.title + "，还有" + left + "。" + body, false);
    }
    if (!wantsWhen) return null;
    return new Outcome(true,
        "这会儿没查到公交到站。装个「车来了」这类 App，到站它会推通知 —— 推过来我就自动记成"
            + "倒计时，桌面那块小组件显示「还有几分钟」；你说「提醒我 7 点 40 上车」也成。",
        false);
  }

  /** 提醒清单 / 取消提醒（桌面小组件显示的就是这里最靠前的那条）。 */
  private static Outcome remindOps(Context ctx, String q) {
    if (ctx == null || q == null) return null;
    boolean list =
        q.contains("哪些提醒") || q.contains("提醒列表") || q.contains("下一个提醒")
            || q.contains("还有什么提醒");
    boolean cancel =
        q.contains("取消提醒") || q.contains("删掉提醒") || q.contains("清掉提醒");
    if (!list && !cancel) return null;
    List<ReminderStore.Reminder> l = ReminderStore.list(ctx);
    if (l.isEmpty()) {
      return new Outcome(
          true, cancel ? "没有提醒，没什么可取消的。" : "现在没有提醒。说「提醒我…」就能加一条。", false);
    }
    if (cancel) {
      ReminderStore.Reminder first = l.get(0); // 没说清哪条就撤最靠前那条
      String name = first.title;
      ReminderStore.remove(ctx, first.id);
      return new Outcome(true, "取消了「" + name + "」。", false);
    }
    StringBuilder sb = new StringBuilder("还有 " + l.size() + " 条提醒：");
    int n = Math.min(3, l.size());
    for (int i = 0; i < n; i++) {
      ReminderStore.Reminder r = l.get(i);
      sb.append("\n· ").append(r.title).append("（").append(ReminderStore.countdown(r)).append('）');
    }
    if (l.size() > n) sb.append("\n（还有 ").append(l.size() - n).append(" 条没念）");
    return new Outcome(true, sb.toString(), false);
  }

  /** 起一个 Intent，优先落到指定包名列表里装着的那个。 */
  private static boolean startAny(Context ctx, Intent base, String... prefer) {
    try {
      if (prefer != null) {
        for (String pkg : prefer) {
          Intent i = (Intent) base.clone(); // clone() 声明返回 Object，不转就编译不过
          i.setPackage(pkg);
          if (ctx.getPackageManager().resolveActivity(i, 0) != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
          }
        }
      }
      base.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      ctx.startActivity(base);
      return true;
    } catch (Throwable ignored) {
      return false;
    }
  }

  /** 电量读出来的结果。 */
  private static final class BatteryInfo {
    final int pct;
    final boolean charging;

    BatteryInfo(int pct, boolean charging) {
      this.pct = pct;
      this.charging = charging;
    }
  }

  /**
   * 读电量。**不能直接信 {@link BatteryManager#getIntProperty}**：
   * 这台机（EMUI 12 / HarmonyOS）上 {@code getIntProperty(3)}（BATTERY_PROPERTY_CAPACITY）
   * 返回的是 -127700 这种垃圾值，「电量还剩多少」会回一句「电量 -127700%」，
   * 用户第一反应是助手坏了。同一台机 `dumpsys battery` 的 level=25 / scale=100 是对的，
   * 所以改走 ACTION_BATTERY_CHANGED 那条老但稳的路（sticky 广播，不需要任何权限）。
   */
  private static BatteryInfo readBattery(Context ctx) {
    try {
      Intent st = ctx.registerReceiver(
          null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
      if (st != null) {
        int level = st.getIntExtra("level", -1);
        int scale = st.getIntExtra("scale", -1);
        // 这两段字符串常量别换成 BatteryManager.EXTRA_LEVEL：裁剪过的 android.jar 里
        // BatteryManager.EXTRA_LEVEL / EXTRA_SCALE / EXTRA_PLUGGED 全都编译不过。
        if (level >= 0 && scale > 0) {
          int pct = level * 100 / scale;
          if (pct >= 0 && pct <= 100) { // 再挡一道，别让同样的事换个地方再犯
            return new BatteryInfo(pct, st.getIntExtra("plugged", 0) != 0);
          }
        }
      }
    } catch (Throwable ignored) {
    }
    try {
      BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
      if (bm != null) {
        int pct = bm.getIntProperty(3);
        if (pct >= 0 && pct <= 100) {
          return new BatteryInfo(pct, bm.getIntProperty(4) == 1);
        }
      }
    } catch (Throwable ignored) {
    }
    return null;
  }

  /**
   * 语音唤醒（「你好大肥鱼」）的开关：默认开（用户 2026-10-09 点名要的），
   * 但必须留一句话关 —— 麦克风常开不是小事，用户随时要能一句话让它闭麦。
   */
  private static Outcome wakeOps(Context ctx, String q) {
    if (ctx == null || q == null) return null;
    String s = q.trim();
    if (s.length() > 24 || !s.contains("唤醒")) return null;
    boolean off = s.contains("关闭") || s.contains("关掉") || s.contains("停") || s.contains("别")
        || s.contains("不要") || s.contains("取消");
    boolean on = s.contains("打开") || s.contains("开启") || s.contains("启动")
        || s.contains("开着") || s.contains("要");
    if (!off && !on) {
      return new Outcome(
          true,
          AssistantWakeService.enabled(ctx)
              ? "语音唤醒开着：说「你好大肥鱼」我就在屏幕上应你（默认不出声）。"
              : "语音唤醒是关着的。说「打开语音唤醒」我就开始听。",
          false);
    }
    if (off) {
      AssistantWakeService.setEnabled(ctx, false);
      return new Outcome(true, "语音唤醒关了，麦克风也闭上了。想再开说「打开语音唤醒」。", false);
    }
    AssistantWakeService.setEnabled(ctx, true);
    return new Outcome(
        true, "语音唤醒开了。说「你好大肥鱼」试试 —— 我只亮屏写字，不出声。", false);
  }

  /**
   * 主动发声开关（2026-10-09 用户原话指定的三档）：
   * <ul>
   *   <li>默认不出声，只写字 —— {@link AssistantVoiceMode#SILENT}；
   *   <li>「请你跟我直接说话」→ {@link AssistantVoiceMode#SPEAK}，之后回答自动念出来；
   *   <li>「你把声音调到最大跟我说话」→ {@link AssistantVoiceMode#LOUD}，顺手把媒体音量拉满；
   *   <li>「别出声 / 闭嘴」→ 回到 {@link AssistantVoiceMode#SILENT}。
   * </ul>
   *
   * <p><b>句子长度卡在 24 字以内</b>：这些词组都太日常（"说话"两个字到处出现），
   * 长句里带"跟我说话"多半是在聊别的事，不能抢答。真正的指令都是短句。
   */
  private static Outcome voiceModeOps(Context ctx, String q) {
    if (ctx == null || q == null) return null;
    String s = q.trim();
    if (s.isEmpty() || s.length() > 24) return null;

    boolean off = s.contains("别出声") || s.contains("不要出声") || s.contains("别说话")
        || s.contains("不要说话") || s.contains("闭嘴") || s.contains("别念了")
        || s.contains("不用念") || s.contains("安静点") || s.contains("安静一下")
        || s.contains("别读出来") || s.contains("不用读");
    if (off) {
      AssistantVoiceMode.set(ctx, AssistantVoiceMode.SILENT);
      return new Outcome(true, "好，我闭嘴，只写字。想让我开口就说「跟我说话」。", false);
    }

    boolean loud = (s.contains("最大") && (s.contains("声") || s.contains("音量")))
        || s.contains("最大声") || s.contains("大声点跟我说话");
    boolean on = s.contains("直接说话") || s.contains("跟我说话") || s.contains("对我说")
        || s.contains("开口说话") || s.contains("念出来") || s.contains("读出来")
        || s.contains("说出来") || s.contains("出声") || s.contains("开口")
        || s.contains("voice");
    if (loud) {
      AssistantVoiceMode.set(ctx, AssistantVoiceMode.LOUD);
      AssistantVoiceMode.maxVolume(ctx);
      return new Outcome(true, "声音拉满了，之后都大声念给你听。", false);
    }
    if (on) {
      AssistantVoiceMode.set(ctx, AssistantVoiceMode.SPEAK);
      return new Outcome(true, "好，之后我都念给你听。想让我闭嘴就说「别出声」。", false);
    }
    return null;
  }

  /**
   * 任务语句：「在 Telegram 上给我的机器人'小飞鱼'发一个'你好'」/「在淘宝里搜机械键盘」。
   *
   * <p>命中返回 {@link Outcome}（handled=true），没命中返回 null 走后面的分发。
   * 有网时异步起 {@link AssistantTaskRunner} 的执行循环，回复先说"我来接着办"；
   * 没网时退化为用户指定的 (b) 方案 —— 只把应用打开，后面如实说做不了。
   */
  private static Outcome taskOps(Context ctx, String q) {
    AssistantTaskRunner.Task task = AssistantTaskRunner.parse(q);
    if (task == null) return null;

    String label = launchApp(ctx, task.app);
    if (label == null) {
      List<String> near = nearestApps(ctx, task.app, 3);
      if (!near.isEmpty()) {
        return new Outcome(
            true,
            "这台手机上没有「" + task.app + "」。装着的比较像的有：" + String.join("、", near)
                + "。你说「在" + near.get(0) + "上」我就接着办。",
            false);
      }
      return new Outcome(true, "这台手机上没装「" + task.app + "」，办不了这个。", false);
    }
    // 回填包名：执行循环靠它等"目标应用真的到前台"再开跑
    for (String[] app : installedApps(ctx)) {
      if (app[0].equals(label)) {
        task.pkg = app[1];
        break;
      }
    }

    if (!AssistantTaskRunner.online(ctx)) {
      // 用户指定的 (b)：没网就只做打开应用这一层，别装作办了
      return new Outcome(
          true,
          "现在没网，" + label + "帮你打开了，里面的操作得你自己来。",
          true);
    }

    if (!(ctx instanceof DshaAccessibilityService)) {
      return new Outcome(
          true, label + "打开了。不过无障碍服务没在，我读不了它的界面，后面的你自己点。", true);
    }

    final DshaAccessibilityService host = (DshaAccessibilityService) ctx;
    final AssistantConfig cfg = AssistantConfig.load(host);
    AssistantTaskRunner.start(host, cfg, task, label);
    return new Outcome(
        true,
        "好，" + label + "打开了，我接着办：" + task.goal + "。过程和结果都在面板上看，收起面板我就停手。",
        false,
        true);
  }

  /**
   * 悬浮球的开关：拖进删除区删掉之后，还得留一句话把它叫回来。
   *
   * <p><b>为什么必须做这条路</b>（2026-10-09）：删除区是"拖过去松手就没了"，非常顺手，
   * 也正因为顺手，一次误操作就会把这个<b>唯一一个「正在用别的 App 时抬手就能碰到」的入口</b>
   * 弄丢，而且记住了就没得起 —— 只能重装 App。所以语音必须能把它请回来。
   *
   * <p>关和开走的是同一个 {@code KEY_HIDDEN} 开关，两条路互斥又互通：
   * 不管怎么关的（拖进删除区 / 说一句收起来），都是同一句「把悬浮球弄回来」把它请回来。
   *
   * <p><b>先判动词再判名词</b>：「显示悬浮球」里虽然带"显示"，但整句是"要它出现"，
   * 所以先过"回来"那组词，再过"收起"那组，最后落到"回来"兜底。
   */
  private static Outcome pebbleOps(Context ctx, String q) {
    if (ctx == null || q == null) return null;
    String s = q.trim();
    boolean ball = s.contains("悬浮球") || s.contains("悬浮窗") || s.contains("浮动球")
        || s.contains("浮动窗") || s.contains("悬浮钮") || s.contains("悬浮按钮")
        || s.contains("小球") || s.contains("那个球") || s.contains("小圆点");
    if (!ball) return null;

    boolean want = s.contains("回来") || s.contains("恢复") || s.contains("还给我")
        || s.contains("找回") || s.contains("弄回来") || s.contains("弄出来")
        || s.contains("显示") || s.contains("出来") || s.contains("重现")
        || s.contains("打开") || s.contains("开启") || s.contains("放出来");
    boolean off = s.contains("收起来") || s.contains("关掉") || s.contains("关闭")
        || s.contains("删掉") || s.contains("去掉") || s.contains("别显示")
        || s.contains("隐藏") || s.contains("藏起来") || s.contains("不需要");

    if (off && !want) {
      DshaPebbleView.banish(ctx);
      return new Outcome(true, "悬浮球收起来了。想它回来说一句「把悬浮球弄回来」。", false);
    }
    if (want || !off) {
      boolean back = DshaPebbleView.revive(ctx);
      return new Outcome(
          true,
          back ? "悬浮球回来了，就在边上。" : "记住了，下次面板一收悬浮球就回来。",
          back);
    }
    return null;
  }

  /**
   * 系统级动作 + 离线自述。命中就返回 {@link Outcome}，没命中返回 null（交给后面的分发）。
   *
   * <p>全部只用清单里已经申请过的权限，不弹任何权限框：Wi-Fi（CHANGE_WIFI_STATE）、
   * 音量/铃音（MODIFY_AUDIO_SETTINGS）、电量（BatteryManager 读是公开的）、存储（StatFs）、
   * 网络（ConnectivityManager）、地图（本来就靠 Intent）。
   *
   * <p><b>手电筒 / 飞行模式 / 通知栏是这条路上的例外</b>（2026-10-07 用户点名要的）：
   * 三者各自要一道权限，走 {@link AssistantPermit} 闸门 —— 能交系统的交系统原生框，
   * 系统不给弹的才画卡片。**做完还是做不成都如实说**：写不进飞行模式就明说这台 ROM 不给，
   * 顺手把快捷设置拉出来，不假装"开好了"。
   */
  private static Outcome systemOps(Context ctx, String q) {
    if (ctx == null || q == null) return null;
    String raw = q.trim();
    String ql = raw.toLowerCase(Locale.ROOT);

    if (P_CAPABILITY.matcher(raw).matches()) return new Outcome(true, CAPABILITY_TEXT, false);

    // ---------- 手电筒 ----------
    if (isTorch(raw)) {
      String lc = raw.toLowerCase(Locale.ROOT);
      if (lc.contains("开没开") || lc.contains("开着没") || lc.contains("开着吗") || lc.contains("亮没亮")
          || lc.contains("亮着") || lc.contains("是不是开着")) {
        return new Outcome(
            true, AssistantPermit.torchOn ? "手电筒正亮着。" : "手电筒是关的。", false);
      }
      // 「开关手电筒」这种含糊说法 = 翻一下当前状态
      boolean wantOn = !lc.contains("开关") && !lc.contains("toggle");
      if (wantOn && (lc.contains("关") || lc.contains("熄") || lc.contains("灭")
          || lc.contains("不要") || lc.contains("别") || lc.contains("收起"))) {
        wantOn = false;
      }
      if (AssistantPermit.ready(ctx, AssistantPermit.WHAT_FLASH)) {
        boolean ok = AssistantPermit.torch(ctx, wantOn);
        AssistantPermit.torchOn = ok ? wantOn : false;
        // 面板**不收**：灯正亮着，用户多半下一句就是"再亮一点 / 关掉"，
        // 把面板收了等于让他再叫一次助手（2026-10-07 自己用出来的别扭）。
        return new Outcome(
            true,
            ok ? (wantOn ? "手电筒开了。" : "手电筒关了。")
                : "手电筒没动静：这台手机可能没闪光灯，或者相机正占着它。",
            false);
      }
      AssistantPermit.ask(ctx, AssistantPermit.WHAT_FLASH,
          wantOn ? "你说要开手电筒" : "你说要关手电筒");
      return new Outcome(
          true,
          AssistantPermit.torchCameraId(ctx) == null
              ? "这台手机没有闪光灯，开不了手电筒。"
              : "手电筒要相机权限（只为开闪光灯，不开摄像头、不存画面）—— 弹窗里点允许，"
                  + "亮了我再告诉你；不想给就点" + "不要" + "。",
          false);
    }

    // ---------- 飞行模式 ----------
    if (isAirplane(raw)) {
      String lc = raw.toLowerCase(Locale.ROOT);
      boolean wantOn = !(lc.contains("关") || lc.contains("取消") || lc.contains("退"));
      if (wantOn == airplaneState(ctx)) {
        return new Outcome(true, (wantOn ? "飞行模式本来就是开的。" : "飞行模式本来就是关的。"), false);
      }
      Outcome done = setAirplane(ctx, wantOn);
      if (done != null) return done;
      // 写不进 secure 设置（这台 ROM 不给第三方动）→ 明说，并把快捷设置拉出来让人点一下
      AssistantPermit.ask(ctx, AssistantPermit.WHAT_AIRPLANE,
          wantOn ? "你说要开飞行模式" : "你说要关飞行模式");
      return new Outcome(
          true,
          (wantOn ? "开" : "关") + "飞行模式这台手机不给第三方 App 做（"
              + "设置里那条被系统锁着），我没法替你按。快捷设置已经拉出来了，你在那儿点一下就行。",
          true);
    }

    Matcher ms = P_SILENT.matcher(raw);
    if (ms.find()) {
      AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
      if (am != null) {
        String mode = ms.group(1);
        // 下面这几个常量写成数值了，是有原因的：这台工程编出来的 android.jar 是裁剪过的，
        // AudioManager.MODE_* / BatteryManager.BATTERY_PROPERTY_* 在 javac 眼里"找不到符号"
        // （编译报：符号 变量 MODE_VIBRATE / 位置 类 AudioManager），用全限定名也一样。
        // 所以照 Android 源码写死并注明，别让后人当成随手写的魔法数删掉。
        // AudioManager.MODE_SILENT=0，MODE_VIBRATE=1，MODE_NORMAL=2。
        final int MODE_S = 0;
        final int MODE_V = 1;
        final int MODE_N = 2;
        final boolean toMute = mode.startsWith("静");
        int r = toMute ? MODE_S
            : mode.startsWith("震") ? MODE_V
            : MODE_N;
        // setRingerMode 在 Android 11+ 上未必有权限，而且**它抛异常是会杀进程的**：
        // 2026-10-07 真机实测「手机静音」→
        //   java.lang.SecurityException: Not allowed to change Do Not Disturb state
        //   at android.media.AudioManager.setRingerMode(AudioManager.java:1177)
        // 这条异常是从无障碍服务的调用栈抛出去的，整个 app 进程（连无障碍服务一起）当场死掉，
        // 助手面板、读屏全部停摆，还得去设置里把无障碍重新打开一次才能复原。
        // 所以这里：先试系统的铃音模式，没权限就降级到"把铃声音量压到 0"，并且说实话。
        try {
          am.setRingerMode(r);
          return new Outcome(true,
              r == MODE_S ? "手机已静音。" : r == MODE_V ? "调成震动了。" : "铃声恢复了。",
              true);
        } catch (Throwable t) {
          android.util.Log.w(TAG, "setRingerMode 没权限，降级到铃声音量，原因: " + t);
        }
        if (toMute) {
          try {
            int before = am.getStreamVolume(AudioManager.STREAM_RING);
            am.setStreamVolume(AudioManager.STREAM_RING, 0, AudioManager.FLAG_SHOW_UI);
            return new Outcome(true,
                "铃音已经压到 0 了（系统不让我直接切静音模式，想彻底静音得自己拉一下状态栏）。"
                    + "之前铃声音量是 " + before + "。",
                true);
          } catch (Throwable t2) {
            return new Outcome(true, "改不了铃音：系统不让我动（" + t2.getClass().getSimpleName()
                + "），得你自己进设置改。", false);
          }
        }
        try {
          am.setStreamVolume(AudioManager.STREAM_RING, 60, AudioManager.FLAG_SHOW_UI);
          return new Outcome(true, "铃音恢复了（系统不让我直接切模式，只能把音量调回来）。", true);
        } catch (Throwable t2) {
          return new Outcome(true, "改不了铃音：系统不让我动（" + t2.getClass().getSimpleName()
              + "）。", false);
        }
      }
    }

    Matcher mv = P_VOLUME.matcher(raw);
    if (mv.find()) {
      try {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return new Outcome(true, "这台机读不到音频服务。", false);
        int want = Math.max(0, Math.min(100, toInt(mv.group(1))));
        am.setStreamVolume(AudioManager.STREAM_MUSIC, want, AudioManager.FLAG_SHOW_UI);
        // 必须回读一次再报：这台机 STREAM_MUSIC 的上限是 15（不是 100），
        // 说"调到 30%"实际是 15%，用户照着说回去只会越说越离谱。
        int got = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return new Outcome(
            true,
            got == want ? "媒体音量调到 " + want + "%。"
                : "媒体音量调不上去：这台机这条流最多 " + max + " 档（你说了 " + want + "%），现在是 "
                    + got + "。",
            true);
      } catch (Throwable t) {
        return new Outcome(true, "音量没调成（" + t.getClass().getSimpleName() + "）。", false);
      }
    }

    if (ql.contains("wifi") || ql.contains("wi-fi") || ql.contains("无线网络")) {
      WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
      if (wm != null) {
        if (ql.contains("打开") || ql.contains("开启") || ql.contains("连上") || ql.contains("联接")) {
          if (!wm.isWifiEnabled()) {
            return wm.setWifiEnabled(true)
                ? new Outcome(true, "Wi-Fi 打开了。", true)
                : new Outcome(true, "Wi-Fi 打不开（系统不让我改，得你自己进设置点）。", false);
          }
          return new Outcome(true, "Wi-Fi 本来就是开的。", false);
        }
        if (ql.contains("关闭") || ql.contains("关掉") || ql.contains("关了") || ql.contains("断开")) {
          if (wm.isWifiEnabled()) {
            return wm.setWifiEnabled(false)
                ? new Outcome(true, "Wi-Fi 关了。", true)
                : new Outcome(true, "Wi-Fi 关不掉（系统不让我改，得你自己进设置点）。", false);
          }
          return new Outcome(true, "Wi-Fi 本来就是关的。", false);
        }
        ConnectivityManager cm =
            (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
        if (!wm.isWifiEnabled()) return new Outcome(true, "Wi-Fi 是关的。", false);
        return new Outcome(
            true,
            (ni != null && ni.isConnected() ? "Wi-Fi 已经连上了" : "Wi-Fi 开着但没连上路由器") + "。",
            false);
      }
    }

    if (ql.contains("移动数据") || ql.contains("数据网络") || ql.contains("用什么网")
        || ql.contains("走的什么网")) {
      ConnectivityManager cm =
          (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
      NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
      if (ni == null) return new Outcome(true, "现在没连任何网络。", false);
      String extra = ni.getSubtypeName();
      return new Outcome(
          true,
          "现在走的是 " + ni.getTypeName() + (extra == null ? "" : "（" + extra + "）") + "。",
          false);
    }

    if (ql.contains("电量") || ql.contains("还剩多少电") || ql.contains("电池")) {
      BatteryInfo bi = readBattery(ctx);
      if (bi == null) return new Outcome(true, "这台机读不到电量。", false);
      return new Outcome(
          true,
          "电量 " + bi.pct + "%" + (bi.charging ? "，正在充电。" : "，没在充电。"),
          false);
    }

    if (ql.contains("存储空间")
        || ql.contains("还剩多少空间")
        || ql.contains("内存还剩")
        || ql.contains("还剩多少存储")) {
      StatFs sf = new StatFs(ctx.getCacheDir().getPath());
      long free = sf.getAvailableBlocksLong() * sf.getBlockSizeLong();
      long total = sf.getBlockCountLong() * sf.getBlockSizeLong();
      return new Outcome(true, "存储还剩 " + gb(free) + "，总共 " + gb(total) + "。", false);
    }

    Matcher mn = P_NAV.matcher(raw);
    if (mn.find()) {
      String dest = mn.group(2);
      Intent geo = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(dest)));
      if (startAny(ctx, geo, MAP_PKGS)) {
        return new Outcome(true, "打开地图了，导航去「" + dest + "」。", true);
      }
      Intent web =
          new Intent(
              Intent.ACTION_VIEW,
              Uri.parse("https://m.amap.com/search?query=" + Uri.encode(dest)));
      if (startAny(ctx, web)) {
        return new Outcome(true, "这台没装地图，先给你打开网页搜「" + dest + "」。", true);
      }
      return new Outcome(true, "这台机没装地图，导航起不来。", false);
    }

    return null;
  }

  /**
   * 匹配优先级：标签完全相等 → 包名后缀相等 → 标签包含（双向）→ 包名包含 → 编辑距离最近。
   * 以前只做前三档，「打开抖音」遇到「抖音短视频」这种还行，但语音识别带尾巴就全丢，
   * 最后丢给模型去编话。补上编辑距离这档，并让调用方拿得到候选。
   */
  private static String launchApp(Context ctx, String name) {
    String key = keyOf(name);
    if (key.isEmpty()) return null;
    List<String[]> apps = installedApps(ctx);

    String bestPkg = null;
    String bestLabel = null;
    int bestRank = 99;
    int bestDist = 99;
    for (String[] app : apps) {
      String l = keyOf(app[0]);
      String p = app[1].toLowerCase(Locale.ROOT);
      int rank = -1;
      if (l.equals(key) || p.endsWith(key) || p.contains("." + key)) rank = 0;
      else if (l.length() >= 2 && key.length() >= 2 && (l.contains(key) || key.contains(l)))
        rank = 1;
      else if (p.contains(key)) rank = 2;
      else if (l.length() >= 2 && key.length() >= 2) {
        int d = editDistance(l, key);
        if (d <= Math.max(1, l.length() / 4)) {
          rank = 3;
          if (d < bestDist) bestDist = d;
        }
      }
      if (rank < 0) continue;
      if (rank < bestRank) {
        bestRank = rank;
        bestPkg = app[1];
        bestLabel = app[0];
        if (rank == 0) break;
      }
    }

    // 兜底④：整名没打中时，拿用户原话里的连续子串去扫应用名。
    // 语音识别经常在应用名后面拖一截修饰语（"打开光遇 那个游戏的"、"打开QQ 右上角那个"），
    // 整串去比必然落空，最后又变成"在桌面找不到，请从应用列表选择"。
    // 句子一般不超过 20 字，子串扫描是 O(n²)，配 72 个应用毫无压力。
    if (bestPkg == null) {
      String raw = name == null ? "" : name;
      for (int len = Math.min(raw.length(), 12); len >= 2; len--) {
        for (int i = 0; i + len <= raw.length(); i++) {
          String slice = keyOf(raw.substring(i, i + len));
          if (slice.length() < 2) continue;
          for (String[] app : apps) {
            String l = keyOf(app[0]);
            String p = app[1].toLowerCase(Locale.ROOT);
            if (l.equals(slice) || (l.length() >= 2 && l.contains(slice)) || p.endsWith(slice)) {
              return startApp(ctx, app[0], app[1]);
            }
          }
        }
      }
    }

    if (bestPkg == null) return null;
    return startApp(ctx, bestLabel, bestPkg);
  }

  /** 起一个已确定包名/类名的应用，返回它的展示名。 */
  private static String startApp(Context ctx, String labelHint, String packageName) {
    try {
      PackageManager pm = ctx.getPackageManager();
      android.content.pm.ResolveInfo ri =
          pm.resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
              .setPackage(packageName), 0);
      String cls = ri == null || ri.activityInfo == null
          ? null : ri.activityInfo.name;
      Intent i = new Intent(Intent.ACTION_MAIN);
      i.addCategory(Intent.CATEGORY_LAUNCHER);
      if (cls != null) i.setClassName(packageName, cls);
      else i.setPackage(packageName);
      i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
      ctx.startActivity(i);
      return labelHint;
    } catch (Throwable t) {
      return null;
    }
  }

  /** 名字相近的几个应用展示名（用来在"没找到"时给用户真机上真实的候选，而不是让用户自己翻）。 */
  private static List<String> nearestApps(Context ctx, String name, int n) {
    String key = keyOf(name);
    List<String> out = new ArrayList<>();
    if (key.length() < 1) return out;
    List<String[]> apps = installedApps(ctx);
    List<String[]> scored = new ArrayList<>();
    for (String[] app : apps) {
      String l = keyOf(app[0]);
      int score = -1;
      if (l.equals(key)) score = 100;
      else if (l.length() >= 2 && key.length() >= 2 && l.contains(key)) score = 80;
      else if (l.length() >= 2 && key.length() >= 2 && key.contains(l)) score = 60;
      else if (editDistance(l, key) <= Math.max(1, l.length() / 4)) score = 30;
      if (score >= 30) scored.add(new String[] {app[0], String.valueOf(score)});
    }
    scored.sort(
        (a, b) -> {
          int c = Integer.compare(Integer.parseInt(b[1]), Integer.parseInt(a[1]));
          return c != 0 ? c : a[0].compareTo(b[0]);
        });
    for (String[] s : scored) {
      out.add(s[0]);
      if (out.size() >= n) break;
    }
    return out;
  }

  /** 经典动态规划编辑距离，用来兜"抖音↔抖音短视频""相机↔相机360"这类差一点点。 */
  private static int editDistance(String a, String b) {
    int last = a.length(), lastB = b.length();
    if (last == 0) return lastB;
    if (lastB == 0) return last;
    int[] prev = new int[lastB + 1];
    for (int j = 0; j <= lastB; j++) prev[j] = j;
    int[] cur = new int[lastB + 1];
    for (int i = 1; i <= last; i++) {
      cur[0] = i;
      for (int j = 1; j <= lastB; j++) {
        int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
      }
      int[] tmp = prev;
      prev = cur;
      cur = tmp;
    }
    return prev[lastB];
  }

  // ---------------------------------------------------------------- 手电筒 / 飞行模式 / 通知栏

  /** 这句话是在说手电筒（闪光灯）。 */
  private static boolean isTorch(String q) {
    String s = q.trim();
    return s.length() <= 20
        && (s.contains("手电") || s.contains("电筒") || s.contains("闪光灯") || s.contains("闪灯"));
  }

  /** 这句话是在说飞行模式。 */
  private static boolean isAirplane(String q) {
    String s = q.trim();
    return s.length() <= 20 && (s.contains("飞行模式") || s.contains("飞行") || s.contains("飞机模式"));
  }

  /** 飞行模式当前是不是开着（Settings.Global，不需要权限就能读）。 */
  private static boolean airplaneState(Context ctx) {
    try {
      return android.provider.Settings.Global.getInt(
              ctx.getContentResolver(), "airplane_mode_on", 0)
          == 1;
    } catch (Throwable ignored) {
      return false;
    }
  }

  /**
   * 真去写飞行模式。没 WRITE_SECURE_SETTINGS（普通用户版 ROM 上就是没有）会抛
   * SecurityException —— {@code Settings.Global.putString} 的这条写入是被签名级权限护着的，
   * manifest 里声明了也白搭，所以异常往上丢，由调用方改成"拉快捷设置 + 说实话"。
   *
   * @return 写成了返回 {@link Outcome}；没权限（抛了）返回 null
   */
  private static Outcome setAirplane(Context ctx, boolean want) {
    try {
      android.provider.Settings.Global.putString(
          ctx.getContentResolver(), "airplane_mode_on", want ? "1" : "0");
      // 光改设置值不广播，很多 ROM 的状态栏图标和射频状态不会跟着动
      Intent b = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
      b.putExtra("state", want);
      ctx.sendBroadcast(b);
      return new Outcome(true, want ? "飞行模式开了。" : "飞行模式关了。", true);
    } catch (Throwable t) {
      android.util.Log.w(TAG, "飞行模式写不进去（没 WRITE_SECURE_SETTINGS）: " + t);
      return null;
    }
  }

  /**
   * 这句话是不是在问通知栏里有什么。
   *
   * <p>刻意<b>不</b>认单独的「通知栏」—— 那是「展开通知栏」的指令（在上面系统键那条处理）。
   * 所以这里必须有"有啥/看看/哪些/谁在找我"这类附加词才算问摘要，
   * 否则一句「通知栏」会被误判成读摘要，用户想拉通知栏却只读了文字。
   */
  private static boolean wantsNotify(String q) {
    String s = q.trim();
    if (s.length() > 24) return false;
    if (s.contains("找我") || s.contains("找我了") || s.contains("谁找我")) return true;
    if (s.contains("通知") || s.contains("通知栏") || s.contains("消息")) {
      return s.contains("有啥") || s.contains("有什么") || s.contains("哪些") || s.contains("几条")
          || s.contains("看看") || s.contains("看下") || s.contains("读一下") || s.contains("念")
          || s.contains("报一下") || s.contains("告诉我") || s.contains("这些")
          || s.contains("没读") || s.contains("未读") || s.contains("消息列表");
    }
    return false;
  }

  /** 「谁在找我 / 通知栏有啥」这类问法 → 念通知摘要；没开通知使用权就弹卡片去开。 */
  private static Outcome notifyOps(Context ctx, String q) {
    if (!wantsNotify(q)) return null;
    if (!AssistantPermit.ready(ctx, AssistantPermit.WHAT_NOTIFY)) {
      AssistantPermit.ask(ctx, AssistantPermit.WHAT_NOTIFY, "你想看通知栏找人");
      return new Outcome(
          true,
          "要读通知栏，得先开「通知使用权」：卡片上点「去开通知使用权」把 DSHA 勾上，"
              + "以后问我「谁在找我」我就能念。银行和支付类应用的通知我一条都不会念。",
          false);
    }
    String text = DshaNotificationListener.summarize(8);
    if (text == null) return new Outcome(true, "通知使用权是开着的，不过现在没收到通知。", false);
    return new Outcome(true, text, false);
  }

  private static void key(String name) {
    try {
      DshaAccessibilityService.uiKey(name);
    } catch (Throwable ignored) {
    }
  }

  private static Outcome media(Context ctx, int code, String reply) {
    if (Build.VERSION.SDK_INT >= 19) {
      try {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
          am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
          am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
          return new Outcome(true, reply, false);
        }
      } catch (Throwable ignored) {
      }
    }
    return Outcome.no();
  }

  private static boolean hasDigit(String s) {
    for (int i = 0; i < s.length(); i++) if (Character.isDigit(s.charAt(i))) return true;
    return false;
  }

  private static String humanSeconds(int s) {
    if (s < 60) return s + " 秒";
    if (s % 3600 == 0) return (s / 3600) + " 小时";
    if (s % 60 == 0) return (s / 60) + " 分钟";
    return (s / 60) + " 分 " + (s % 60) + " 秒";
  }

  private static int toSeconds(String num, String unit) {
    int n = toInt(num);
    if (n <= 0) return -1;
    if (unit == null) return n;
    switch (unit) {
      case "小时":
      case "个小时":
        return n * 3600;
      case "分钟":
      case "分":
        return n * 60;
      default:
        return n;
    }
  }

  /** 小时：把「下午七点」这种转成 19。 */
  private static int toHour(String num, String period) {
    int h = toInt(num);
    if (h < 0) return -1;
    if ("下午".equals(period) || "晚上".equals(period)) {
      if (h < 12) h += 12;
    }
    if (h == 24) h = 0;
    return h;
  }

  /** 阿拉伯数字 + 中文数字（一到九十九）。 */
  private static int toInt(String s) {
    if (s == null) return -1;
    s = s.trim();
    if (s.isEmpty()) return -1;
    if (s.matches("[0-9]+")) {
      try {
        return Integer.parseInt(s);
      } catch (Throwable t) {
        return -1;
      }
    }
    return chineseToInt(s);
  }

  private static int chineseToInt(String s) {
    String[] d = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
    int total = 0, section = 0, number = 0;
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      int digit = -1;
      for (int k = 0; k < d.length; k++) if (d[k].charAt(0) == ch) digit = k;
      if (ch == '两') digit = 2;
      if (digit >= 0) {
        number = digit;
      } else if (ch == '十') {
        section += (number == 0 ? 1 : number) * 10;
        number = 0;
      } else {
        return -1;
      }
    }
    total = section + number;
    return total <= 0 ? -1 : total;
  }

  /** {@code Intent.EXTRA} 里 {@code android.speech.extra.QUERY} 的常量名，避免硬编码字符串。 */
  private static final class Search {
    static final String EXTRA = "query";
  }

  /** 迷你四则运算解析器（递归下降）：+ - * / % ^ 括号、一元负号。 */
  private static final class Calc {
    private final String s;
    private int i = 0;

    Calc(String s) {
      this.s = s.replace('×', '*').replace('÷', '/').replace('（', '(').replace('）', ')');
    }

    double eval() throws Exception {
      double v = expr();
      skip();
      if (i < s.length()) throw new Exception("尾部有多余字符");
      return v;
    }

    private void skip() {
      while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private double expr() throws Exception {
      double v = term();
      for (; ; ) {
        skip();
        if (i >= s.length()) return v;
        char c = s.charAt(i);
        if (c == '+') {
          i++;
          v += term();
        } else if (c == '-') {
          i++;
          v -= term();
        } else return v;
      }
    }

    private double term() throws Exception {
      double v = power();
      for (; ; ) {
        skip();
        if (i >= s.length()) return v;
        char c = s.charAt(i);
        if (c == '*') {
          i++;
          v *= power();
        } else if (c == '/') {
          i++;
          double d = power();
          if (d == 0) throw new Exception("除以 0");
          v /= d;
        } else if (c == '%') {
          i++;
          double d = power();
          if (d == 0) throw new Exception("取余 0");
          v %= d;
        } else return v;
      }
    }

    private double power() throws Exception {
      double v = unary();
      skip();
      if (i < s.length() && s.charAt(i) == '^') {
        i++;
        v = Math.pow(v, power());
      }
      return v;
    }

    private double unary() throws Exception {
      skip();
      if (i >= s.length()) throw new Exception("缺数字");
      char c = s.charAt(i);
      if (c == '-') {
        i++;
        return -unary();
      }
      if (c == '+') {
        i++;
        return unary();
      }
      if (c == '(') {
        i++;
        double v = expr();
        skip();
        if (i >= s.length() || s.charAt(i) != ')') throw new Exception("括号不匹配");
        i++;
        return v;
      }
      return number();
    }

    private double number() throws Exception {
      skip();
      int start = i;
      while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
      if (start == i) throw new Exception("不是数字");
      return Double.parseDouble(s.substring(start, i));
    }
  }
}
