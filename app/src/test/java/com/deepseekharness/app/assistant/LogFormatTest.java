package com.deepseekharness.app.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 日志格式化**行为级**测试（JVM，零 Android 依赖）。
 *
 * <p><b>和 {@code LogPrivacyTest} 的分工</b>：
 * <ul>
 *   <li>{@code LogPrivacyTest}：扫源码断言"没有某种写法" —— 属于 <b>静态回归</b>，
 *       只能证明此刻代码长这样。
 *   <li>本文件：把<u>虚构的敏感内容</u>真的喂进格式化函数，断言输出里**找不到它** ——
 *       这是 <b>行为验证</b>，证明"即使有人真的把这些内容传进来，也不会落到日志里"。
 * </ul>
 *
 * <p>用户明确要求："不要把'源码里没找到某个关键词'当作完整的隐私证明"，
 * 所以两种测试都要有 —— 本文件补的就是缺的那一半。
 *
 * <p><b>所有测试数据都是本文件里编的假内容</b>，不含任何真实用户数据或凭据。
 */
public class LogFormatTest {

  // ===== 虚构的敏感内容（全部是编的）=====

  /** 虚构的"用户说的话"。 */
  private static final String FAKE_SPEECH = "假语音内容_给小明转五百块_假的";

  /** 虚构的"用户输入的问题"。 */
  private static final String FAKE_QUESTION = "假问题_我的支付密码是多少_假的";

  /** 虚构的"任务目标"。 */
  private static final String FAKE_GOAL = "假任务_给张三发一条消息说你好_假的";

  /** 虚构的"提醒内容"。 */
  private static final String FAKE_REMINDER = "假提醒_去银行转账_假的";

  /** 虚构的"任务结束语"（含内容）。 */
  private static final String FAKE_FINISH = "没办成：假任务_给李四转账_假的（第 3 步）";

  /** 虚构的 Token。 */
  private static final String FAKE_TOKEN = "sk-FAKE9999888877776666";

  /** 虚构的屏幕文本。 */
  private static final String FAKE_SCREEN = "假屏幕文字_聊天记录内容_假的";

  // ================================================================
  // 1. 逐项：每个格式化函数都不得漏出正文
  // ================================================================

  @Test
  public void 语音识别日志_不含用户说的话() {
    String out = LogFormat.speechRecognized(FAKE_SPEECH, true);
    assertNotNull(out);
    assertFalse("日志不得含用户说的话", out.contains(FAKE_SPEECH));
    assertFalse("不得含其中任何片段", out.contains("给小明转五百块"));
    assertTrue("应含字数（结构性信息）", out.contains(String.valueOf(FAKE_SPEECH.length()) + " 字"));
    assertTrue("应含是否命中唤醒词", out.contains("命中唤醒词=true"));
  }

  @Test
  public void 直接输入日志_不含用户输入的问题() {
    String out = LogFormat.directAsk(FAKE_QUESTION);
    assertNotNull(out);
    assertFalse("日志不得含用户输入", out.contains(FAKE_QUESTION));
    assertFalse("不得含片段", out.contains("支付密码"));
    assertTrue("应含字数", out.contains(String.valueOf(FAKE_QUESTION.length()) + " 字"));
  }

  @Test
  public void 任务开始日志_不含任务目标正文() {
    String out = LogFormat.taskStart(7, FAKE_GOAL, "Telegram");
    assertNotNull(out);
    assertFalse("日志不得含任务目标", out.contains(FAKE_GOAL));
    assertFalse("不得含片段", out.contains("给张三发一条消息"));
    assertTrue("应含代次号", out.contains("代次7"));
    assertTrue("应含目标字数", out.contains(String.valueOf(FAKE_GOAL.length()) + " 字"));
    assertTrue("应用名是结构性信息，可以保留", out.contains("Telegram"));
  }

  @Test
  public void 任务结束日志_不含结束语正文() {
    String out = LogFormat.taskEnd(FAKE_FINISH);
    assertNotNull(out);
    assertFalse("日志不得含任务结束语", out.contains(FAKE_FINISH));
    assertFalse("不得含片段", out.contains("给李四转账"));
    assertTrue("应含字数", out.contains(String.valueOf(FAKE_FINISH.length()) + " 字"));
  }

  @Test
  public void 提醒日志_不含提醒内容() {
    String out = LogFormat.alarmFired(FAKE_REMINDER);
    assertNotNull(out);
    assertFalse("日志不得含提醒内容", out.contains(FAKE_REMINDER));
    assertFalse("不得含片段", out.contains("去银行转账"));
    assertTrue("应含字数", out.contains(String.valueOf(FAKE_REMINDER.length()) + " 字"));
  }

  @Test
  public void 读屏指标_不含节点文本() {
    String out = LogFormat.dumpMetrics("窗口应用: com.example.chat", 17, 12, 0, 1832);
    assertNotNull(out);
    assertFalse("指标不得含屏幕文本", out.contains(FAKE_SCREEN));
    assertTrue("应含包名（结构性）", out.contains("com.example.chat"));
    assertTrue("应含节点行数", out.contains("节点行=17"));
    assertTrue("应含 viewId 计数", out.contains("带viewId=12"));
    assertTrue("应含字符数", out.contains("字符数=1832"));
  }

  @Test
  public void 守卫日志_只含阶段与包名() {
    String out = LogFormat.guardBlocked(3, "DENY_PROTECTED", "com.example.bank");
    assertNotNull(out);
    assertTrue(out.contains("第3步"));
    assertTrue(out.contains("DENY_PROTECTED"));
    assertTrue(out.contains("com.example.bank"));
  }

  // ================================================================
  // 2. 把假 Token 混进内容里 —— 也不能漏出去
  // ================================================================

  @Test
  public void 内容里夹带Token_也不会漏进日志() {
    // 模拟"用户说的话里恰好包含一串 Token"（比如他在念自己的 key）
    String speechWithToken = "假内容_我的key是" + FAKE_TOKEN + "_假的";
    String goalWithToken = "假目标_用" + FAKE_TOKEN + "调用_假的";
    String finishWithToken = "没办成：key=" + FAKE_TOKEN;

    assertFalse(
        "语音日志不得漏 Token", LogFormat.speechRecognized(speechWithToken, false).contains(FAKE_TOKEN));
    assertFalse(
        "任务日志不得漏 Token", LogFormat.taskStart(1, goalWithToken, "App").contains(FAKE_TOKEN));
    assertFalse(
        "结束日志不得漏 Token", LogFormat.taskEnd(finishWithToken).contains(FAKE_TOKEN));
    assertFalse(
        "提醒日志不得漏 Token", LogFormat.alarmFired("提醒_" + FAKE_TOKEN).contains(FAKE_TOKEN));
    assertFalse(
        "直达日志不得漏 Token", LogFormat.directAsk("问题_" + FAKE_TOKEN).contains(FAKE_TOKEN));
  }

  // ================================================================
  // 3. null / 空串边界（日志链路里很常见）
  // ================================================================

  @Test
  public void null输入_标记为null而不是空串() {
    // 区分"没传（null）"和"传了空串"对排障有用，且都不会抛
    assertTrue(LogFormat.speechRecognized(null, false).contains("null"));
    assertTrue(LogFormat.directAsk(null).contains("null"));
    assertTrue(LogFormat.taskStart(1, null, "App").contains("null"));
    assertTrue(LogFormat.taskEnd(null).contains("null"));
    assertTrue(LogFormat.alarmFired(null).contains("null"));
  }

  @Test
  public void 空串输入_长度为0() {
    assertTrue(LogFormat.speechRecognized("", false).contains("0 字"));
    assertTrue(LogFormat.directAsk("").contains("0 字"));
    assertTrue(LogFormat.taskEnd("").contains("0 字"));
  }

  @Test
  public void 各格式化函数对异常输入都不抛() {
    // 日志本身绝不能因为输入奇怪就崩掉主流程
    LogFormat.speechRecognized("\n\t\r", true);
    LogFormat.directAsk("\u0000\u0001");
    LogFormat.taskStart(0, "", "");
    LogFormat.taskEnd("\n");
    LogFormat.alarmFired(" ");
    LogFormat.dumpMetrics("", 0, 0, 0, 0);
    LogFormat.guardBlocked(0, "", "");
  }

  // ================================================================
  // 4. 结构性字段必须真的在（否则日志失去排障价值）
  // ================================================================

  @Test
  public void 结构性字段必须保留_否则日志就没用了() {
    // 隐私和数据可用性要同时满足：去掉正文，但不能把排障信息也一起去掉
    assertTrue("唤醒判定结果要留", LogFormat.speechRecognized("x", true).contains("命中唤醒词=true"));
    assertTrue("唤醒判定结果要留", LogFormat.speechRecognized("x", false).contains("命中唤醒词=false"));
    assertTrue("代次要留", LogFormat.taskStart(42, "x", "App").contains("代次42"));
    assertTrue("应用名要留", LogFormat.taskStart(1, "x", "Telegram").contains("Telegram"));
    assertTrue("节点统计要留", LogFormat.dumpMetrics("pkg", 5, 3, 1, 99).contains("带contentDescription=1"));
  }

  // ================================================================
  // 5. 生产代码路径覆盖（防止测试守护影子逻辑）
  // ================================================================

  // ================================================================
  // 6. Phase 13：反射全覆盖 + 反影子逻辑
  // ================================================================

  /** 唯一标记：只在测试里编出来的串，任何真实内容里都不会出现。 */
  private static final String MARK = "LFMARK9X2QP";

  /**
   * 每个 LogFormat 方法里"<b>哪个参数是用户内容</b>"的登记表。
   *
   * <p><b>为什么必须有这张表</b>：Phase 13 把 {@code LogFormat} 这个类名放进了
   * {@code LogPrivacyTest.SAFE_FIELDS}，理由不能只是"名字听着安全" ——
   * 万一以后有人加一个 {@code LogFormat.leakAll(text)}，白名单照样放行。
   * 这张表 + 下面两条测试给出真正的理由：
   * <ul>
   *   <li>枚举<b>每一个</b>静态方法，逐个喂唯一标记，断言输出里找不到它
   *       （连"截断后残留的前 12 个字符"也断言找不到 ——
   *        防止有人拿"截断"冒充"不记"）；</li>
   *   <li>表里没登记的方法 → 测试失败，逼着新增方法的人先声明参数意图。</li>
   * </ul>
   */
  private static final java.util.Map<String, int[]> CONTENT_ARGS =
      java.util.Map.of(
          "size", new int[] {0},                 // 只输出"N 字"
          "speechRecognized", new int[] {0},     // 用户说的话
          "directAsk", new int[] {0},            // 用户输入的问题
          "taskStart", new int[] {1},            // goal 是内容；appLabel 是应用名（结构性）
          "taskEnd", new int[] {0},              // 结束语含任务内容
          "alarmFired", new int[] {0},           // 提醒内容是用户写的
          "dumpMetrics", new int[] {},           // 第 0 参是"窗口应用: 包名"行（结构性）
          "guardBlocked", new int[] {},          // 判定名 + 包名（结构性）
          "modelAction", new int[] {0});         // 模型完整响应（D-13）

  @Test
  public void LogFormat每个方法都必须在内容参数表里登记() {
    java.util.List<String> missing = new java.util.ArrayList<>();
    for (java.lang.reflect.Method m : LogFormat.class.getDeclaredMethods()) {
      if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
      if (!CONTENT_ARGS.containsKey(m.getName())) missing.add(m.getName());
    }
    assertTrue(
        "以下 LogFormat 方法还没在 CONTENT_ARGS 里声明'哪个参数是用户内容'："
            + missing + "（新增方法必须先声明，否则下面的漏出测试会自动跳过它）",
        missing.isEmpty());
  }

  @Test
  public void LogFormat每个方法都不得回吐传入的内容() throws Exception {
    java.util.List<String> leaks = new java.util.ArrayList<>();
    String head = MARK.substring(0, 8); // 截断残留也要能抓到

    for (java.lang.reflect.Method m : LogFormat.class.getDeclaredMethods()) {
      if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
      m.setAccessible(true);
      int[] contentAt = CONTENT_ARGS.get(m.getName());
      if (contentAt == null) continue;

      Class<?>[] types = m.getParameterTypes();
      Object[] args = new Object[types.length];
      for (int i = 0; i < types.length; i++) {
        boolean isContent = false;
        for (int idx : contentAt) {
          if (idx == i) isContent = true;
        }
        if (isContent) args[i] = MARK + "_尾巴_" + MARK;
        else if (types[i] == String.class) args[i] = "com.example.app";
        else if (types[i] == boolean.class) args[i] = Boolean.FALSE;
        else if (types[i] == int.class) args[i] = Integer.valueOf(7);
        else if (types[i] == long.class) args[i] = Long.valueOf(7L);
        else args[i] = null;
      }

      Object out = m.invoke(null, args);
      if (!(out instanceof String)) continue;
      String s = (String) out;
      if (s.contains(MARK) || s.contains(head)) {
        leaks.add(m.getName() + " → " + s);
      }
    }
    assertTrue(
        "以下 LogFormat 方法把传入的内容回吐了（含截断残留）：\n" + String.join("\n", leaks),
        leaks.isEmpty());
  }

  /**
   * 允许"有行为测试但生产没直接调用"的方法 —— <b>必须写明原因与处置</b>。
   *
   * <p>反过来：表里登记了、但生产里已经被调用了 → 测试失败，逼着把这个条目删掉。
   * 这样"死代码冒充已覆盖"这件事会被一直盯着，不会慢慢烂掉。
   */
  private static final java.util.Map<String, String> NOT_DIRECTLY_CALLED =
      java.util.Map.of(
          "guardBlocked",
              "零调用（Phase 13 登记 D-15）：生产的守卫拦截日志走的是 "
                  + "GuardDecision.logReason(verdict, curPkg)（AssistantTaskRunner:343），"
                  + "不经过本方法。待决：要么接进来、要么删掉，"
                  + "不能长期留着一个'看起来被测过、实际没人用'的函数。");

  @Test
  public void 不许死代码冒充已被测试覆盖() throws Exception {
    java.util.List<String> unwired = new java.util.ArrayList<>();
    java.util.List<String> stale = new java.util.ArrayList<>();

    for (java.lang.reflect.Method m : LogFormat.class.getDeclaredMethods()) {
      if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
      String name = m.getName();
      boolean called = false;
      for (String src : productionSources()) {
        if (src.contains("LogFormat." + name + "(")) called = true;
      }
      if (!called && !NOT_DIRECTLY_CALLED.containsKey(name)) unwired.add(name);
      if (called && NOT_DIRECTLY_CALLED.containsKey(name)) {
        stale.add(name + "（登记说它没被调用，但现在生产里已经调了，请删掉这条登记）");
      }
    }
    assertTrue(
        "以下 LogFormat 方法生产里根本没调用，却在 LogFormatTest 里有行为测试 —— "
            + "那是影子逻辑，测了等于没测：" + unwired,
        unwired.isEmpty());
    assertTrue("死代码登记表已过期：\n" + String.join("\n", stale), stale.isEmpty());
  }

  @Test
  public void 生产代码确实调用了LogFormat() throws Exception {
    java.nio.file.Path root = findRoot();
    int callSites = 0;
    for (java.nio.file.Path p : java.nio.file.Files.walk(root)
        .filter(x -> x.toString().endsWith(".java"))
        .filter(x -> !x.toString().contains("/test/"))
        .collect(java.util.stream.Collectors.toList())) {
      String code = new String(java.nio.file.Files.readAllBytes(p), "UTF-8");
      if (code.contains("LogFormat.")) callSites++;
    }
    assertTrue(
        "至少要有 4 个生产文件真的在用 LogFormat（语音/直达/任务/提醒），实际 " + callSites,
        callSites >= 4);
  }

  private static java.nio.file.Path findRoot() {
    java.nio.file.Path[] candidates = {
      java.nio.file.Paths.get("src/main/java/com/deepseekharness/app"),
      java.nio.file.Paths.get("app/src/main/java/com/deepseekharness/app"),
      java.nio.file.Paths.get("../app/src/main/java/com/deepseekharness/app")
    };
    for (java.nio.file.Path p : candidates) {
      if (java.nio.file.Files.exists(p)) return p;
    }
    throw new AssertionError("找不到生产源码根目录");
  }

  /** 收集生产源码全文（去掉注释行），供"有没有真的被生产调用"这类断言使用。 */
  private static java.util.List<String> productionSources() throws Exception {
    java.nio.file.Path root = findRoot();
    java.util.List<String> out = new java.util.ArrayList<>();
    for (java.nio.file.Path p :
        java.nio.file.Files.walk(root)
            .filter(x -> x.toString().endsWith(".java"))
            .filter(x -> !x.toString().contains("/test/"))
            .collect(java.util.stream.Collectors.toList())) {
      StringBuilder sb = new StringBuilder();
      for (String line :
          new String(java.nio.file.Files.readAllBytes(p),
              java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
        String t = line.trim();
        if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue;
        int c = line.indexOf("//");
        sb.append(c >= 0 ? line.substring(0, c) : line).append('\n');
      }
      out.add(sb.toString());
    }
    assertFalse("应能找到生产源码", out.isEmpty());
    return out;
  }
}
