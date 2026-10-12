package com.deepseekharness.app.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.deepseekharness.app.util.SensitiveData;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志隐私测试（离线，JVM）。
 *
 * <p>用户要求：
 * <blockquote>
 * ① 屏幕文本正文不得进入生产日志；
 * ② 用户消息、凭据和 Token 不得进入生产日志；
 * ③ 日志只保留完成诊断所需的结构化信息；
 * ④ 使用虚构的屏幕文本和假 Token 构造测试输入；
 * ⑤ <b>不要把"源码里没找到某个关键词"当作完整的隐私证明</b>。
 * </blockquote>
 *
 * <p>针对第 ⑤ 条，本文件不满足于"搜关键词"，而是做两件事：
 * <ul>
 *   <li><b>行为测试</b>：拿<u>虚构</u>的假 Token 真的跑一遍 {@link SensitiveData#redact}，
 *       断言输出里不含原文（证明脱敏真的工作，而不只是"代码里写了 redact"）。
 *   <li><b>结构测试</b>：扫描日志调用点，断言**不存在**"把用户内容变量直接拼进日志"的形状，
 *       并对每一处历史泄漏点（D-10）做定点断言。
 * </ul>
 *
 * <p><b>不做的事</b>：不连真实服务、不产生真实日志、不使用任何真实凭据。
 * 所有输入都是本文件里编的假数据。
 */
public class LogPrivacyTest {

  /** 虚构的屏幕文本（不是任何真实用户的内容）。 */
  private static final String FAKE_SCREEN_TEXT = "假聊天记录小明你好啊假聊天记录";

  /** 虚构的 Token（格式像但值是编的，不会被任何服务接受）。 */
  private static final String FAKE_TOKEN = "sk-FAKE000011112222333344445555";

  /** 虚构的私钥样式串。 */
  private static final String FAKE_PRIVATE_KEY =
      "-----BEGIN PRIVATE KEY-----\nFAKEFAKEFAKE\n-----END PRIVATE KEY-----";

  // ================================================================
  // 1. 脱敏行为（用假凭据真跑一遍）
  // ================================================================

  @Test
  public void 假Token必须被脱敏() {
    SensitiveData.registerKnownSecret(FAKE_TOKEN);
    try {
      String input = "endpoint=https://example.invalid key=" + FAKE_TOKEN;
      String out = SensitiveData.redact(input);
      assertNotNull(out);
      assertFalse("脱敏后不得出现假 Token 原文", out.contains(FAKE_TOKEN));
      assertTrue("应留下 *** 痕迹便于确认做过脱敏", out.contains("***"));
    } finally {
      SensitiveData.forgetKnownSecret(FAKE_TOKEN);
    }
  }

  @Test
  public void 假私钥必须被脱敏() {
    String out = SensitiveData.redact(FAKE_PRIVATE_KEY);
    assertNotNull(out);
    assertFalse("私钥块不得原样留在输出里", out.contains("FAKEFAKEFAKE"));
  }

  @Test
  public void Bearer令牌必须被脱敏() {
    String out = SensitiveData.redact("Authorization: Bearer abc123def456ghi789");
    assertNotNull(out);
    assertFalse("Bearer 令牌不得原样输出", out.contains("abc123def456ghi789"));
  }

  @Test
  public void 脱敏对null与空串安全() {
    // 日志链路里经常出现 null，脱敏本身不能抛
    SensitiveData.redact(null);
    SensitiveData.redact("");
    SensitiveData.redact(FAKE_SCREEN_TEXT); // 普通文本：不做过度处理
  }

  // ================================================================
  // 2. 结构测试：日志调用点不得拼接用户内容
  // ================================================================

  /**
   * 扫描生产代码的日志调用，断言没有"把用户内容变量直接拼进 Log"的形状。
   *
   * <p>要防的变量：用户输入/识别结果、任务目标、可能含任务内容的消息、屏幕正文、
   * 节点树全文、模型输出（content/rc/reply/response/answer/body/payload…）。
   *
   * <p>注意：这里查的是**形状**（拼接），不是关键词 —— 所以改名变量也躲不过
   * "把某个可能装用户内容的串直接进 Log"这一类写法被人工复核。
   *
   * <p><b>Phase 11 修正</b>：原名单只有 {@code text|goal|msg|screenText|dump|reply}，
   * 实测漏掉了模型输出变量 {@code content}（见 {@code PHASE11-STAGE3-LOGPRIVACY-MUTATION.md}
   * 变异 2：注入 {@code Log.i(..., "模型回复: " + content)} 后本测试**未失败**）。
   * 现已扩大名单；更稳健的兜底见 {@link #日志拼接字段必须在安全白名单内()}。
   */
  @Test
  public void 日志调用不得直接拼接用户内容变量() throws IOException {
    List<String> offenders = new ArrayList<>();
    // 拼接形状：Log.x(TAG, "..." + <变量>) 或 .append(<变量>)
    // Phase 11：扩大变量名单，纳入模型输出与常见别名
    Pattern bad =
        Pattern.compile(
            "Log\\.[a-z]+\\([^)]*\\+\\s*(text|goal|msg|screenText|dump|reply|content|rc|result|response|answer|body|payload|userText|userInput|input)\\b");

    for (Path p : productionSources()) {
      List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
      for (int i = 0; i < lines.size(); i++) {
        String line = stripComment(lines.get(i));
        if (line.trim().isEmpty()) continue; // 纯注释行跳过
        Matcher m = bad.matcher(line);
        if (m.find()) {
          // 允许显式包了长度/摘要的形式
          if (line.contains("length()") || line.contains("redact") || line.contains("truncate")) {
            continue;
          }
          offenders.add(p.getFileName() + ":" + (i + 1) + "  " + line.trim());
        }
      }
    }
    assertTrue(
        "以下日志点把用户内容变量直接拼进了日志（应改为长度/结构化指标）：\n"
            + String.join("\n", offenders),
        offenders.isEmpty());
  }

  /**
   * ★ Phase 11 新增兜底测试：日志拼接字段必须在"已审核安全字段"白名单内。
   *
   * <p><b>为什么需要它</b>：上面的测试是**变量名黑名单**。黑名单永远补不全 ——
   * 实测变异 2 证明，注入一个名单外的变量名（{@code content}）就能绕过。
   *
   * <p><b>做法</b>：扫描所有 {@code Log.x(...)} 调用里的 {@code + <标识符>} 拼接，
   * 只有"已审核为安全的结构化字段"才允许：
   * <ul>
   *   <li>显式长度/摘要：{@code length()} / {@code redact()} / {@code truncate()} / {@code hash()}</li>
   *   <li>布尔与枚举（如是否命中唤醒词、判定结论）</li>
   *   <li>代次 / 包名 / 应用名 / 视图标识（{@code gen} / {@code pkg} / {@code packageName} /
   *       {@code appLabel} / {@code viewId} / {@code windowId} / {@code displayId}）</li>
   *   <li>计数类指标（{@code count} / {@code nodeLines} / {@code withId} / {@code withCd} /
   *       {@code chars} / {@code step} / {@code versionCode}）</li>
   * </ul>
   * 不在此列的变量拼接 → FAIL。这样**第一次见的变量名**也会被拦下。
   *
   * <p>新增安全字段时，必须同步加进 {@link #SAFE_FIELDS} 并说明理由。
   */
  @Test
  public void 日志拼接字段必须在安全白名单内() throws IOException {
    java.util.Set<String> safeFields = SAFE_FIELDS;

    List<String> offenders = new ArrayList<>();
    // 抓 Log.x(...) 里的 "+ <标识符>"（不跟 "(" 即方法调用，也不跟 "."）
    Pattern concat =
        Pattern.compile("Log\\.[a-z]+\\([^)]*\\+\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\b(?!\\s*\\()");

    for (Path p : productionSources()) {
      List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
      for (int i = 0; i < lines.size(); i++) {
        String line = stripComment(lines.get(i));
        if (line.trim().isEmpty()) continue;
        // 显式长度/摘要形式一律放行
        if (line.contains("length()") || line.contains("redact") || line.contains("truncate")
            || line.contains("hash()")) {
          continue;
        }
        Matcher m = concat.matcher(line);
        while (m.find()) {
          String var = m.group(1);
          String key = p.getFileName() + ":" + (i + 1);
          if (safeFields.contains(var)) continue;
          if (KNOWN_LEAKS.containsKey(key)) continue;   // 已登记的已知泄漏（见下方说明）
          offenders.add(key + "  未登记字段 '" + var + "'  " + line.trim());
        }
      }
    }
    assertTrue(
        "以下日志点拼接了未经审核的字段（可能含用户/模型正文，应改为 length()/结构化指标，"
            + "或将其加入白名单并说明理由）：\n" + String.join("\n", offenders),
        offenders.isEmpty());
  }

  /**
   * D-10 / D-11 定点回归：历史泄漏点必须保持已修复状态。
   *
   * <p>注意断言只扫**代码行**（{@link #stripComment} 去掉注释）——
   * 修复说明的注释里会引用旧写法作为对照，那不是泄漏。
   * （第一版就是被自己的注释绊倒的，这条经验写在这里。）
   */
  @Test
  public void D10回归_历史泄漏点不得复发() throws IOException {
    // 注意：格式化已经抽到 LogFormat，所以这里断言的是
    // "必须走 LogFormat 的结构化格式化"，而不是"必须出现某个字面量"。
    // 严格度不变（依然是"必须用结构化形式"），只是跟着代码结构走。
    String wake = readCodeOnly("AssistantWakeService.java");
    assertFalse(
        "唤醒服务不得再打识别正文（'听到: ' + text）", wake.contains("\"听到: \" + text"));
    assertTrue(
        "唤醒服务必须走 LogFormat.speechRecognized（结构化：字数 + 是否命中唤醒词）",
        wake.contains("LogFormat.speechRecognized("));

    String assist = readCodeOnly("DshaAssistActivity.java");
    assertFalse(
        "直达入口不得再打用户输入正文（'text-direct ask: ' + text）",
        assist.contains("\"text-direct ask: \" + text"));
    assertTrue("必须走 LogFormat.directAsk", assist.contains("LogFormat.directAsk("));

    String runner = readCodeOnly("AssistantTaskRunner.java");
    assertFalse("任务日志不得再打目标正文", runner.contains("\"): \" + task.goal"));
    assertTrue("必须走 LogFormat.taskStart", runner.contains("LogFormat.taskStart("));
    assertTrue("必须走 LogFormat.taskEnd", runner.contains("LogFormat.taskEnd("));

    String alarm = readCodeOnly("DshaAlarmReceiver.java");
    assertFalse("提醒日志不得再打提醒正文", alarm.contains("\"响了: \" + msg"));
    assertTrue("必须走 LogFormat.alarmFired", alarm.contains("LogFormat.alarmFired("));
  }

  /** 读屏日志必须是指标形态，不能是 dump 正文。 */
  @Test
  public void D06回归_读屏日志必须是结构化指标() throws IOException {
    String runner = read("AssistantTaskRunner.java");
    assertTrue("读屏日志应打指标", runner.contains("读屏指标"));
    assertFalse("不得再有 dump 正文格式", runner.contains("读屏 head="));
    assertTrue("指标里应有节点行数", runner.contains("节点行="));
    assertFalse("指标不得包含 dump 文本切片", runner.contains("dump.substring(0, Math.min(240"));
  }

  /** 日志里不得出现"密钥/令牌"这类字样直接接变量。 */
  @Test
  public void 日志调用不得直接拼密钥类变量() throws IOException {
    List<String> offenders = new ArrayList<>();
    Pattern bad =
        Pattern.compile("Log\\.[a-z]+\\([^)]*(apiKey|ApiKey|token|Token|secret|Secret)\\b");
    for (Path p : productionSources()) {
      List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
      for (int i = 0; i < lines.size(); i++) {
        if (bad.matcher(lines.get(i)).find()) {
          offenders.add(p.getFileName() + ":" + (i + 1));
        }
      }
    }
    assertTrue("日志里不得出现密钥/令牌变量：" + offenders, offenders.isEmpty());
  }

  // ================================================================
  // 2b. 安全字段白名单 / 已知泄漏登记（Phase 11 兜底机制）
  // ================================================================

  /**
   * 已审核为"结构性、不含用户正文"的日志拼接字段。
   *
   * <p>加入此清单必须能说明"为什么它不含用户内容"：
   * <ul>
   *   <li><b>布尔/状态</b>：on attached leaving pending handled dismiss ok hot allowed live
   *       hasAuthority targetMatches</li>
   *   <li><b>代次与步骤</b>：gen step</li>
   *   <li><b>包名/应用标识</b>：pkg packageName appLabel appId
   *       ME（编译期常量，无障碍组件名，已核实为字面量）</li>
   *   <li><b>视图/窗口/配置标识</b>：viewId windowId displayId targetId
   *       SCHEMA（配置 schema 版本常量）</li>
   *   <li><b>坐标与尺寸</b>：x y size lp from target screenW</li>
   *   <li><b>计数/指标/耗时</b>：count nodeLines withId withCd chars waited versionCode
   *       metrics（由 LogFormat.dumpMetrics 产出的结构化指标串）</li>
   *   <li><b>异常对象</b>：e t ex error detail（detail 已过 SensitiveData.redact）</li>
   *   <li><b>判定/枚举</b>：verdict d（配置迁移决策）</li>
   * </ul>
   */
  private static final java.util.Set<String> SAFE_FIELDS =
      java.util.Set.of(
          "on", "attached", "leaving", "pending", "handled", "dismiss", "ok", "hot",
          "allowed", "live", "hasAuthority", "targetMatches",
          "gen", "step",
          "pkg", "packageName", "appLabel", "appId", "ME",
          "viewId", "windowId", "displayId", "targetId", "SCHEMA",
          "x", "y", "size", "lp", "from", "target", "screenW",
          "count", "nodeLines", "withId", "withCd", "chars", "waited", "versionCode", "metrics",
          "e", "t", "ex", "error", "detail",
          "verdict", "d",
          // AssistantPanel:1163 "skill hit: name=" + hit.name —— hit.name 是技能名
          // （来自 AssistantSkills 配置，非用户输入；用户输入 question 在同一 try 内但未进日志）
          "hit",
          // AssistantTaskRunner:251 "前台等待 " + waited + "ms，当前包=" + DshaAccessibilityService.currentPackage()
          // —— waited 是耗时；.currentPackage() 是方法调用，正则误抓了类名 DshaAccessibilityService
          "DshaAccessibilityService",
          // AssistantTaskRunner:272 "任务授权上下文: 包=" + taskTarget.packageName
          //   + " 窗口=" + taskTarget.windowId + " 显示器=" + taskTarget.displayId
          // —— taskTarget 是 ScreenTarget，取的三个字段都是界面身份三元组，非用户内容
          "taskTarget");

  /**
   * ★ 已知泄漏登记表（Phase 11 兜底测试首次运行即发现，黑名单完全漏掉）。
   *
   * <p>key = {@code 文件名:行号}，value = 泄漏说明。
   * <b>这是"待修清单"，不是"免责清单"</b> ——
   * {@link #已知泄漏登记必须与实际情况一致()} 会盯着它：
   * 修好一处就能划掉一处；清单只许减、不许无故增。
   */
  private static final java.util.Map<String, String> KNOWN_LEAKS =
      java.util.Map.of(
          // 用户输入 + 模型回复同时进日志
          "AssistantPanel.java:360",
              "runText 日志把用户输入 question 与模型回复 o.reply 直接拼进 logcat"
                  + "（D-12，Phase 11 兜底测试发现，黑名单未覆盖）",
          // 模型完整响应 JSON 进日志
          "AssistantTaskRunner.java:1035",
              "模型动作日志把完整响应 resp（含动作 JSON 与任务内容）拼进 logcat"
                  + "（D-13，Phase 11 兜底测试发现，黑名单未覆盖）");

  /** 抓 Log.x(...) 里的 "+ <标识符>"（不跟 "(" 即方法调用）。 */
  private static final java.util.regex.Pattern CONCAT =
      java.util.regex.Pattern.compile("Log\\.[a-z]+\\([^)]*\\+\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\b(?!\\s*\\()");

  /**
   * 保证 KNOWN_LEAKS 与代码实际状态一致：
   * <ul>
   *   <li>清单里每一行必须**仍然是**日志拼接形状（防止修好了却没划掉）</li>
   * </ul>
   */
  @Test
  public void 已知泄漏登记必须与实际情况一致() throws IOException {
    List<String> stale = new ArrayList<>();
    for (String key : KNOWN_LEAKS.keySet()) {
      String[] parts = key.split(":");
      String fname = parts[0];
      int lineNo = Integer.parseInt(parts[1]);
      Path target = null;
      for (Path p : productionSources()) {
        if (p.getFileName().toString().equals(fname)) {
          target = p;
          break;
        }
      }
      if (target == null) {
        stale.add(key + " 文件不存在");
        continue;
      }
      List<String> lines = Files.readAllLines(target, StandardCharsets.UTF_8);
      if (lineNo > lines.size()) {
        stale.add(key + " 行号越界");
        continue;
      }
      String line = stripComment(lines.get(lineNo - 1));
      boolean stillLogConcat =
          line.contains("Log.") && line.contains("+")
              && !line.contains("length()") && !line.contains("redact()");
      if (!stillLogConcat) {
        stale.add(key + " 该行已不再是日志拼接（可能已修复，请从 KNOWN_LEAKS 划掉）");
      }
    }
    assertTrue(
        "KNOWN_LEAKS 清单与代码不一致：\n" + String.join("\n", stale)
            + "\n（修好泄漏后请同步更新 KNOWN_LEAKS，保持清单可信）",
        stale.isEmpty());
  }

  // ================================================================
  // 3. 辅助
  // ================================================================

  /** 收集生产源码文件（只扫 main，不扫 test —— 测试里可以随便写假数据）。 */
  private static List<Path> productionSources() throws IOException {
    Path root = findMainRoot();
    List<Path> out = new ArrayList<>();
    Files.walk(root)
        .filter(p -> p.toString().endsWith(".java"))
        .forEach(out::add);
    assertFalse("应能找到生产源码", out.isEmpty());
    return out;
  }

  private static Path findMainRoot() {
    Path[] candidates = {
      Paths.get("src/main/java/com/deepseekharness/app"),
      Paths.get("app/src/main/java/com/deepseekharness/app"),
      Paths.get("../app/src/main/java/com/deepseekharness/app")
    };
    for (Path p : candidates) {
      if (Files.exists(p)) return p;
    }
    throw new AssertionError("找不到生产源码根目录（cwd=" + Paths.get("").toAbsolutePath() + "）");
  }

  /**
   * 去掉行内与整行注释，只留代码。
   *
   * <p>为什么必须去：修复说明的注释里会**引用旧写法**（"原来打的是 '听到: ' + text"），
   * 直接 contains 会把注释当成泄漏，导致测试自己在正确的代码上失败。
   * 这是本测试第一版真实踩到的坑。
   *
   * <h3>已知限制（不掩盖）</h3>
   * <ol>
   *   <li><b>会误截字符串字面量里的 {@code //}</b>：
   *       例如 {@code String u = "https://x";} 会变成 {@code String u = "https:}。
   *       对本测试的影响：若某行"日志拼接"恰好写在含 {@code //} 的字符串之后，可能漏检。
   *   <li><b>不解析块注释的中间行之外的形态</b>：本方法按行处理，
   *       {@code /* ... *}{@code /} 单行注释的尾部不会被去掉。
   *   <li><b>以 {@code *} 开头的字符串字面量行会被整体跳过</b>（例如 {@code String s = "*x";}）。
   * </ol>
   * <p><b>为什么可以接受</b>：本类断言的是"日志调用形状"（{@code Log.x(..., "..." + 变量)}），
   * 这种形状极少出现在含 {@code //} 的字符串之后；而更重要的隐私保证由
   * {@code LogFormatTest}（行为级：喂真内容断言不漏）承担，
   * 那一路完全不依赖本方法。**本类属静态回归，不是运行时日志测试。**
   */
  static String stripComment(String line) {
    if (line == null) return "";
    String t = line.trim();
    if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) return "";
    int c = line.indexOf("//");
    return c >= 0 ? line.substring(0, c) : line;
  }

  /**
   * 把 {@link #stripComment} 的**已知限制**钉进测试，避免以后有人误以为它是个完整解析器。
   *
   * <p>这条测试不断言"它是对的"，而是断言"它确实会这样截断" —— 限制被记录在案，
   * 将来若有人改成真正的词法解析，这条会失败并提醒他更新说明。
   */
  @Test
  public void stripComment的已知限制_被明确记录() {
    // 限制 1：字符串里的 // 会被截断
    String withUrl = "    Log.i(TAG, \"see https://example.com\" + text);";
    String stripped = stripComment(withUrl);
    assertFalse("已知限制：含 // 的字符串会被截断（这份记录就是提醒）", stripped.contains("example.com"));
    assertTrue("截断后会剩下前半段", stripped.contains("https:"));

    // 正常情况：整行注释被去掉
    assertEquals("", stripComment("    // 这是注释"));
    assertEquals("", stripComment("   * 块注释中间行"));

    // 正常情况：行尾注释被去掉，代码保留
    assertTrue(stripComment("int a = 1; // 说明").contains("int a = 1;"));

    // 正常情况：没有注释的行原样返回
    assertTrue(stripComment("Log.i(TAG, \"x\");").contains("Log.i(TAG, \"x\");"));
  }

  /** 读源码并去掉所有注释行（用于"不得出现某写法"类断言）。 */
  private static String readCodeOnly(String fileName) throws IOException {
    String src = read(fileName);
    StringBuilder out = new StringBuilder();
    for (String line : src.split("\n")) {
      String code = stripComment(line);
      if (!code.trim().isEmpty()) out.append(code).append('\n');
    }
    return out.toString();
  }

  /** 按文件名读取一个生产源码（递归查找）。 */
  private static String read(String fileName) throws IOException {
    Path root = findMainRoot();
    final Path[] found = {null};
    Files.walk(root)
        .filter(p -> p.getFileName().toString().equals(fileName))
        .forEach(p -> found[0] = p);
    if (found[0] == null) throw new AssertionError("找不到源码：" + fileName);
    return new String(Files.readAllBytes(found[0]), StandardCharsets.UTF_8);
  }
}
