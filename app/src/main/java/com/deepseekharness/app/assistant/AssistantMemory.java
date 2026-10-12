package com.deepseekharness.app.assistant;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「记住你爱怎么说」的小本子。
 *
 * <p>每问答一条就往私有目录里写一行（jsonl，一行一条，追加写），
 * 攒够了回头统计**出现次数最多的说法**，原封不动地拿去当推荐 chip。
 * 这样用着用着，面板自己就会把「你平时问的那句」顶到最前面 —— 这是「自优化」里
 * 唯一不需要模型、断网也生效、还不会乱编的那部分。
 *
 * <p>为什么是原话而不是「意图分类」：中文里同一件事能说二十种，
 * 归类一旦猜错，建议就是错的；而**用户自己敲过的原话一定是他想点的东西**。
 *
 * <p>隐私：文件在 app 私有目录（data/data/com.dsh.client/files/dsha/），
 * 别的 App 读不到；内容只进这个文件的统计，不会发给模型。
 */
final class AssistantMemory {

  private static final String FILE = "dsha-assistant-memory.jsonl";
  private static final int KEEP_LINES = 240;
  private static volatile List<Count> cache;

  private AssistantMemory() {}

  /** 记一条：question 是用户原话，local 表示本机就答了（没走网络）。 */
  static void record(Context ctx, String question, boolean local, String app) {
    if (ctx == null || question == null) return;
    final String q = oneLine(question, 80);
    if (q.isEmpty()) return;
    // 「建个技能：…」「有哪些技能」这种是**管理命令**，不是你平时问的那句话。
    // 记进习惯本子会污染推荐（"有几个技能"会被当成高频原话顶到第一位），所以挡掉。
    final String low = q.toLowerCase(java.util.Locale.ROOT);
    if (low.contains("技能") || low.contains("skill")) return;
    try {
      File dir = new File(ctx.getFilesDir(), "dsha");
      if (!dir.exists() && !dir.mkdirs()) return;
      File f = new File(dir, FILE);
      try (Writer w =
          new OutputStreamWriter(new java.io.FileOutputStream(f, true), StandardCharsets.UTF_8)) {
        w.write(System.currentTimeMillis() + "|" + (local ? "L" : "M") + "|"
            + oneLine(app == null ? "" : app, 24) + "|" + q + "\n");
      }
      trim(f, KEEP_LINES);
      cache = null; // 内存统计缓存作废
    } catch (Throwable ignored) {
      // 写不进去（存储满了/没权限）不影响主流程，习惯记忆是锦上添花
    }
  }

  /**
   * 用得最多的说法（原话），降序。
   * 过滤掉太短的（"好""嗯"这种点了也白点）和纯符号的。
   */
  static List<String> frequent(Context ctx, int want) {
    if (ctx == null || want <= 0) return new ArrayList<>();
    try {
      List<Count> all = scan(ctx);
      Map<String, Integer> counts = new LinkedHashMap<>();
      for (Count c : all) {
        if (c.text.length() < 4) continue;
        if (!isUseless(c.text)) counts.merge(c.text, 1, Integer::sum);
      }
      List<Map.Entry<String, Integer>> rows = new ArrayList<>(counts.entrySet());
      rows.sort((a, b) -> b.getValue().compareTo(a.getValue()));
      List<String> out = new ArrayList<>();
      for (Map.Entry<String, Integer> r : rows) {
        if (out.size() >= want) break;
        if (!out.contains(r.getKey())) out.add(r.getKey());
      }
      return out;
    } catch (Throwable ignored) {
      return new ArrayList<>();
    }
  }

  /** 我这本子记了多少条（给用户看「你确实在用」用）。 */
  static int size(Context ctx) {
    try {
      return scan(ctx).size();
    } catch (Throwable ignored) {
      return 0;
    }
  }

  /** 「忘掉我的习惯」：把本子清空。 */
  static void forget(Context ctx) {
    cache = null;
    if (ctx == null) return;
    try {
      File f = new File(new File(ctx.getFilesDir(), "dsha"), FILE);
      if (f.exists() && !f.delete()) {
        java.io.FileOutputStream sink = new java.io.FileOutputStream(f, false);
        try {
          sink.write(new byte[0]);
        } finally {
          sink.close();
        }
      }
    } catch (Throwable ignored) {
    }
  }

  // ---------------------------------------------------------------- 内部

  private static List<Count> scan(Context ctx) throws Exception {
    if (cache != null) return cache;
    File f = new File(new File(ctx.getFilesDir(), "dsha"), FILE);
    if (!f.exists()) return new ArrayList<>();
    List<Count> rows = new ArrayList<>();
    try (BufferedReader r =
        new BufferedReader(new InputStreamReader(new java.io.FileInputStream(f), "UTF-8"))) {
      String line;
      while ((line = r.readLine()) != null) {
        if (line.trim().isEmpty()) continue;
        String[] parts = line.split("\\|", 4);
        if (parts.length < 4) continue;
        rows.add(new Count(parts[0], parts[1].equals("L"), parts[3]));
      }
    }
    // 全在内存里排好序，下面 frequent 直接数就行
    cache = rows;
    return rows;
  }

  private static void trim(File f, int keep) throws Exception {
    List<String> tail = new ArrayList<>();
    try (BufferedReader r =
        new BufferedReader(new InputStreamReader(new java.io.FileInputStream(f), "UTF-8"))) {
      String line;
      while ((line = r.readLine()) != null) tail.add(line);
    }
    if (tail.size() <= keep) return;
    try (Writer w = new OutputStreamWriter(new java.io.FileOutputStream(f, false), "UTF-8")) {
      for (int i = tail.size() - keep; i < tail.size(); i++) w.write(tail.get(i) + "\n");
    }
  }

  /** 「好」「嗯」「?"这种点了也白点，不算习惯。 */
  private static boolean isUseless(String s) {
    String t = s.trim();
    if (t.isEmpty() || t.length() < 4) return true;
    boolean hasLetterOrDigit = false;
    for (int i = 0; i < t.length(); i++) {
      if (Character.isLetterOrDigit(t.charAt(i))) hasLetterOrDigit = true;
    }
    return !hasLetterOrDigit;
  }

  private static String oneLine(String s, int max) {
    String t = s.replace('\n', ' ').replace('\r', ' ').trim();
    return t.length() > max ? t.substring(0, max) : t;
  }

  private static final class Count {
    final String time;
    final boolean local;
    final String text;

    Count(String time, boolean local, String text) {
      this.time = time;
      this.local = local;
      this.text = text;
    }
  }
}
