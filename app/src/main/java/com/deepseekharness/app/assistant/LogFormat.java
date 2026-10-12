package com.deepseekharness.app.assistant;

/**
 * 日志格式化（2026-10-11 日志隐私轮抽出）。
 *
 * <p><b>为什么要有这个类</b>：用户要求"在不引入 Android 依赖的情况下，增加 JVM 测试，
 * 验证日志格式化方法<b>只接收结构性字段</b>，不会拼接用户正文"。
 *
 * <p>光靠"扫源码断言没有某个写法"是不够的 —— 那是<b>静态回归</b>，
 * 只能证明"此刻代码长这样"，不能证明"喂进去敏感内容也不会漏出来"。
 * 把格式化抽成纯函数之后，测试可以真的**喂一段虚构的用户文本进去、
 * 断言输出里找不到它** —— 这才是行为级验证。
 *
 * <p><b>每个方法只允许出现</b>：阶段名、长度、布尔标志、包名/应用名、代次号。
 * <b>不允许出现</b>：用户说的话、用户输入的问题、任务目标、提醒内容、
 * 屏幕节点文本、凭据、Token。
 *
 * <p>本类零 Android 依赖（只用 {@code java.lang}），所以能在 JVM 测试里直接跑。
 */
final class LogFormat {

  private LogFormat() {}

  /** 长度描述：只给字数，不给内容。null 单独标出来（避免和"空串"混淆）。 */
  static String size(String s) {
    return s == null ? "null" : s.length() + " 字";
  }

  /** 语音识别结果。正文不出现在日志里，只给字数和是否命中唤醒词。 */
  static String speechRecognized(String text, boolean hotword) {
    return "语音识别: " + size(text) + " 命中唤醒词=" + hotword;
  }

  /** 文本直达入口。只记录"有没有传进来 + 多长"。 */
  static String directAsk(String text) {
    return "text-direct ask: " + size(text);
  }

  /**
   * 任务开跑。
   *
   * <p>{@code appLabel} 是应用显示名（"Telegram"这种），不是用户内容，保留它有利于排障；
   * {@code goal} 是用户让助手干什么（"给小明发消息"），**只记长度**。
   */
  static String taskStart(int generation, String goal, String appLabel) {
    return "任务开跑(代次" + generation + ") 目标=" + size(goal) + " 应用=" + appLabel;
  }

  /** 任务结束。msg 里含任务内容（"没办成：…"），只记长度；正文留在面板给用户看。 */
  static String taskEnd(String msg) {
    return "任务结束: " + size(msg);
  }

  /** 提醒触发。提醒内容是用户写的（"给妈妈打电话"），只记长度。 */
  static String alarmFired(String msg) {
    return "提醒触发: " + size(msg);
  }

  /** 读屏指标：应用包名 + 节点结构统计，不含任何节点文本。 */
  static String dumpMetrics(String pkgLine, int nodeLines, int withId, int withCd, int chars) {
    return pkgLine + " 节点行=" + nodeLines + " 带viewId=" + withId
        + " 带contentDescription=" + withCd + " 字符数=" + chars;
  }

  /** 守卫拦截：只给阶段、包名与拒绝类型。 */
  static String guardBlocked(int step, String verdictName, String pkg) {
    return "第" + step + "步守卫拦截: " + verdictName + " 包=" + pkg;
  }
}
