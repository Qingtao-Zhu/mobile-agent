package com.deepseekharness.app.assistant;

import android.content.Context;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 面板底部那排「Siri 建议」。
 *
 * <p>Siri 的一半好感来自它**猜得到你想问什么**，而不是等你憋出一句完整问题。
 * 这里的推荐位按四层拼装，越靠前越「像你自己会说的话」：
 * <ol>
 *   <li><b>记忆</b>：你平时对着面板点得最多的那几句原话（{@link AssistantMemory}）。
 *       不用模型、断网也生效，而且全是你自己敲过的字，不会编；</li>
 *   <li><b>技能</b>：你自己攒下的 {@code chip}（{@link AssistantSkills}）；</li>
 *   <li><b>这页现想</b>：从当前屏幕里**照抄出来的句子**加工成问题，
 *       所以不管你在微信还是浏览器，推荐都是贴着这一页的（{@link #fromScreen}）；</li>
 *   <li><b>按包名/时间猜</b>：兜底池，永远填不满一排。</li>
 * </ol>
 *
 * <p>文案口径（2026-10-07 用户反馈）：旧版那句「这个页面在说什么？用三句话讲给我」
 * 「帮我写一句简短的回复」「帮我总结今天做过的事」「讲个很短的冷笑话」「现在几点」
 * 太像说明书和功能自检表，用户明确说不喜欢。现在统一改成人开口问的短句，
 * 短的、口语的、不带"请用三句话"这种对机器下指令的说法。
 *
 * <p>纯规则，零网络、零模型调用，所以永远瞬时、永远不会因为 Key 挂了而空一排。
 */
final class AssistantSuggestions {

  /** 记忆攒够这么多条才拿来当推荐，太早顶上来会把人吓跑（前三句还是陌生话）。 */
  private static final int MEMORY_MIN_RECORDS = 8;

  private AssistantSuggestions() {}

  /** 不带上下文的入口（有些调用方只有包名，没有屏幕内容）。 */
  static List<String> build(String pkg, String appLabel, boolean hasScreen, boolean hasVision) {
    return build(null, pkg, appLabel, hasScreen, hasVision, null);
  }

  /**
   * 完整拼装推荐位。
   *
   * @param ctx        给「记忆 / 技能」用（传 null 就只走规则兜底）
   * @param screenText 当前屏幕的读屏文本，用来按这一页现想推荐
   */
  static List<String> build(Context ctx, String pkg, String appLabel, boolean hasScreen,
      boolean hasVision, String screenText) {
    List<String> out = new ArrayList<>();
    String p = pkg == null ? "" : pkg.toLowerCase(Locale.ROOT);
    String n = appLabel == null ? "" : appLabel;

    // ---------- ① 记忆：你平时点得最多的那几句话 ----------
    if (ctx != null && AssistantMemory.size(ctx) >= MEMORY_MIN_RECORDS) {
      out.addAll(AssistantMemory.frequent(ctx, 2));
    }

    // ---------- ② 技能：自己攒的（AssistantSkills.chip） ----------
    if (ctx != null) {
      for (AssistantSkills.Skill sk : AssistantSkills.chipped(ctx)) {
        if (!sk.chip.isEmpty()) out.add(sk.chip);
      }
    }

    // ---------- ③ 按这页现想 ----------
    if (hasScreen) {
      out.addAll(fromScreen(screenText));
    } else if (hasVision) {
      // 反自动化的应用（微信列表那类）：读不到字，只能看图 → 给看图的问题
      out.add("这屏上都有啥");
    }

    // ---------- ④ 按应用猜 ----------
    if (p.contains("tencent.mm") || p.contains("wechat") || "微信".equals(n)) {
      out.add("对方这句到底啥意思");
      out.add("有没有非回不可的消息");
      out.add("帮我接一句：行，我看看");
    } else if (isBrowser(p)) {
      out.add("这篇讲了些什么");
      out.add("这页有没有便宜可占");
    } else if (isGallery(p)) {
      out.add("这照片里都有啥");
    } else if (isMusic(p)) {
      out.add("在放哪一首");
    } else if (isMail(p)) {
      out.add("这封邮件要我干什么");
    } else if (isMap(p)) {
      out.add("还要多久到");
    }

    // ---------- ⑤ 按时间猜（只分早/午/深夜三档，别编得太满） ----------
    int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
    if (h >= 5 && h < 11) out.add("今天先干哪三件事");
    else if (h >= 11 && h < 14) out.add("中午吃啥，给几个选");
    else if (h >= 22 || h < 5) out.add("今天都干了些啥");

    // ---------- ⑥ 兜底池 ----------
    // 每秒钟轮一下顺序，同一条建议不会每次唤醒都顶在同一位，
    // 又不至于抖得人眼晕（一秒内是稳定的）。纯规则，不联网。
    List<String> pool = new ArrayList<>();
    pool.add("手机还剩多少电");
    pool.add("帮我回一句，别太客套");
    pool.add("定个提醒，半小时后叫我");
    pool.add("讲个好笑的");
    pool.add("帮我开一下 Wi-Fi");
    pool.add("现在几点了");
    int turn = (int) (System.currentTimeMillis() / 1000L);
    Collections.rotate(pool, turn % pool.size());
    out.addAll(pool);

    // 去重并截断（横排 chip 一屏只露得下两个左右，前两条最关键）
    List<String> dedup = new ArrayList<>();
    for (String s : out) if (s != null && !s.isEmpty() && !dedup.contains(s)) dedup.add(s);
    return dedup.subList(0, Math.min(6, dedup.size()));
  }

  // ---------------------------------------------------------------- 按页面现想

  /**
   * 从当前屏幕的读屏文本里**现想**推荐。
   *
   * <p>办法是：把 dump 里那些 {@code [N] "文字"} 条目里的真句子抽出来，
   * 直接拿它当问题的题眼（「讲讲『这单什么时候付』这句」）——
   * 句子是从这页抄的，所以换任何应用都成立，不用一个应用写一套模板。
   * 再看整页里有没有金额、未读、距离这类信号，补一两条对应的问题。
   *
   * <p>只做加法和照抄，不做"猜你 intent"：猜错了推荐位就废了，
   * 而从屏幕上抄来的句子点下去一定问得到东西。
   */
  private static List<String> fromScreen(String dump) {
    List<String> out = new ArrayList<>();
    if (dump == null || dump.isEmpty()) return out;
    List<String> lines = entryTexts(dump);
    int n = lines.size();
    if (n == 0) return out;

    // 页面上最长/最像话的几条 → 「讲讲『…』这句」，最多两条
    int shown = 0;
    for (String s : lines) {
      if (shown >= 2) break;
      if (s.length() < 5 || s.length() > 42) continue;
      out.add("讲讲「" + clip(s, 9) + "」这句");
      shown++;
    }
    if (n >= 3) out.add("这几行挑重点，三句话讲完");

    if (hasMoney(dump)) out.add("这单一共多少钱");
    if (hasUnread(dump)) out.add("这几条谁在找我");
    if (hasPlace(dump)) out.add("还要多久到");

    while (out.size() > 4) out.remove(out.size() - 1);
    return out;
  }

  /** dump 里的条目行抽成纯文字（读屏格式固定是 {@code [序号] "文字" 可点击 …}）。 */
  private static List<String> entryTexts(String dump) {
    List<String> out = new ArrayList<>();
    for (String line : dump.split("\n")) {
      String t = line.trim();
      if (!t.startsWith("[")) continue;
      int a = t.indexOf('"');
      int b = t.lastIndexOf('"');
      if (a < 0 || b <= a) continue;
      String s = t.substring(a + 1, b).trim();
      if (!s.isEmpty()) out.add(s);
    }
    return out;
  }

  private static boolean hasMoney(String s) {
    return s.contains("¥") || s.contains("￥") || s.contains("元");
  }

  private static boolean hasUnread(String s) {
    return s.contains("未读") || s.contains("新消息") || s.contains("条消息");
  }

  private static boolean hasPlace(String s) {
    return s.contains("km") || s.contains("公里") || s.contains("千米") || s.contains("分钟");
  }

  private static String clip(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max) + "…";
  }

  // ---------------------------------------------------------------- 包名识别

  private static boolean isBrowser(String p) {
    return p.contains("browser")
        || p.contains("chrome")
        || p.contains("edge")
        || p.contains("firefox")
        || p.contains("via")
        || p.contains("quark")
        || p.contains("uc")
        || p.contains("baidu")
        || p.contains("sogou");
  }

  private static boolean isGallery(String p) {
    return p.contains("gallery") || p.contains("photos") || p.contains("album") || p.contains("photo");
  }

  private static boolean isMusic(String p) {
    return p.contains("music")
        || p.contains("cloudmusic")
        || p.contains("kugou")
        || p.contains("kuwo")
        || p.contains("qqmusic");
  }

  private static boolean isMail(String p) {
    return p.contains("mail") || p.contains("email") || p.contains("outlook");
  }

  private static boolean isMap(String p) {
    return p.contains("map") || p.contains("navi") || p.contains("amap") || p.contains("baidumap");
  }
}
