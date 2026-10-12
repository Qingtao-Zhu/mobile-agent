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
}
