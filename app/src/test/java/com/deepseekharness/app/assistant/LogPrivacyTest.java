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
    // Phase 11：扩大变量名单，纳入模型输出与常见别名
    // Phase 13：改用与白名单测试同一个 logCalls()，两条扫描口径一致，
    //           不再各写一条正则（否则"黑名单漏了、白名单却说没事"这种错位没人发现）。
    Pattern bad =
        Pattern.compile(
            "\\+\\s*(text|goal|msg|screenText|dump|reply|content|rc|result|response|answer|body|payload|userText|userInput|input)\\b");

    for (LogCall call : logCalls()) {
      // 允许显式包了长度/摘要的形式
      if (call.text.contains("length()") || call.text.contains("redact")
          || call.text.contains("truncate")) {
        continue;
      }
      if (bad.matcher(call.args).find()) offenders.add(call.at + "  " + call.text.trim());
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
    List<String> offenders = new ArrayList<>();
    for (LogCall call : logCalls()) {
      if (call.text.contains("length()") || call.text.contains("redact")
          || call.text.contains("truncate") || call.text.contains("hash()")) {
        continue; // 显式长度/摘要形式一律放行
      }
      // ★ (A) 拼接形态：在**参数区间内**找所有 "+ 表达式"，逐个过审。
      //   旧版把 Log\. 和 [^)]*\+变量 写在同一条正则里，而 [^)]* 跨不过 ')'，
      //   导致每条 Log 只查到最后一个拼接变量 —— 前面的一律放过。
      // 第 0 段是首号 + 之前的整段（一般是 TAG 和开头那个字面量），不是拼接项，跳过
      java.util.List<String> operands = splitTop(call.args, '+');
      for (int pi = 1; pi < operands.size(); pi++) {
        String expr = operands.get(pi);
        String v = rootName(expr);
        if (v == null) continue;                    // 纯常量表达式
        if (isSafeExpr(expr)) continue;             // 结构性取值（长度/计数/布尔/安全调用）
        if (reviewed(call, v)) continue;            // 已人工审核的取值点（见下方说明）
        if (KNOWN_LEAKS.containsKey(call.at)) continue;
        offenders.add(call.at + "  拼接了未登记字段 '" + v + "'  " + call.text.trim());
      }

      // ★ (B) 参数形态：Log.i(TAG, resp) 这种**不带 + 号**的写法（A）完全看不见。
      //   D-13 修好之后正是这种形态，所以必须单独看一遍每个参数。
      for (String arg : splitArgs(call.args)) {
        String a = arg.trim();
        if (a.isEmpty()) continue;
        if (!hasIdentifier(a)) continue;            // 纯字面量/常量表达式
        if (hasTopLevelPlus(a)) continue;           // 带 + 的由 (A) 负责，不重复报
        String v = rootName(a);
        if (v == null) continue;
        if (isSafeExpr(a)) continue;
        if (reviewed(call, v)) continue;
        if (KNOWN_LEAKS.containsKey(call.at)) continue;
        offenders.add(call.at + "  参数 '" + a + "' 未经审核  " + call.text.trim());
      }
    }
    assertTrue(
        "以下日志点拼接了未经审核的字段（可能含用户/模型正文，应改为 length()/结构化指标，"
            + "或将其加入白名单并说明理由）：\n" + String.join("\n", offenders),
        offenders.isEmpty());
  }

  // ---------------------------------------------------------------- 扫描器

  /** 单个参数是不是"光一个标识符"。 */
  private static final Pattern BARE_ID =
      Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)");

  /** 纯 {@code a} / {@code a.b} / {@code a.b.c} 这种字段链（不含括号）。 */
  private static final Pattern CHAIN =
      Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)*");

  /** 以"无嵌套的方法调用"收尾，如 {@code x.optInt("s", 0)}。 */
  private static final Pattern LAST_CALL =
      Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\([^()]*\\)\\s*$");

  private static final Pattern IDENT = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

  /** 把字符串字面量抠掉（保留一对空引号占位，免得把跨引号的标识符拼出来）。 */
  private static String stripStrings(String s) {
    return s.replaceAll("\"(\\\\.|[^\"\\\\])*\"", "\"\"");
  }

  /**
   * 参数里（除了字符串字面量）还有没有标识符。
   *
   * <p>没有 → 这是纯常量表达式，安全；有 → 需要过白名单。
   * 比"是不是以引号开头结尾"更稳：三元 {@code (ok ? "成功" : "失败")}、
   * 多段字面量拼接这类写法都能正确判成安全，不会误报。
   */
  private static boolean hasIdentifier(String arg) {
    return IDENT.matcher(stripStrings(arg)).find();
  }

  /**
   * 允许出现在日志参数里的"安全调用"前缀 —— 都是只产出结构性信息的纯函数。
   *
   * <p><b>加进来必须有行为级背书</b>：{@code LogFormat} 靠的是
   * {@code LogFormatTest} 那条反射测试（枚举每个方法喂标记、断言不回吐）；
   * {@code SensitiveData.redact} 靠的是本类第 1 节那几条真跑一遍的脱敏测试。
   * 没有背书的调用不许进这张表。
   */
  private static final java.util.Set<String> SAFE_CALLS =
      java.util.Set.of(
          "LogFormat.",
          "SensitiveData.redact(",
          "String.valueOf(",
          "Integer.toString(", "Long.toString(", "Boolean.toString(",
          "GuardDecision.logReason(",
          "System.currentTimeMillis(", "System.nanoTime(",
          "TextUtils.isEmpty(", "android.text.TextUtils.isEmpty(",
          // DshaAssistActivity 的"定位自检"日志：列出已配置目标 id（配置数据，非屏幕内容）
          "ActionTargetStore.summary(",
          // DshaAccessibilityService：面板当前是否可见（布尔）
          "com.deepseekharness.app.assistant.AssistantPanel.visible(",
          // AssistantTaskRunner：等待前台时记录的当前包名
          "DshaAccessibilityService.currentPackage(");

  /**
   * 以这些方法收尾的取值 → 结构性（数值 / 布尔 / 枚举），不含正文。
   *
   * <p>注意 {@code optString} / {@code getString} / {@code opt} 之类<b>不在</b>这里：
   * 它们返回的是字符串，可能是内容，只能走 {@link #REVIEWED_SITES} 逐点审核。
   */
  private static final java.util.Set<String> SAFE_METHODS =
      java.util.Set.of(
          "length", "size", "isEmpty",
          "optInt", "optLong", "optDouble", "optBoolean",
          "getInt", "getLong", "getBoolean", "getKeyCode", "getAction",
          // 无障碍按键事件的重复次数（int），与 getKeyCode/getAction 同一类结构性取值
          "getRepeatCount",
          "currentTimeMillis", "nanoTime", "hashCode",
          "getSimpleName",  // 异常类名，用来排障且不含消息正文
          "screenW");       // 屏幕宽度（尺寸，本类只用它算吸边位置）

  /**
   * ★ 已人工审核的**取值点**登记表（Phase 13 新增）。
   *
   * <p><b>为什么不直接往 {@link #SAFE_FIELDS} 里加名字</b>：本次扫描器修好之后，
   * 一口气浮出 15 处以前"看不见"的拼接点（{@code attempt} {@code backoffMs}
   * {@code tx} {@code ty} {@code box} {@code source} {@code launcher} {@code r} …）。
   * 这些变量<b>名字都很通用</b>，一旦按名字放进白名单，等于宣布
   * "以后任何地方拼 {@code tx} 都安全" —— 那正是"白名单无限扩大"。
   *
   * <p>所以这里按 <b>{@code 文件 + 变量名 + 该行日志里的字面量锚点}</b> 逐点登记：
   * 换一处再拼同名变量，照样会被拦下来。
   *
   * <p><b>为什么锚点用字面量、不用行号</b>（Phase 13 变异 M4 实测出来的）：
   * 第一版是 <b>{@code 文件:行号|变量名}</b>，结果在别处删掉两行代码，
   * 三个已审站点<b>全部</b>因为行号漂移而失效 —— 一次无关改动就刷出 3 条假警报，
   * 这种登记表没人会认真维护。改成"这一行日志里那段独有的文案"之后，
   * 只要日志本身没改，行号怎么漂都不影响。
   *
   * <p>{@link #已审核取值点必须与实际情况一致()} 会盯着这张表：
   * 那行日志被删掉或改了文案 → 锚点对不上 → 测试失败，逼着同步更新。
   * 和 {@link #KNOWN_LEAKS} 是同一个"登记表必须与现实一致"的机制。
   */
  private static final java.util.List<Site> REVIEWED_SITES =
      java.util.List.of(
          new Site("AssistantApiClient.java", "attempt", "429 限流", "429 重试次数（int）"),
          new Site("AssistantApiClient.java", "backoffMs", "429 限流", "退避毫秒数（long）"),
          new Site("AssistantApiClient.java", "choices", "finish_reason=",
              "choices.getJSONObject(0).optString(\"finish_reason\", \"?\") —— "
                  + "模型响应的结束原因枚举（stop/length/…），不是回复正文；"
                  + "登记时已逐行确认没有把 content 取出来"),
          new Site("AssistantConfig.java", "source", "配置解析失败",
              "配置来源标识（sdcard/internal 之类）"),
          new Site("AssistantTaskRunner.java", "tx", "tap_xy 参数非法", "tap_xy 的 x 坐标（float）"),
          new Site("AssistantTaskRunner.java", "ty", "tap_xy 参数非法", "tap_xy 的 y 坐标（float）"),
          new Site("AssistantTaskRunner.java", "box", "没有焦点输入框", "box[0]/box[1] 坐标（int[]）"),
          new Site("AssistantPermitActivity.java", "a", "设置页打不开",
              "for (String a : actions) —— Settings 的 Intent action 常量"
                  + "（android.settings.* 之类），非用户内容"),
          new Site("DshaAssistActivity.java", "bad", "校验结果=",
              "validateTapCoords(...) 的拒绝原因（「坐标越界」这类固定文案），"
                  + "是自检诊断串，不是屏幕内容"),
          new Site("ConfigStoreLite.java", "name", "已从旧位置迁移",
              "配置文件名（内部固定名，非用户输入）"),
          new Site("ConfigStoreLite.java", "name", "创建默认配置",
              "配置文件名（内部固定名，非用户输入）"),
          new Site("ConfigStoreLite.java", "f", "失败: ", "File.toString() → 应用私有文件路径"),
          new Site("DshaAssistActivity.java", "r", "置信度=",
              "ActionTargetLocator.Result：r.found() 布尔、r.cx/r.cy 坐标、"
                  + "r.confidence 置信度、r.via 命中依据（=配置项 rule.by≈rule.value，"
                  + "是配置里的匹配规则，不是屏幕上的内容）"),
          new Site("DshaPebbleView.java", "tx", "静置半隐", "translationX 坐标（float）"),
          new Site("DshaPebbleView.java", "maxX", "拖完吸边", "拖动范围上限坐标（int）"),
          new Site("DshaAccessibilityService.java", "n", "节点 ", "n[0] 节点计数（int[]）"),
          new Site("DshaAccessibilityService.java", "launcher", "语音助手窗口事件到了", "前台包名"));

  /** 一条已审核取值点：文件 + 变量 + 该行日志里的字面量锚点 + 理由。 */
  private static final class Site {
    final String file;
    final String var;
    final String anchor;
    final String why;

    Site(String file, String var, String anchor, String why) {
      this.file = file;
      this.var = var;
      this.anchor = anchor;
      this.why = why;
    }
  }

  /** 这条日志调用里的这个变量，是不是已经逐点审核过（文件 + 变量 + 锚点都要对上）。 */
  private static boolean reviewed(LogCall call, String var) {
    for (Site s : REVIEWED_SITES) {
      if (s.file.equals(call.file) && s.var.equals(var) && call.text.contains(s.anchor)) {
        return true;
      }
    }
    return false;
  }

  /** 表达式里最靠前的那个标识符（去掉字符串字面量之后）；纯常量则返回 null。 */
  private static String rootName(String expr) {
    String e = stripStrings(expr).trim();
    Matcher m = IDENT.matcher(e);
    return m.find() ? m.group() : null;
  }

  /**
   * 这个取值表达式是不是"结构性"的。
   *
   * <p>判据按形态，不按名字 —— 这样 {@code p.attached} {@code o.handled} 这类
   * "对象.布尔字段"不用把 {@code p} {@code o} 这种通用名塞进白名单：
   * 字段名 {@code attached} {@code handled} 本来就在 {@link #SAFE_FIELDS} 里。
   */
  private static boolean isSafeExpr(String expr) {
    String e = expr.trim();

    if (!hasIdentifier(e)) return true;                                  // (0) 纯字面量/常量
    for (String prefix : SAFE_CALLS) {                                    // (1) 已背书的安全调用
      if (e.startsWith(prefix)) return true;
    }
    // (2) 这个取值项自己还含拼接（三元里常见）→ 逐项递归审，别只看最后一段
    java.util.List<String> parts = splitTop(e, '+');
    if (parts.size() > 1) {
      for (int i = 1; i < parts.size(); i++) {
        if (!isSafeExpr(parts.get(i))) return false;
      }
      return true;
    }
    // (3) 整项包在一对括号里 → 看里面
    String inner = unparen(e);
    if (inner != null) return isSafeExpr(inner);
    // (4) 三元：取值看两个分支，条件本身不影响"值是不是内容"
    java.util.List<String> tern = splitTernary(e);
    if (tern != null) return isSafeExpr(tern.get(1)) && isSafeExpr(tern.get(2));
    // (5) 判空 / 比较 → 取出来的是布尔，不是内容
    if (CMP.matcher(stripStrings(e)).find()) return true;
    // (6) 以结构性方法收尾（length()/optInt()/getKeyCode() …）
    Matcher call = LAST_CALL.matcher(e);
    if (call.find() && SAFE_METHODS.contains(call.group(1))) return true;
    // (7) 纯字段链：接收者或字段名在已审核表里就算过
    //     （这样 p.attached / o.handled 不用把 p、o 这种通用名塞进白名单）
    if (CHAIN.matcher(e).matches()) {
      String[] seg = e.split("\\.");
      if (SAFE_FIELDS.contains(seg[0])) return true;
      if (SAFE_FIELDS.contains(seg[seg.length - 1])) return true;
    }
    return BARE_ID.matcher(e).matches() && SAFE_FIELDS.contains(e);
  }

  /** 比较/判空运算符。命中说明这个表达式的值是布尔。 */
  private static final Pattern CMP = Pattern.compile("(==|!=|<=|>=|<|>)");

  /** 整串被一对括号包住 → 返回里面的内容；否则返回 null。 */
  private static String unparen(String e) {
    if (e.length() < 2 || e.charAt(0) != '(' || e.charAt(e.length() - 1) != ')') return null;
    int depth = 0;
    for (int i = 0; i < e.length(); i++) {
      char c = e.charAt(i);
      if (c == '(') depth++;
      else if (c == ')') {
        depth--;
        if (depth == 0 && i != e.length() - 1) return null; // 括号提前闭合，不是整体包住
      }
    }
    return e.substring(1, e.length() - 1);
  }

  /** {@code cond ? a : b} → [cond, a, b]；不是三元则返回 null。 */
  private static java.util.List<String> splitTernary(String e) {
    java.util.List<String> q = splitTop(e, '?');
    if (q.size() != 2) return null;
    java.util.List<String> c = splitTop(q.get(1), ':');
    if (c.size() != 2) return null;
    java.util.List<String> out = new ArrayList<>();
    out.add(q.get(0));
    out.add(c.get(0));
    out.add(c.get(1));
    return out;
  }

  /** 表达式里有没有"深度为 0 的 +"（有的话拼接项由 (A) 逐个审，这里跳过）。 */
  private static boolean hasTopLevelPlus(String s) {
    return splitTop(s, '+').size() > 1;
  }

  /** 按"深度为 0 的逗号"切分参数。 */
  private static List<String> splitArgs(String args) {
    return splitTop(args, ',');
  }

  /**
   * 按"深度为 0 的分隔符"切分（字符串字面量里的、括号里的都不算）。
   *
   * <p><b>已知限制</b>：不做词法分析，转义引号 {@code \"} 只靠前一个字符是不是
   * 反斜杠来判断；Java 源码里出现 {@code '\\' + '"'} 这种写法会被误判。
   * 当前生产代码里没有这种写法（扫描结果已人工核对过一遍）。
   */
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

  /** 一处 {@code Log.x(...)} 调用：位置、整段文本、括号配对后的参数区间。 */
  private static final class LogCall {
    final String file;  // 文件名
    final String at;    // 文件名:行号
    final String text;  // 整条调用（可能跨行合并）
    final String args;  // 参数区间（不含 "Log.x(" 与末尾 ")"）

    LogCall(String file, String at, String text, String args) {
      this.file = file;
      this.at = at;
      this.text = text;
      this.args = args;
    }
  }

  /**
   * 扫出生产代码里所有 {@code Log.x(...)} 调用的参数区间。
   *
   * <p><b>Phase 13 修了两个真实的漏检</b>（都是先发现、再补，不是顺手优化）：
   * <ol>
   *   <li><b>跨行调用漏检</b>：D-12 修复把 runText 日志拆成两行之后，
   *       第二行（{@code + " handled=" + o.handled ...}）里没有 {@code Log.} 字样，
   *       按单行扫描会整行跳过 —— <b>修缺陷反而制造出一条新的漏检路径</b>。
   *       现在先按括号配对把续行合并，再看。</li>
   *   <li><b>一行只查一个变量</b>：旧正则把 {@code Log\.[a-z]+\(} 和
   *       {@code [^)]*\+变量} 写在同一条里，而 {@code [^)]*} 不能跨过 {@code )}，
   *       所以每条 Log 调用<b>只检查最后一个拼接变量</b>，前面的全放过。
   *       现在先把参数区间切出来，再在区间内找<b>全部</b> {@code + 变量}。</li>
   * </ol>
   *
   * <p><b>已知限制（不掩盖）</b>：括号配对会把字符串字面量里的 {@code (} {@code )}
   * 也算进去（没有做词法分析）；遇到这种行可能提前收束或过度合并。
   * 影响是"可能少查几个变量"，不会凭空制造假阳性。
   */
  private static List<LogCall> logCalls() throws IOException {
    Pattern start = Pattern.compile("Log\\.[a-z]+\\(");
    List<LogCall> out = new ArrayList<>();
    for (Path p : productionSources()) {
      List<String> raw = Files.readAllLines(p, StandardCharsets.UTF_8);
      for (int i = 0; i < raw.size(); i++) {
        String line = stripComment(raw.get(i));
        if (line.trim().isEmpty()) continue;
        Matcher s = start.matcher(line);
        while (s.find()) {
          StringBuilder whole = new StringBuilder();
          // ★ depth 从 1 起：扫描是从 "Log.x(" 的**右**括号之后开始的，
          //   那个左括号没被扫到，得先记上。初值写成 0 的话第一个 ')' 会把
          //   depth 打成 -1，永远等不到 ==0 —— 于是会把后面几行全吞进来，
          //   参数区间里混进别的语句，报出一堆假阳性。（Phase 13 实测踩到。）
          int depth = 1;
          int j = i;
          boolean closed = false;
          int closePos = -1;
          // 从 "Log.x(" 的右括号开始做配对。
          // ★ 续行上限 25，且**注释行/空行不占预算** —— Phase 13 变异 M4 实测：
          //   一条日志中间夹了 5 行修复说明注释，调用被撑到 9 行，
          //   原先"最多续 5 行"的上限让它在注释那里就截断了，
          //   beforeInput 那处泄漏重新写回去也**扫不出来**。
          //   这个上限不是随便调大的，是被变异验证逼出来的。
          int budget = 0;
          for (; j < raw.size() && budget < 25; j++) {
            String cur = (j == i) ? line : stripComment(raw.get(j));
            if (j != i && cur.trim().isEmpty()) continue; // 纯注释行跳过，不占预算
            budget++;
            int base = whole.length();
            int prefix = (j == i) ? 0 : 1;
            whole.append(j == i ? "" : " ").append(cur);
            for (int k = (j == i ? s.end() : 0); k < cur.length(); k++) {
              char c = cur.charAt(k);
              if (c == '(') depth++;
              else if (c == ')') {
                depth--;
                if (depth == 0) {
                  closed = true;
                  closePos = base + prefix + k;
                  break;
                }
              }
            }
            if (closed) break;
          }
          int from = s.end();
          int to = closed ? closePos : whole.length();
          String args = (to > from) ? whole.substring(from, to) : "";
          out.add(new LogCall(p.getFileName().toString(),
              p.getFileName() + ":" + (i + 1), whole.toString(), args));
        }
      }
    }
    return out;
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
    assertFalse("指标不得包含 dump 文本切片", runner.contains("dump.substring(0, Math.min(240"));

    // ★ D-15（Phase 13）改动说明：这里原本断言 {@code runner.contains("节点行=")}，
    //   那是"指标串内联拼在 AssistantTaskRunner 里"才成立的写法。
    //   内联拼串的后果是：LogFormatTest 的"读屏指标不含节点文本"那条行为测试
    //   守护的是一段生产根本不走的影子逻辑（dumpMetrics 零调用）。
    //   现已改回调 LogFormat.dumpMetrics（输出逐字符不变），断言随之改成——
    //     ① 生产必须真的走那个函数（不是自己再拼一遍）；
    //     ② 那个函数的输出形态交给 LogFormatTest 做行为级验证。
    //   这不是放松：旧断言盯着"字面量还在不在"，换个实现方式就失效；
    //   新断言盯着"生产是否走被行为测试覆盖的那个函数"，比原来更难糊弄。
    assertTrue(
        "读屏指标必须走 LogFormat.dumpMetrics（不能自己内联拼一份）",
        runner.contains("LogFormat.dumpMetrics("));
    assertFalse("不得再内联拼指标串", runner.contains("\" 节点行=\""));
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
   *   <li><b>TAG</b>：日志标签常量（每个类里 {@code private static final String TAG}，
   *       编译期字面量，与用户内容无关；它是几乎每条 Log 调用的第一个参数，
   *       不放行会让参数级检查全是噪音）</li>
   *   <li><b>视图/窗口/配置标识</b>：viewId windowId displayId targetId
   *       SCHEMA（配置 schema 版本常量）</li>
   *   <li><b>坐标与尺寸</b>：x y size lp from target screenW</li>
   *   <li><b>计数/指标/耗时</b>：count nodeLines withId withCd chars waited versionCode</li>
   *   <li><b>metrics</b>：读屏指标串。<b>Phase 13 修正注释</b> —— 原先写的是
   *       "由 {@code LogFormat.dumpMetrics} 产出"，这个理由当时<b>不成立</b>：
   *       生产在 {@code AssistantTaskRunner} 里是内联拼的，压根没调那个函数（D-15）。
   *       现已改回调 {@code LogFormat.dumpMetrics}，理由才站得住，
   *       并且 {@code LogFormatTest} 的"指标不含节点文本"是真的守在生产路径上了。</li>
   *   <li><b>异常对象</b>：e t ex error detail（detail 已过 SensitiveData.redact）</li>
   *   <li><b>判定/枚举</b>：verdict d（配置迁移决策）</li>
   * </ul>
   *
   * <p><b>Phase 13 新增的门槛</b>：这张表是<b>按名字</b>生效的，所以越通用越危险。
   * 只因为"某处拼了个叫 {@code tx} 的坐标"就把 {@code tx} 加进来，等于宣布
   * "以后任何地方拼 {@code tx} 都安全"。通用名一律改用
   * {@link #REVIEWED_SITES}（按 {@code 文件:行号|变量名} 逐点登记）；
   * 只有"名字本身就是结构性语义"的（{@code attached} {@code handled} {@code pkg} …）
   * 才适合进这张表。
   */
  private static final java.util.Set<String> SAFE_FIELDS =
      java.util.Set.of(
          "on", "attached", "leaving", "pending", "handled", "dismiss", "ok", "hot",
          "allowed", "live", "hasAuthority", "targetMatches",
          "gen", "step",
          "pkg", "packageName", "appLabel", "appId", "ME", "TAG",
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
          "taskTarget",
          // ★ Phase 13：LogFormat 是本项目的"日志格式化"纯函数集合，
          //   所有方法只输出结构性字段（长度 / 布尔 / 包名 / 代次）。
          //   正则抓到的是**类名**（LogFormat.directAsk(...) 里的 LogFormat），
          //   不是变量。白名单放行这个类，靠的不是"名字听着安全"，而是
          //   LogFormatTest 里那条反射测试：枚举 LogFormat 的**每一个**静态方法、
          //   逐个喂唯一标记、断言输出里找不到它 —— 将来新增方法也会被自动扫到。
          //   没有那条测试，这一项就不该加进来。
          "LogFormat");

  /**
   * ★ 已知泄漏登记表（Phase 11 兜底测试首次运行即发现，黑名单完全漏掉）。
   *
   * <p>key = {@code 文件名:行号}，value = 泄漏说明。
   * <b>这是"待修清单"，不是"免责清单"</b> ——
   * {@link #已知泄漏登记必须与实际情况一致()} 会盯着它：
   * 修好一处就能划掉一处；清单只许减、不许无故增。
   *
   * <p><b>Phase 13 现状：当前为空。</b>
   * <ul>
   *   <li><b>D-12</b>（{@code AssistantPanel} runText 日志打用户输入 + 模型回复）
   *       —— 已修：{@code question} 走 {@code LogFormat.directAsk}，
   *       {@code o.reply} 不再进日志。<b>已从本表划掉。</b></li>
   *   <li><b>D-13</b>（{@code AssistantTaskRunner} 模型动作日志打完整响应 JSON）
   *       —— 已修：改走 {@code LogFormat.modelAction}，只记长度。<b>已从本表划掉。</b></li>
   * </ul>
   * 划掉的依据是"那一行已经不再是日志拼接形状"，这条判据由
   * {@link #已知泄漏登记必须与实际情况一致()} 自己守着 ——
   * 若有人把旧写法改回去，它会立刻重新被发现并需要重新登记，
   * 不存在"划掉了就永久免疫"。
   *
   * <p>注意：空的登记表只代表"<b>已登记的都修完了</b>"，
   * 不代表"不存在泄漏"。扫描器本身的能力边界见 {@link #记录扫描器的已知盲区()}。
   */
  private static final java.util.Map<String, String> KNOWN_LEAKS = java.util.Map.of();

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

  /**
   * 保证 {@link #REVIEWED_SITES} 与代码实际状态一致。
   *
   * <p>机制同 {@link #已知泄漏登记必须与实际情况一致()}：登记表<b>只许跟着现实走</b>。
   * 那行日志被删掉、挪走、或改成了别的取值方式 → 这个 key 就再也扫不出来 →
   * 本测试失败，逼着维护者把失效的登记删掉。
   * 少了这一条，站点级白名单会像名字白名单一样越积越脏。
   */
  @Test
  public void 已审核取值点必须与实际情况一致() throws IOException {
    List<String> stale = new ArrayList<>();
    for (Site s : REVIEWED_SITES) {
      boolean found = false;
      for (LogCall call : logCalls()) {
        if (!s.file.equals(call.file)) continue;
        if (!call.text.contains(s.anchor)) continue;   // 锚点（那段日志文案）对不上
        if (varsOf(call).contains(s.var)) found = true;
      }
      if (!found) stale.add(s.file + "|" + s.var + "（锚点「" + s.anchor + "」）  → " + s.why);
    }
    assertTrue(
        "REVIEWED_SITES 里有失效登记（那行日志已删/文案已改/取值方式已变，"
            + "留着只会让白名单越积越脏，请删掉或更新）：\n" + String.join("\n", stale),
        stale.isEmpty());
  }

  /** 这条日志调用里出现过的所有"取值根名"（拼接项 + 不带 + 的参数，两路都要算）。 */
  private static java.util.Set<String> varsOf(LogCall call) {
    java.util.Set<String> out = new java.util.HashSet<>();
    java.util.List<String> operands = splitTop(call.args, '+');
    for (int pi = 1; pi < operands.size(); pi++) {
      String v = rootName(operands.get(pi));
      if (v != null) out.add(v);
    }
    for (String arg : splitArgs(call.args)) {
      String a = arg.trim();
      if (a.isEmpty() || !hasIdentifier(a) || hasTopLevelPlus(a)) continue;
      String v = rootName(a);
      if (v != null) out.add(v);
    }
    return out;
  }

  /**
   * ★ 把扫描器的<b>已知盲区</b>钉在测试里。
   *
   * <p>用户要求："不要把'源码里没找到某个关键词'当作完整的隐私证明"。
   * 同理，也不能把"这套扫描器没报警"当作证明 —— 它有以下这些<b>确定存在</b>的盲区。
   * 每条都尽量做成可执行断言：要么证明"这条盲区当前没被踩中"，
   * 要么证明"扫描器确实看不见它"（把无能记录在案，而不是假装没有）。
   *
   * <ol>
   *   <li><b>只认 {@code Log.x(} 这一层</b>：先把内容拼进局部变量再打日志
   *       （{@code String s = "a" + text; Log.i(TAG, s);}）时，扫到的是 {@code s}。
   *       {@code s} 不在白名单 → 会被拦；取了个白名单里的名字（如 {@code detail}）
   *       → <b>漏检</b>。下面断言"当前生产里没有这种两段式写法"。</li>
   *   <li><b>StringBuilder 拼装不扫</b>：同上，一旦出现就说明盲区被踩中。</li>
   *   <li><b>stripComment 会截断字符串里的 {@code //}</b>（见
   *       {@link #stripComment的已知限制_被明确记录()}）。</li>
   *   <li><b>括号配不算词法</b>：字符串字面量里的 {@code (} {@code )} 会被计入深度，
   *       可能让参数区间提前收束 → 少查几个变量（只会漏，不会假阳性）。</li>
   *   <li><b>不覆盖非 Log 出口</b>：{@code System.out} / {@code printStackTrace} /
   *       {@code Logcat.write} 之类第三方日志库都不扫。下面断言生产里没有这些。</li>
   *   <li><b>没有运行时证据</b>：本类全部是静态扫描 + 纯函数行为测试，
   *       <b>不产生、也不验证真实 logcat 输出</b>。真机 logcat 取证属 NOT TESTED。</li>
   * </ol>
   */
  @Test
  public void 记录扫描器的已知盲区() throws IOException {
    List<String> twoStage = new ArrayList<>();  // 盲区 1：先拼进变量再打日志
    List<String> sbLogs = new ArrayList<>();    // 盲区 2：StringBuilder 拼装
    List<String> otherSinks = new ArrayList<>();// 盲区 5：非 Log 出口

    // 局部变量名：抓 "<类型> <名字> = ... + <标识符>;" 且该名字随后出现在 Log 里
    Pattern decl = Pattern.compile("\\s([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*[^\"\\n]*\\+\\s*[a-zA-Z_][a-zA-Z0-9_]*\\s*;");
    Pattern otherSink =
        Pattern.compile("(System\\.out\\.print|System\\.err\\.print|printStackTrace\\(\\s*\\)|android\\.util\\.Log\\.println)");

    for (Path p : productionSources()) {
      List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
      java.util.Set<String> logLines = new java.util.HashSet<>();
      for (String l : lines) {
        if (stripComment(l).contains("Log.")) logLines.add(stripComment(l));
      }
      for (int i = 0; i < lines.size(); i++) {
        String line = stripComment(lines.get(i));
        if (line.trim().isEmpty()) continue;
        Matcher d = decl.matcher(line);
        while (d.find()) {
          String var = d.group(1);
          for (String ll : logLines) {
            if (ll.matches(".*\\b" + java.util.regex.Pattern.quote(var) + "\\b.*")) {
              twoStage.add(p.getFileName() + ":" + (i + 1) + "  变量 '" + var + "' 先拼后打：" + line.trim());
              break;
            }
          }
        }
        if (line.contains("Log.") && line.contains(".append(")) {
          sbLogs.add(p.getFileName() + ":" + (i + 1) + "  " + line.trim());
        }
        if (otherSink.matcher(line).find()) {
          otherSinks.add(p.getFileName() + ":" + (i + 1) + "  " + line.trim());
        }
      }
    }

    assertTrue(
        "盲区 1 被踩中：以下位置先把内容拼进局部变量、再拿去打日志 —— "
            + "扫描器只看 Log 那一行，看不见变量里装了什么：\n" + String.join("\n", twoStage),
        twoStage.isEmpty());
    assertTrue(
        "盲区 2 被踩中：以下日志点用 StringBuilder 拼装，扫描器不覆盖：\n"
            + String.join("\n", sbLogs),
        sbLogs.isEmpty());
    assertTrue(
        "盲区 5 被踩中：以下位置走了 Log 之外的输出口，本套扫描器完全不看：\n"
            + String.join("\n", otherSinks),
        otherSinks.isEmpty());

    // 盲区 6（无运行时证据）：断言本类确实不依赖 logcat —— 只做源码级扫描。
    // 这一条是"记录"，不是"保证"：真正的运行时取证需要真机，本轮 NOT TESTED。
    assertTrue("本类是静态扫描器，不做运行时日志取证（真机 logcat 验证属 NOT TESTED）", true);
  }

  // ================================================================
  // 2.5 SOURCE → SINK 污点分析（Phase 16 新增，修 P1-1）
  // ================================================================

  /**
   * ★ <b>Phase 16 P1-1 的核心修复</b>：不再靠"变量名"判断，改用
   * {@link LogTaint} 做 SOURCE → SINK 污点分析。
   *
   * <p><b>为什么要换模型</b>：旧检查判的是"Log 参数里有没有出现某个名字"。
   * Phase 15 独立审查实测：
   * <pre>
   *   String e = question;                     // 用户输入 → 变量 e
   *   android.util.Log.i(TAG, "x=" + e);       // e 不在任何黑名单里
   * </pre>
   * 结果 **248 条测试全绿** —— 用户输入就这么进了 logcat。
   * 名字是不可靠的证据：改一次名、中转一次就没了。
   *
   * <p>新模型判断的是"敏感来源有没有流到日志出口"，因此下面三种形状**都必须被抓到**
   * （每一条都有对应的变异验证，见 {@code MUTATION-RESULT.md}）：
   * <ol>
   *   <li>{@code question} 改名成 {@code e} 后直接进 Log</li>
   *   <li>中间加一层 {@code String tmp = e;} 再进 Log</li>
   *   <li>两层传播 {@code String a = input; String b = a;} 再进 Log</li>
   * </ol>
   *
   * <p><b>能力边界（写在这里，不假装全覆盖）</b>：{@link LogTaint} 只做
   * <b>方法内</b>分析 + 已登记返回值的跨方法传播。
   * 没登记的方法返回值按"干净"处理，字段污点只做类名+字段名近似。
   * 因此它<b>可能漏报</b>。它保证的是"看得见的路径上一定会报"。
   */
  @Test
  public void 污点分析_敏感来源不得流入日志出口() throws Exception {
    List<String> offenders = new ArrayList<>();
    for (Path p : productionSources()) {
      for (LogTaint.Finding f : LogTaint.scan(p)) {
        offenders.add(f.toString());
      }
    }
    assertTrue(
        "以下位置存在「敏感来源 → 日志出口」的污点流（LogTaint 方法内污点分析）：\n"
            + String.join("\n", offenders),
        offenders.isEmpty());
  }

  // ================================================================
  // 2.6 扫描器自证（CANARY）—— Phase 16 阶段 3，修「抽查 #10」
  // ================================================================

  /**
   * ★ <b>Phase 16 阶段 3</b>：给扫描器装"自检"，让它不能悄悄失效。
   *
   * <p><b>问题（Phase 15 抽查 #10 实测）</b>：把 {@code 日志调用不得直接拼接用户内容变量()}
   * 里那条检测规则的黑名单清空之后，**整套 248 条测试依然全绿**。
   * 静态扫描类测试只能检测"生产代码里有没有某种形状"，
   * 检测不了"扫描器自己还灵不灵" —— 于是它可以被无声地废掉。
   *
   * <p><b>解法</b>：内置一批<b>必须被正确判定</b>的样例
   * （{@link LogTaint#selfCheckCases()}），每个样例都断言"该报 / 不该报"：
   * <ul>
   *   <li>{@code SAFE_*} 必须<b>不报</b> —— 防止有人把规则改得过度敏感、假阳性泛滥；</li>
   *   <li>{@code LEAK_*} 必须<b>报</b> —— 防止规则被削弱；</li>
   *   <li>{@code UNKNOWN_*} 用<b>随机变量名 + 多层中转</b>，必须<b>报</b>
   *       —— 专门盯住 P1-1 那类绕过形状（改名 / 一层 / 两层 / 三层）。</li>
   * </ul>
   *
   * <p>这样无论谁削弱了 {@code PARAM_SEEDS}、{@code SANITIZERS} 还是传播逻辑，
   * 总有一个样例会红。
   */
  @Test
  public void 扫描器自证_内置样例必须被正确判定() {
    List<String> wrong = new ArrayList<>();
    for (LogTaint.CanaryCase c : LogTaint.selfCheckCases()) {
      List<LogTaint.Finding> found;
      try {
        found = LogTaint.scanText("AssistantPanel", c.source);
      } catch (RuntimeException e) {
        wrong.add(c.id + " 扫描时抛异常: " + e);
        continue;
      }
      boolean did = !found.isEmpty();
      if (c.mustFind && !did) {
        wrong.add(c.id + " 应被判为泄露，但扫描器**没有报**（说明检测能力被削弱了）");
      } else if (!c.mustFind && did) {
        wrong.add(c.id + " 应判为安全，但扫描器**误报**了：" + found.get(0));
      }
    }
    assertTrue(
        "扫描器自证失败（这些样例是它的「健康证明」，任何一个不对都说明扫描器已不可信）：\n"
            + String.join("\n", wrong),
        wrong.isEmpty());
  }

  /** 自证样例本身必须覆盖两个方向，防止有人只留 SAFE 样例糊弄过去。 */
  @Test
  public void 扫描器自证_样例集必须同时含正向与负向() {
    int safe = 0;
    int leak = 0;
    for (LogTaint.CanaryCase c : LogTaint.selfCheckCases()) {
      if (c.mustFind) leak++;
      else safe++;
    }
    assertTrue("至少要有 2 条『应判为安全』的样例（防假阳性方向）", safe >= 2);
    assertTrue("至少要有 3 条『应判为泄露』的样例（防漏检方向）", leak >= 3);
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
