package com.deepseekharness.app.assistant;

import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 「常用应用 + 目标按钮」的可扩展配置（2026-10-11 大众化改造核心之一）。
 *
 * <p><b>为什么要有它</b>：原来助手每次都要"读屏 → 喂模型 → 猜点哪"，
 * 费 token、慢，而且同一个发送按钮每天猜一遍。用户要求：
 * <pre>
 *   通过可扩展的配置机制管理常用应用和目标按钮，
 *   不要把所有应用名称和按钮坐标硬编码在程序中。
 * </pre>
 *
 * <p><b>关键设计：存特征，不存坐标。</b>
 * 规则里只记「这个按钮长什么样」（contentDescription / viewId / 文本 / 层级路径），
 * <b>绝不记屏幕坐标</b>。理由很简单：坐标换个分辨率、换个 App 版本、
 * 换个语言就全错，而且错了还很危险（点到隔壁的"删除"）。
 * 坐标只在"当前这一刻的界面"里由 {@link ActionTargetLocator} 现场算出来。
 *
 * <p><b>存放位置</b>：`/sdcard/dsh/action-targets.json` —— 与 assistant-voice.json 同目录，
 * 用户/ADB 都能直接改；App 内也有增删改的入口（见 PermissionHub 的"目标管理"）。
 *
 * <p><b>schema 版本化</b>：顶层 {@code schema} 字段。以后结构变了要靠它做迁移，
 * 别指望 JSON 能自己兼容。
 */
final class ActionTargetStore {

  private static final String TAG = "DSHA/Targets";

  /**
   * 配置文件名。实际路径由 {@link ConfigStoreLite} 决定——
   * <b>应用内部存储优先</b>（零权限），旧位置 `/sdcard/dsh/` 只作一次性迁移来源。
   * （2026-10-11 第二阶段 P0-1：原来直接写死 /sdcard，普通用户没宽存储权限就用不了。）
   */
  private static final String NAME = "action-targets.json";

  /** @deprecated 仅为兼容旧文档/日志展示；真实路径走 {@link ConfigStoreLite}。 */
  @Deprecated
  private static final String PATH = "/sdcard/dsh/" + NAME;

  private static final int SCHEMA = 1;

  /** 操作风险等级：决定要不要二次确认。 */
  static final class Risk {
    /** 只是看看/打开，无副作用。 */
    static final String SAFE = "safe";
    /** 发送消息、提交表单 —— 会产生实际后果，默认要确认。 */
    static final String SEND = "send";
    /** 付款、转账 —— 最高级别，永远确认。 */
    static final String PAY = "pay";
    /** 删除、清空 —— 不可逆，永远确认。 */
    static final String DELETE = "delete";

    private Risk() {}

    static boolean needsConfirm(String risk) {
      return SEND.equals(risk) || PAY.equals(risk) || DELETE.equals(risk);
    }

    static String label(String risk) {
      if (PAY.equals(risk)) return "涉及付款";
      if (DELETE.equals(risk)) return "不可逆删除";
      if (SEND.equals(risk)) return "会发出消息";
      return "无副作用";
    }
  }

  /** 一条定位规则：怎么认出这个控件。 */
  static final class Rule {
    /** 匹配依据：text / contentDescription / viewId / className。 */
    final String by;
    final String value;
    /** true = 完全相等才算命中；false = 包含即可（默认 false，更宽松）。 */
    final boolean exact;

    Rule(String by, String value, boolean exact) {
      this.by = by == null ? "" : by;
      this.value = value == null ? "" : value;
      this.exact = exact;
    }

    boolean matches(String candidate) {
      if (candidate == null || value.isEmpty()) return false;
      String c = candidate.trim();
      return exact ? c.equals(value) : c.contains(value);
    }

    JSONObject toJson() {
      JSONObject o = new JSONObject();
      try {
        o.put("by", by);
        o.put("value", value);
        if (exact) o.put("exact", true);
      } catch (Throwable ignored) {
      }
      return o;
    }

    static Rule fromJson(JSONObject o) {
      if (o == null) return null;
      String by = o.optString("by", "");
      String value = o.optString("value", "");
      if (by.isEmpty() || value.isEmpty()) return null;
      return new Rule(by, value, o.optBoolean("exact", false));
    }
  }

  /** 一个操作目标（例如"Telegram 的发送按钮"）。 */
  static final class Target {
    String id;
    String appLabel;
    /** 目标应用的包名候选（同名 App 常有多个包，如 Telegram 有 web 版）。 */
    List<String> packages = new ArrayList<>();
    String name;
    String risk = Risk.SAFE;
    /** 有序候选规则：逐个尝试，全不中才升级到 AI / 校准。 */
    List<Rule> rules = new ArrayList<>();
    /** 是否强制二次确认；默认跟随 risk。 */
    boolean confirm;
    /** 曾经验证通过的设备画像（{@link Compat#deviceProfile}）。不匹配时提示可能失效。 */
    List<String> verifiedOn = new ArrayList<>();

    boolean shouldConfirm() {
      return confirm || Risk.needsConfirm(risk);
    }

    JSONObject toJson() {
      JSONObject o = new JSONObject();
      try {
        o.put("id", id);
        o.put("appLabel", appLabel);
        JSONArray ps = new JSONArray();
        for (String p : packages) ps.put(p);
        o.put("packages", ps);
        o.put("name", name);
        o.put("risk", risk);
        JSONArray rs = new JSONArray();
        for (Rule r : rules) rs.put(r.toJson());
        o.put("rules", rs);
        o.put("confirm", confirm);
        JSONArray vs = new JSONArray();
        for (String v : verifiedOn) vs.put(v);
        o.put("verifiedOn", vs);
      } catch (Throwable ignored) {
      }
      return o;
    }

    static Target fromJson(JSONObject o) {
      if (o == null) return null;
      Target t = new Target();
      t.id = o.optString("id", "");
      t.appLabel = o.optString("appLabel", "");
      t.name = o.optString("name", "");
      t.risk = o.optString("risk", Risk.SAFE);
      t.confirm = o.optBoolean("confirm", false);
      if (t.id.isEmpty()) return null; // 没 id 的条目没法引用，丢弃
      JSONArray ps = o.optJSONArray("packages");
      if (ps != null) {
        for (int i = 0; i < ps.length(); i++) {
          String p = ps.optString(i, "");
          if (!p.isEmpty()) t.packages.add(p);
        }
      }
      JSONArray rs = o.optJSONArray("rules");
      if (rs != null) {
        for (int i = 0; i < rs.length(); i++) {
          Rule r = Rule.fromJson(rs.optJSONObject(i));
          if (r != null) t.rules.add(r);
        }
      }
      JSONArray vs = o.optJSONArray("verifiedOn");
      if (vs != null) {
        for (int i = 0; i < vs.length(); i++) {
          String v = vs.optString(i, "");
          if (!v.isEmpty()) t.verifiedOn.add(v);
        }
      }
      return t;
    }
  }

  private ActionTargetStore() {}

  // ---------------------------------------------------------------- 读

  /** 读全部目标。文件不存在 / 坏了都返回空表（不抛，调用方按"没配"处理）。 */
  static List<Target> loadAll(Context ctx) {
    List<Target> out = new ArrayList<>();
    JSONObject root = readRoot(ctx);
    if (root == null) return out;
    if (root.optInt("schema", 0) != SCHEMA) {
      android.util.Log.w(TAG, "schema 不匹配（期望 " + SCHEMA + "，实际 "
          + root.optInt("schema", 0) + "），按空表处理");
      return out;
    }
    JSONArray arr = root.optJSONArray("targets");
    if (arr == null) return out;
    for (int i = 0; i < arr.length(); i++) {
      Target t = Target.fromJson(arr.optJSONObject(i));
      if (t != null) out.add(t);
    }
    return out;
  }

  /** 按 id 取一个目标；没有返回 null。 */
  static Target findById(Context ctx, String id) {
    if (id == null) return null;
    for (Target t : loadAll(ctx)) {
      if (id.equals(t.id)) return t;
    }
    return null;
  }

  /**
   * 找出适用于某应用（包名）的全部目标。
   *
   * <p>同时匹配 {@code packages} 里的任意一个包名 —— 同一个 App 常有多包
   * （如 Telegram 的 `org.telegram.messenger.web` 与 `org.telegram.messenger`）。
   */
  static List<Target> forPackage(Context ctx, String pkg) {
    List<Target> out = new ArrayList<>();
    if (pkg == null || pkg.isEmpty()) return out;
    for (Target t : loadAll(ctx)) {
      if (t.packages.contains(pkg)) out.add(t);
    }
    return out;
  }

  // ---------------------------------------------------------------- 写

  /** 新增或覆盖一个目标（按 id 去重），返回是否写盘成功。 */
  static boolean upsert(Context ctx, Target target) {
    if (target == null || target.id == null || target.id.isEmpty()) return false;
    List<Target> all = loadAll(ctx);
    boolean replaced = false;
    for (int i = 0; i < all.size(); i++) {
      if (target.id.equals(all.get(i).id)) {
        all.set(i, target);
        replaced = true;
        break;
      }
    }
    if (!replaced) all.add(target);
    return writeAll(ctx, all);
  }

  static boolean remove(Context ctx, String id) {
    List<Target> all = loadAll(ctx);
    boolean removed = false;
    for (int i = all.size() - 1; i >= 0; i--) {
      if (all.get(i).id.equals(id)) {
        all.remove(i);
        removed = true;
      }
    }
    if (!removed) return false;
    return writeAll(ctx, all);
  }

  /**
   * 全量写盘。写的是<b>应用内部存储</b>（原子写），失败返回 false，调用方要提示用户。
   *
   * <p>2026-10-11：原来写 `/sdcard/dsh/`，普通用户没宽存储权限会静默失败。
   * 现在走 {@link ConfigStoreLite}，零权限可写。
   */
  static boolean writeAll(Context ctx, List<Target> targets) {
    try {
      JSONObject root = new JSONObject();
      root.put("schema", SCHEMA);
      JSONArray arr = new JSONArray();
      for (Target t : (targets == null ? Collections.<Target>emptyList() : targets)) {
        arr.put(t.toJson());
      }
      root.put("targets", arr);

      boolean ok = ConfigStoreLite.write(ctx, NAME, root.toString(2));
      android.util.Log.i(TAG, "目标配置写入" + (ok ? "成功" : "失败")
          + "（" + arr.length() + " 条）");
      return ok;
    } catch (Throwable t) {
      android.util.Log.w(TAG, "目标配置写盘失败: " + t);
      return false;
    }
  }

  // ---------------------------------------------------------------- 内置种子

  /**
   * 生成一份**示例**目标（首次使用时展示给用户看"长什么样"）。
   *
   * <p>刻意只给规则、不给坐标，且标了 {@code verifiedOn} 为空 ——
   * 意思是"这条还没在你机器上验过，用之前先跑一次定位测试"。
   */
  static Target sampleTelegramSend() {
    Target t = new Target();
    t.id = "telegram.send";
    t.appLabel = "Telegram";
    t.name = "发送消息";
    t.risk = Risk.SEND;
    t.confirm = true;
    t.packages.add("org.telegram.messenger.web");
    t.packages.add("org.telegram.messenger");
    // 候选顺序：描述最准 → viewId → 文本兜底
    t.rules.add(new Rule("contentDescription", "发送", true));
    t.rules.add(new Rule("viewId", "send", false));
    t.rules.add(new Rule("text", "发送", true));
    return t;
  }

  /**
   * 配置文件的人类可读位置说明（给 UI 显示"配置存在哪"用）。
   *
   * <p>注意不再是单一绝对路径：主存储是应用内部（`filesDir/dsha/action-targets.json`），
   * 旧的 `/sdcard/dsh/` 只在首次迁移时被读取。
   */
  static String path() {
    return "应用内部存储 dsha/" + NAME + "（旧位置 " + PATH + " 仅作迁移来源）";
  }

  /** 当前设备画像，写进新目标的 verifiedOn。 */
  static String currentProfile(Context ctx) {
    return Compat.deviceProfile(ctx);
  }

  // ---------------------------------------------------------------- 私有

  /** 读根对象。走统一配置层（内部存储优先 + 旧位置一次性迁移）。 */
  private static JSONObject readRoot(Context ctx) {
    try {
      String json = ConfigStoreLite.read(ctx, NAME);
      if (json == null || json.trim().isEmpty()) return null;
      return new JSONObject(json);
    } catch (Throwable t) {
      android.util.Log.w(TAG, "读目标配置失败: " + t);
      return null;
    }
  }

  private static String readAll(InputStream in) throws Exception {
    try (InputStream i = in) {
      java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
      byte[] chunk = new byte[8192];
      int n;
      while ((n = i.read(chunk)) > 0) buf.write(chunk, 0, n);
      return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }
  }

  /** 诊断用：一行摘要（不打印敏感内容）。 */
  static String summary(Context ctx) {
    List<Target> all = loadAll(ctx);
    int withRules = 0;
    for (Target t : all) if (!t.rules.isEmpty()) withRules++;
    return "目标 " + all.size() + " 条（含规则 " + withRules + "）"
        + " · " + ConfigStoreLite.describe(ctx, NAME)
        + " · 设备 " + Build.MODEL;
  }
}
