package com.deepseekharness.app.assistant;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 助手自己攒的**技能**（用户 2026-10-07 明确要求必须有）。
 *
 * <p>一个技能就是私有目录里一个 {@code *.skill.md} 文本文件，长这样：
 *
 * <pre>
 * name: 付款提醒
 * triggers: 这单什么时候付, 帮我看看该付多少
 * style: 先报金额和到期日，别长篇大论
 * chip: 这单我该什么时候付
 * </pre>
 *
 * <p>三个字段各管一段：
 * <ul>
 *   <li>{@code triggers} —— 命中任一触发词就算这个技能当家：答问时把 {@code style} 追加进系统提示，
 *       回答就按它来；</li>
 *   <li>{@code style} —— 给模型的额外要求（也可以留空，只当建议 chip 用）；</li>
 *   <li>{@code chip} —— 直接塞进面板推荐那一行，用户能一眼点到。</li>
 * </ul>
 *
 * <p>能自己写文件这件事要收口：文件名只允许中文/字母/数字/下划线，
 * 顺手把 {@code ..}、路径分隔符、控制字符全清掉，技能目录固定，
 * 保证「用户说一句自然语言」不会变成任意文件写入。
 *
 * <p>技能是**纯文本、可导出、可手改**的：改完不用重装 APK，
 * 直接拿文件管理器改（路径见 {@link #dir(Context)}）或者让助手自己写。
 */
final class AssistantSkills {

  static final class Skill {
    final String name;
    final List<String> triggers;
    /** 追加进系统提示的要求，空表示只做触发/建议用。 */
    final String style;
    /** 塞进推荐 chip 的文案，空表示不占推荐位。 */
    final String chip;

    Skill(String name, List<String> triggers, String style, String chip) {
      this.name = name;
      this.triggers = triggers;
      this.style = style == null ? "" : style;
      this.chip = chip == null ? "" : chip;
    }

    boolean matches(String question) {
      if (question == null || triggers.isEmpty()) return false;
      String q = question.toLowerCase(Locale.ROOT);
      for (String t : triggers) {
        if (t != null && !t.isEmpty() && q.contains(t.toLowerCase(Locale.ROOT))) return true;
      }
      return false;
    }
  }

  private AssistantSkills() {}

  static File dir(Context ctx) {
    File d = new File(ctx.getFilesDir(), "dsha-skills");
    if (!d.exists()) d.mkdirs();
    return d;
  }

  /** 读全部技能；单行坏掉就跳过，不让一个坏文件拖垮整个推荐。 */
  static List<Skill> load(Context ctx) {
    List<Skill> out = new ArrayList<>();
    if (ctx == null) return out;
    File[] files = dir(ctx).listFiles();
    if (files == null) return out;
    for (File f : files) {
      if (f == null || !f.getName().endsWith(".skill.md")) continue;
      try (BufferedReader r =
          new BufferedReader(new InputStreamReader(new java.io.FileInputStream(f), "UTF-8"))) {
        String name = "";
        StringBuilder style = new StringBuilder();
        StringBuilder chip = new StringBuilder();
        List<String> triggers = new ArrayList<>();
        String line;
        boolean inBody = false;
        while ((line = r.readLine()) != null) {
          String t = line.trim();
          if (t.startsWith("#") || t.startsWith("//")) inBody = true;
          if (t.startsWith("triggers:")) {
            for (String part : t.substring("triggers:".length()).split("[,，]")) {
              String k = part.trim();
              if (!k.isEmpty()) triggers.add(k);
            }
          } else if (t.startsWith("style:")) {
            style.append(t.substring("style:".length()).trim());
          } else if (t.startsWith("chip:")) {
            chip.append(t.substring("chip:".length()).trim());
          } else if (!t.isEmpty() && !t.startsWith("name:") && !inBody && name.isEmpty()) {
            name = t;
          }
          if (t.startsWith("name:")) name = t.substring("name:".length()).trim();
        }
        if (name.isEmpty()) name = f.getName().replace(".skill.md", "");
        out.add(new Skill(name, triggers, style.toString(), chip.toString()));
      } catch (Throwable ignored) {
      }
    }
    return out;
  }

  /** 命中的第一个技能（技能按顺序排，先建的先命中）。 */
  static Skill match(Context ctx, String question) {
    for (Skill s : load(ctx)) if (s.matches(question)) return s;
    return null;
  }

  /** 有 chip 的技能（拿去填推荐位）。 */
  static List<Skill> chipped(Context ctx) {
    List<Skill> out = new ArrayList<>();
    for (Skill s : load(ctx)) if (!s.chip.isEmpty()) out.add(s);
    return out;
  }

  /**
   * 新建技能。入参都是已经从自然语言里切出来的片段（来自
   * {@link AssistantCommands} 的「建个技能：……」）：
   * 名字由调用方给（或取触发词），触发词至少一条。
   */
  static synchronized String create(Context ctx, String name, List<String> triggers,
      String style, String chip) throws Exception {
    String safe = safeName(name == null ? (triggers.isEmpty() ? "skill" : triggers.get(0)) : name);
    if (safe.isEmpty()) throw new IllegalStateException("技能名没法用");
    List<String> keys = new ArrayList<>();
    for (String t : triggers) {
      String k = oneLine(t, 24);
      if (!k.isEmpty() && !keys.contains(k)) keys.add(k);
    }
    if (keys.isEmpty()) throw new IllegalStateException("得给一句触发的话");
    File f = new File(dir(ctx), safe + ".skill.md");
    StringBuilder sb = new StringBuilder();
    sb.append("# ").append(safe).append('\n');
    sb.append("name: ").append(safe).append('\n');
    sb.append("triggers: ").append(String.join(", ", keys)).append('\n');
    if (style != null && !style.trim().isEmpty()) {
      sb.append("style: ").append(oneLine(style, 120)).append('\n');
    }
    if (chip != null && !chip.trim().isEmpty()) {
      sb.append("chip: ").append(oneLine(chip, 24)).append('\n');
    }
    try (Writer w = new OutputStreamWriter(new java.io.FileOutputStream(f, false),
        StandardCharsets.UTF_8)) {
      w.write(sb.toString());
    }
    return safe;
  }

  static synchronized boolean delete(Context ctx, String name) {
    if (ctx == null || name == null) return false;
    File f = new File(dir(ctx), safeName(name) + ".skill.md");
    return f.exists() && f.delete();
  }

  static synchronized List<String> names(Context ctx) {
    List<String> out = new ArrayList<>();
    for (Skill s : load(ctx)) out.add(s.name);
    Collections.sort(out);
    return out;
  }

  /** 给用户看的清单（含触发词与是否有style）。 */
  static String listText(Context ctx) {
    List<Skill> all = load(ctx);
    if (all.isEmpty()) return "现在还没攒下技能。你可以跟我说「建个技能：这单什么时候付，先报金额」——"
        + "我会把它记进技能本子，以后你再说这句就按那个方式答。";
    StringBuilder sb = new StringBuilder("会这些技能（" + all.size() + "条）：\n");
    for (Skill s : all) {
      sb.append("· ").append(s.name).append("：").append(String.join(" / ", s.triggers));
      if (!s.style.isEmpty()) sb.append(" → ").append(s.style);
      sb.append('\n');
    }
    return sb.toString().trim();
  }

  // ---------------------------------------------------------------- 内部

  /** 文件名收口：只允许中文/字母/数字/下划线/短横。 */
  private static String safeName(String raw) {
    String s = raw.replace("\n", " ").replace("\r", "").trim();
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < s.length() && out.length() < 40; i++) {
      char c = s.charAt(i);
      boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
          || (c >= 0x4e00 && c <= 0x9fa5) || c == '_' || c == '-';
      if (ok) out.append(c);
    }
    return out.toString();
  }

  private static String oneLine(String s, int max) {
    String t = s.replace('\n', ' ').replace('\r', ' ').trim();
    return t.length() > max ? t.substring(0, max) : t;
  }
}
