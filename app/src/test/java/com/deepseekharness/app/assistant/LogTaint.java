package com.deepseekharness.app.assistant;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志隐私的 <b>SOURCE → SINK 污点分析器</b>（Phase 16 新增，**仅测试用**，不在生产代码里）。
 *
 * <p><b>为什么要有这个东西</b>（Phase 15 独立审查 P1-1 实测出来的洞）：
 * <pre>
 *   旧模型：判断「变量名」—— Log 参数里出现 question / text / dump … 就报警，
 *           其余名字（含 e / t / d / size / count …）一律放行。
 *   绕过：  String e = question;  Log.i(TAG, "x=" + e);   → 248 条测试全绿。
 * </pre>
 * 名字是不可靠的证据：任何一次改名、任何一次中转赋值都能让它失效。
 *
 * <p><b>新模型：不看名字，看「敏感来源有没有流到日志出口」</b>：
 * <ol>
 *   <li><b>SOURCE</b>：由 {@link #PARAM_SEEDS} / {@link #RETURN_SEEDS} 注册，
 *       描述"哪个方法的第几个参数 / 返回值携带敏感数据"。按<b>语义角色</b>注册，
 *       与参数叫什么名字无关 —— 这正是变异 1（question 改名 e）仍能抓到的原因。</li>
 *   <li><b>PROPAGATION</b>：方法体内做赋值级污点传播，迭代到不动点。
 *       只跟踪 <b>{@code String} 声明</b>的变量（见 {@link #ASSIGN}），
 *       避免把 {@code Outcome o} 这种对象整体标污、进而把 {@code o.handled}
 *       这种布尔字段误报成泄露（这是第一版实测出来的误报）。</li>
 *   <li><b>SANITIZER</b>：经过 {@code LogFormat.*} / {@code SensitiveData.redact} /
 *       长度类方法后只剩结构化信息，不再带污点。</li>
 *   <li><b>SINK</b>：{@code Log.x(...)}、异常消息。</li>
 * </ol>
 *
 * <p><b>能力边界（明确记录，不伪造覆盖）</b>：
 * <ul>
 *   <li><b>只做方法内（intra-procedural）分析</b>。跨方法污点只覆盖 {@link #RETURN_SEEDS}
 *       里显式登记的方法；没登记的被调用方法返回值一律视为干净 → <b>可能漏报</b>。</li>
 *   <li><b>只跟踪 String 变量</b>。走 {@code StringBuilder} / 数组 / 对象字段的
 *       间接传播看不到 → <b>可能漏报</b>。</li>
 *   <li>字段污点靠 {@link #FIELD_LIKE_SEEDS} 的"类名+字段名"粗粒度近似，不精确。</li>
 *   <li>不做别名/分支敏感性；三元与 lambda 内部只做词法近似。</li>
 *   <li>因此本分析器<b>不能证明"没有泄露"</b>，只能保证
 *       "只要污点在它看得见的路径上流到了 sink，就一定报警"。</li>
 * </ul>
 */
final class LogTaint {

  private LogTaint() {}

  /** 敏感数据种类。 */
  enum Kind {
    USER_INPUT, MODEL_REPLY, NOTIFICATION, SCREEN_TEXT, ACCESSIBILITY_TEXT
  }

  /** 一条发现。 */
  static final class Finding {
    final String file;
    final int line;
    final Kind kind;
    final String sink;
    final String taintedName;
    final String snippet;

    Finding(String file, int line, Kind kind, String sink, String taintedName, String snippet) {
      this.file = file;
      this.line = line;
      this.kind = kind;
      this.sink = sink;
      this.taintedName = taintedName;
      this.snippet = snippet;
    }

    @Override
    public String toString() {
      return file + ":" + line + "  [" + kind + "] " + sink + " ← 污点 \""
          + taintedName + "\"\n      " + snippet;
    }
  }

  // ==================================================================
  // 1. SOURCE：按语义角色注册（不看参数名）
  // ==================================================================

  /** 形参污点源：{@code 简单类名#方法名} → 被污染的形参下标。 */
  private static final Map<String, int[]> PARAM_SEEDS = new HashMap<>();

  static {
    // 用户在输入框里打的字
    PARAM_SEEDS.put("AssistantPanel#runText", new int[] {0});
    PARAM_SEEDS.put("AssistantPanel#askModel", new int[] {0});
    // 执行器：runLoop 的第 4 个参数是 task（含 goal，即用户原话）
    PARAM_SEEDS.put("AssistantTaskRunner#runLoop", new int[] {3});
    PARAM_SEEDS.put("AssistantTaskRunner#askAction", new int[] {0});
    // inputText 的入参是屏幕全文 dump
    PARAM_SEEDS.put("AssistantTaskRunner#inputText", new int[] {0});
  }

  /** 返回值污点源：跨方法传播的唯一通道（没登记的一律按干净处理）。 */
  private static final Map<String, Kind> RETURN_SEEDS = new HashMap<>();

  static {
    RETURN_SEEDS.put("DshaAccessibilityService#uiDump", Kind.SCREEN_TEXT);
    RETURN_SEEDS.put("DshaAccessibilityService#uiDumpUnderPanel", Kind.SCREEN_TEXT);
    RETURN_SEEDS.put("DshaAccessibilityService#collectText", Kind.ACCESSIBILITY_TEXT);
    RETURN_SEEDS.put("AssistantTaskRunner#inputText", Kind.SCREEN_TEXT);
    RETURN_SEEDS.put("AssistantTaskRunner#askAction", Kind.MODEL_REPLY);
    RETURN_SEEDS.put("DshaNotificationListener#line", Kind.NOTIFICATION);
    RETURN_SEEDS.put("DshaNotificationListener#digest", Kind.NOTIFICATION);
    RETURN_SEEDS.put("DshaNotificationListener#summarize", Kind.NOTIFICATION);
    RETURN_SEEDS.put("DshaNotificationListener#modelSectionFor", Kind.NOTIFICATION);
  }

  /**
   * 类级"字段即污点"近似（不精确，明确记录）：真正的字段污点需要跨方法数据流，
   * 本项目做不到，这里按"类名 + 字段名"粗粒度判定。
   */
  private static final Map<String, Set<String>> FIELD_LIKE_SEEDS = new HashMap<>();

  static {
    FIELD_LIKE_SEEDS.put("AssistantPanel", new HashSet<>(Arrays.asList("screenText", "screenShotB64")));
    FIELD_LIKE_SEEDS.put("AssistantTaskRunner", new HashSet<>(Arrays.asList("screenText")));
  }

  // ==================================================================
  // 2. SANITIZER
  // ==================================================================

  private static final String[] SANITIZERS = {
    "LogFormat.", "SensitiveData.redact", ".length()", ".size()", ".isEmpty()",
    "Integer.toString(", "Long.toString(", "Boolean.toString(",
    // ── 以下两条是 Phase 16 实测后补的，依据都是生产代码自己的契约 ──
    // GuardDecision.extractPackage(dump)：只取 dump 首行的**包名**，
    //   丢掉全部屏幕正文 → 属于"降维到结构化标识"，不是内容。
    //   （v5 实测：不登记它时，curPkg 会被当作屏幕污点，连带把 logReason 误报。）
    "GuardDecision.extractPackage(",
    // GuardDecision.logReason(v, pkg)：javadoc 原文"给日志用的拒绝原因（不包含屏幕内容）"，
    //   与 LogFormat 同类的结构化格式化器。
    "GuardDecision.logReason(",
    // ★★ 注意：这里**故意没有** "String.valueOf(" ★★
    //
    // 它曾经在名单里，是 Phase 16 第二次独立审查攻破的第一条：
    //     android.util.Log.i(TAG, "q=" + String.valueOf(question));
    // 对String 参数，String.valueOf 是**恒等函数**（原样返回），
    // 于是"名字看着像净化器、实际原样回吐"—— 一行代码、零测试改动，绕过整套检测。
    // 取消登记之后它不再是净化器，污点照常传到 sink。
    // （代价：`String.valueOf(someInt)` 这类本来安全的写法现在会被判为可疑 ——
    //   但 int 变量不会被种上污点，所以实际不产生假阳性。）
  };

  // ==================================================================
  // 3. SINK
  // ==================================================================

  private static final Pattern LOG_SINK = Pattern.compile("\\bLog\\.[a-z]\\s*\\(");
  private static final Pattern EXCEPTION_SINK =
      Pattern.compile("new\\s+\\w*(?:Exception|Error|Throwable)\\s*\\(");

  // ==================================================================
  // 4. 词法
  // ==================================================================

  /** 方法声明。 */
  private static final Pattern METHOD_DECL =
      Pattern.compile(
          "(?:public|private|protected|static|final|synchronized|abstract|\\s)*"
              + "[\\w<>\\[\\],.?\\s]+?\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(?:throws\\s+[\\w.,\\s]+)?\\{");

  /**
   * 赋值语句，带「声明类型」捕获：group(1)=类型（可为 null），group(2)=变量名，group(3)=右值。
   *
   * <p><b>踩过的坑（Phase 16）</b>：字符类里写 {@code =-+} 会被当成
   * "从 {@code =} 到 {@code +}" 的<b>范围</b>，而 {@code +} 码位小于 {@code =}，
   * 直接抛 {@code PatternSyntaxException}（表现为 {@code ExceptionInInitializerError}，
   * 极难定位）。<b>短横线必须放最后。</b>
   */
  private static final Pattern ASSIGN =
      Pattern.compile(
          "(?:^|[;{}\\n])\\s*(?:((?:(?:final|static|var)\\s+)*[\\w<>\\[\\],.?]+)\\s+)?"
              + "([a-zA-Z_]\\w*)\\s*=\\s*([^;{}=+\\-\\n][^;{}]*);");

  private static final Pattern IDENT = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

  // ==================================================================
  // 5. 分析主体
  // ==================================================================

  /** 扫一个生产源文件。 */
  static List<Finding> scan(Path file) throws Exception {
    String raw = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    return scanText(file.getFileName().toString().replace(".java", ""), raw);
  }

  /**
   * 扫一段源码文本（供 {@link #selfCheckCases} 的自证样例使用 —— 样例不需要真的落盘）。
   */
  static List<Finding> scanText(String simpleName, String raw) {
    // 注释替换成空格（保留换行），这样行号与原文一致
    String src = blankComments(raw);
    List<Finding> out = new ArrayList<>();
    for (MethodBody m : splitMethods(src)) {
      out.addAll(analyzeMethod(simpleName, m, src));
    }
    return out;
  }

  // ==================================================================
  // 6. 自证样例（CANARY）：扫描器必须能正确判定这些**内置**样例
  // ==================================================================

  /**
   * 内置自证样例。
   *
   * <p><b>为什么必须有这个</b>（Phase 15 抽查 #10 实测）：把扫描器的检测规则清空之后，
   * 全套 248 条测试<b>依然全绿</b> —— 静态扫描类测试在结构上无法发现"自己已经失效"。
   * 自证样例就是把"必须被抓到"这件事变成一条断言：样例还在，规则被削弱就立刻红。
   *
   * <p>样例分三类，覆盖两个方向：
   * <ul>
   *   <li>{@code SAFE} —— 必须<b>不报</b>（防"规则被改得太敏感"导致假阳性泛滥）</li>
   *   <li>{@code LEAK} —— 必须<b>报</b>（防"规则被削弱"导致漏检）</li>
   *   <li>{@code UNKNOWN} —— 用<b>随机变量名</b>的泄露，必须<b>报</b>（专门防 P1-1 那类绕过）</li>
   * </ul>
   */
  static final class CanaryCase {
    final String id;
    final String source;
    final boolean mustFind;

    CanaryCase(String id, String source, boolean mustFind) {
      this.id = id;
      this.source = source;
      this.mustFind = mustFind;
    }
  }

  /** 内置样例表。新增检测能力时应该同时往这里加样例。 */
  static List<CanaryCase> selfCheckCases() {
    List<CanaryCase> out = new ArrayList<>();
    // ── SAFE：结构化日志，不应报警 ──
    out.add(new CanaryCase("SAFE_结构化",
        "class AssistantPanel {\n"
            + "  private void runText(String question) {\n"
            + "    android.util.Log.i(TAG, \"runText: \" + LogFormat.directAsk(question));\n"
            + "  }\n}\n",
        false));
    out.add(new CanaryCase("SAFE_长度",
        "class AssistantPanel {\n"
            + "  private void runText(String question) {\n"
            + "    android.util.Log.i(TAG, \"len=\" + question.length());\n"
            + "  }\n}\n",
        false));
    out.add(new CanaryCase("SAFE_脱敏",
        "class DshaNotificationListener {\n"
            + "  static String item(String s) {\n"
            + "    android.util.Log.i(TAG, SensitiveData.redact(s));\n"
            + "  }\n}\n",
        false));
    // ── LEAK：用户内容直接进 Log，必须报 ──
    out.add(new CanaryCase("LEAK_直接",
        "class AssistantPanel {\n"
            + "  private void runText(String question) {\n"
            + "    android.util.Log.i(TAG, \"q=\" + question);\n"
            + "  }\n}\n",
        true));
    out.add(new CanaryCase("LEAK_改名为e",
        "class AssistantPanel {\n"
            + "  private void runText(String e) {\n"
            + "    android.util.Log.i(TAG, \"q=\" + e);\n"
            + "  }\n}\n",
        true));
    // ── UNKNOWN：随机变量名 + 多层中转，必须报（P1-1 的核心绕过形状）──
    out.add(new CanaryCase("UNKNOWN_一层中转",
        "class AssistantPanel {\n"
            + "  private void runText(String question) {\n"
            + "    String zz1 = question;\n"
            + "    android.util.Log.i(TAG, \"q=\" + zz1);\n"
            + "  }\n}\n",
        true));
    out.add(new CanaryCase("UNKNOWN_两层传播",
        "class AssistantPanel {\n"
            + "  private void runText(String question) {\n"
            + "    String zzA = question;\n"
            + "    String zzB = zzA;\n"
            + "    android.util.Log.i(TAG, \"q=\" + zzB);\n"
            + "  }\n}\n",
        true));
    out.add(new CanaryCase("UNKNOWN_三层传播",
        "class AssistantPanel {\n"
            + "  private void runText(String question) {\n"
            + "    String p1 = question;\n"
            + "    String p2 = p1;\n"
            + "    String p3 = p2;\n"
            + "    android.util.Log.i(TAG, \"q=\" + p3);\n"
            + "  }\n}\n",
        true));
    // ── 以下两条是 Phase 16 第二次独立审查**攻破过**的形状，现已永久纳入自证 ──
    // 它们当时都能让 253 条测试全绿：
    out.add(new CanaryCase("ATTACK_String_valueOf",
        "class AssistantPanel {\n"
            + "  private void runText(String question) {\n"
            + "    android.util.Log.i(TAG, \"q=\" + String.valueOf(question));\n"
            + "  }\n}\n",
        true));
    out.add(new CanaryCase("ATTACK_final_String",
        "class AssistantPanel {\n"
            + "  private void runText(String question) {\n"
            + "    final String e = question;\n"
            + "    android.util.Log.i(TAG, \"q=\" + e);\n"
            + "  }\n}\n",
        true));
    out.add(new CanaryCase("ATTACK_var_推断类型",
        "class AssistantPanel {\n"
            + "  private void runText(String question) {\n"
            + "    var e = question;\n"
            + "    android.util.Log.i(TAG, \"q=\" + e);\n"
            + "  }\n}\n",
        true));
    return out;
  }

  private static List<Finding> analyzeMethod(String cls, MethodBody m, String src) {
    List<Finding> out = new ArrayList<>();
    Set<String> tainted = new LinkedHashSet<>();
    Set<String> stringVars = new LinkedHashSet<>();

    // 种子 1：注册的形参（类型必须是 String 才跟踪）
    int[] seeds = PARAM_SEEDS.get(cls + "#" + m.name);
    if (seeds != null) {
      List<String> params = splitTop(m.params, ',');
      for (int idx : seeds) {
        if (idx < params.size()) {
          String decl = params.get(idx).trim();
          String p = lastIdent(decl);
          if (p != null && isStringDecl(decl)) {
            stringVars.add(p);
            tainted.add(p);
          }
        }
      }
    }
    // 种子 2：类级字段近似
    Set<String> fields = FIELD_LIKE_SEEDS.get(cls);
    if (fields != null) {
      stringVars.addAll(fields);
      tainted.addAll(fields);
    }

    // 传播：迭代到不动点（覆盖一层 / 两层 / 多层中转）
    for (int round = 0; round < 8; round++) {
      Set<String> before = new LinkedHashSet<>(tainted);
      Matcher am = ASSIGN.matcher(m.body);
      while (am.find()) {
        String type = am.group(1);
        String lhs = am.group(2);
        String rhs = am.group(3);
        if (type != null && !isStringDecl(type)) continue; // 非 String 声明：当不透明对象
        if (type == null && !stringVars.contains(lhs)) continue; // 无类型=重新赋值，只跟已跟踪的
        stringVars.add(lhs);
        if (isSanitized(rhs)) continue;
        if (referencesAny(rhs, tainted) || fromTaintedCall(rhs)) {
          tainted.add(lhs);
        }
      }
      if (tainted.equals(before)) break;
    }

    // SINK 检查：按顶层 + 切开，逐段判污点
    for (SinkCall sink : findSinks(m.body)) {
      for (String op : splitTop(sink.args, '+')) {
        if (isSanitized(op)) continue;
        for (String t : tainted) {
          if (containsIdent(op, t)) {
            int abs = m.bodyStart + sink.offsetInBody;
            out.add(new Finding(cls + ".java", lineOf(src, abs), kindOf(cls, m.name),
                sink.kind, t, "命中片段=[" + op.trim() + "] @" + snippet(src, abs)));
            break;
          }
        }
      }
    }
    return out;
  }

  private static Kind kindOf(String cls, String method) {
    Kind k = RETURN_SEEDS.get(cls + "#" + method);
    return k != null ? k : Kind.USER_INPUT;
  }

  private static boolean fromTaintedCall(String rhs) {
    for (String key : RETURN_SEEDS.keySet()) {
      String m = key.substring(key.indexOf('#') + 1);
      if (rhs.contains(m + "(")) return true;
    }
    return false;
  }

  /**
   * 判断"声明的类型部分"是不是 String。
   *
   * <p><b>Phase 16 踩过的最严重的坑</b>：第一版写成
   * {@code t.endsWith("String") || t.contains(" String")}。
   * 但形参写法是 {@code String question} —— <b>类型在前、名字在后</b>，
   * 于是 {@code "String question".endsWith("String")} 为 false、
   * {@code contains(" String")} 也为 false（开头没有空格）。
   * 结果所有形参种子<b>一个都没被种上</b>，分析器对整个代码库报 0 条——
   * 在干净代码上"通过"，实际检测能力为 <b>0</b>。
   * 是三个必需变异全部 MISSED 才暴露出来。
   *
   * <p>现在先剥掉尾部标识符（变量名），再判类型部分。
   */
  private static boolean isStringDecl(String decl) {
    String t = decl.trim();
    if (t.isEmpty()) return false;
    // 只有类型没有名字（ASSIGN 的 group(1) 形态，如 "String"）
    if (isStringType(t)) return true;
    // 有名字（形参形态，如 "String question"）：剥掉最后一个标识符再判
    String typePart = stripLastIdent(t);
    return isStringType(typePart);
  }

  private static boolean isStringType(String t) {
    String s = t.trim();
    return s.equals("String")
        || s.equals("String...")
        // `var x = ...` 类型靠推断。按 String 处理是**保守方向**（宁可多标污点），
        // 与"漏检比误报危险"的取向一致。
        || s.endsWith("var")
        || s.endsWith(".String")
        || s.endsWith(" String")
        || s.endsWith(" String...");
  }

  /** 剥掉尾部标识符（变量名），例如 {@code "String question"} → {@code "String"}。 */
  private static String stripLastIdent(String t) {
    int cut = t.length();
    while (cut > 0) {
      char c = t.charAt(cut - 1);
      if (Character.isLetterOrDigit(c) || c == '_') cut--;
      else break;
    }
    return t.substring(0, cut).trim();
  }

  // ---------------- 词法辅助 ----------------

  private static boolean isSanitized(String expr) {
    for (String s : SANITIZERS) {
      if (expr.contains(s)) return true;
    }
    return false;
  }

  private static boolean referencesAny(String expr, Set<String> names) {
    Matcher m = IDENT.matcher(stripStrings(expr));
    while (m.find()) {
      if (names.contains(m.group())) return true;
    }
    return false;
  }

  private static boolean containsIdent(String expr, String name) {
    Matcher m = IDENT.matcher(stripStrings(expr));
    while (m.find()) {
      if (m.group().equals(name)) return true;
    }
    return false;
  }

  private static String lastIdent(String s) {
    String[] parts = s.trim().split("[^A-Za-z0-9_]+");
    return parts.length == 0 || parts[parts.length - 1].isEmpty() ? null : parts[parts.length - 1];
  }

  private static String stripStrings(String s) {
    return s.replaceAll("\"(\\\\.|[^\"\\\\])*\"", "\"\"");
  }

  /** 把注释替换成空格（保留换行），行号才与原文一致。 */
  private static String blankComments(String src) {
    StringBuilder out = new StringBuilder(src.length());
    boolean inStr = false;
    for (int i = 0; i < src.length(); i++) {
      char c = src.charAt(i);
      if (inStr) {
        out.append(c);
        if (c == '"' && i > 0 && src.charAt(i - 1) != '\\') inStr = false;
        continue;
      }
      if (c == '"') {
        inStr = true;
        out.append(c);
        continue;
      }
      if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
        int nl = src.indexOf('\n', i);
        int end = nl < 0 ? src.length() : nl;
        for (int k = i; k < end; k++) out.append(' ');
        i = end - 1;
        continue;
      }
      if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '*') {
        int close = src.indexOf("*/", i + 2);
        int end = close < 0 ? src.length() : close + 2;
        for (int k = i; k < end; k++) out.append(src.charAt(k) == '\n' ? '\n' : ' ');
        i = end - 1;
        continue;
      }
      out.append(c);
    }
    return out.toString();
  }

  /** 按"深度为 0 的分隔符"切分。 */
  private static List<String> splitTop(String s, char sep) {
    List<String> out = new ArrayList<>();
    int depth = 0;
    boolean inStr = false;
    StringBuilder cur = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (inStr) {
        if (c == '"' && i > 0 && s.charAt(i - 1) != '\\') inStr = false;
        cur.append(c);
        continue;
      }
      if (c == '"') {
        inStr = true;
        cur.append(c);
        continue;
      }
      if (c == '(' || c == '[') depth++;
      else if (c == ')' || c == ']') depth--;
      if (c == sep && depth == 0) {
        out.add(cur.toString());
        cur.setLength(0);
        continue;
      }
      cur.append(c);
    }
    out.add(cur.toString());
    return out;
  }

  private static int lineOf(String src, int idx) {
    int n = 1;
    for (int i = 0; i < idx && i < src.length(); i++) {
      if (src.charAt(i) == '\n') n++;
    }
    return n;
  }

  /** 取 sink 附近一小段（不打印整个方法体，避免报告爆炸）。 */
  private static String snippet(String src, int abs) {
    int to = Math.min(src.length(), abs + 180);
    return src.substring(Math.max(0, abs), to).replaceAll("\\s+", " ").trim();
  }

  // ---------------- 方法切分 ----------------

  private static final class MethodBody {
    String name;
    String params;
    String body;
    int bodyStart;
  }

  private static List<MethodBody> splitMethods(String src) {
    List<MethodBody> out = new ArrayList<>();
    Matcher m = METHOD_DECL.matcher(src);
    while (m.find()) {
      int braceStart = m.end() - 1;
      int end = matchBrace(src, braceStart);
      if (end < 0) continue;
      MethodBody mb = new MethodBody();
      mb.name = m.group(1);
      mb.params = m.group(2);
      mb.body = src.substring(braceStart + 1, end);
      mb.bodyStart = braceStart + 1;
      out.add(mb);
    }
    return out;
  }

  private static int matchBrace(String s, int open) {
    int depth = 0;
    boolean inStr = false;
    for (int i = open; i < s.length(); i++) {
      char c = s.charAt(i);
      if (inStr) {
        if (c == '"' && i > 0 && s.charAt(i - 1) != '\\') inStr = false;
        continue;
      }
      if (c == '"') {
        inStr = true;
        continue;
      }
      if (c == '{') depth++;
      else if (c == '}') {
        depth--;
        if (depth == 0) return i;
      }
    }
    return -1;
  }

  // ---------------- SINK 定位 ----------------

  private static final class SinkCall {
    String kind;
    String args;
    int offsetInBody;
  }

  private static List<SinkCall> findSinks(String body) {
    List<SinkCall> out = new ArrayList<>();
    out.addAll(matchSink(body, LOG_SINK, "Log"));
    out.addAll(matchSink(body, EXCEPTION_SINK, "Exception"));
    return out;
  }

  /**
   * 匹配圆括号 {@code (...)} 的闭合下标。
   *
   * <p><b>Phase 16 踩过的坑</b>：第一版这里复用了 {@link #matchBrace}（它数的是
   * <b>花括号</b> {@code {}}）。用它去匹配 {@code Log.i(...)} 的括号时，
   * 括号内的 {@code {} 根本不配对，于是它一路扫到方法体的收尾花括号才返回 ——
   * 结果 {@code sink.args} 把整个方法后半截都吃了进去，把 {@code AssistantPanel.note("…" + task.goal)}
   * 这种**别的调用**的参数也算成了 Log 的污点。表现为"同一行报出五六个不相干的污点名"。
   * <b>括号和花括号必须各用一个函数。</b>
   */
  private static int matchParen(String s, int open) {
    int depth = 0;
    boolean inStr = false;
    for (int i = open; i < s.length(); i++) {
      char c = s.charAt(i);
      if (inStr) {
        if (c == '"' && i > 0 && s.charAt(i - 1) != '\\') inStr = false;
        continue;
      }
      if (c == '"') {
        inStr = true;
        continue;
      }
      if (c == '(') depth++;
      else if (c == ')') {
        depth--;
        if (depth == 0) return i;
      }
    }
    return -1;
  }

  private static List<SinkCall> matchSink(String body, Pattern p, String kind) {
    List<SinkCall> out = new ArrayList<>();
    Matcher m = p.matcher(body);
    while (m.find()) {
      int open = m.end() - 1;
      int close = matchParen(body, open);
      if (close < 0) continue;
      SinkCall s = new SinkCall();
      s.kind = kind;
      s.args = body.substring(open + 1, close);
      s.offsetInBody = m.start();
      out.add(s);
    }
    return out;
  }
}
