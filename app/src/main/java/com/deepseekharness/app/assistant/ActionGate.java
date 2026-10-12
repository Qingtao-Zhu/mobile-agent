package com.deepseekharness.app.assistant;

/**
 * 动作闸门：**派发手势之前**唯一的放行判定（2026-10-11 离线安全验证轮新增）。
 *
 * <p><b>为什么要有这个类</b>：用户要求"验证实际的手势派发函数没有被调用，
 * 而不只是验证返回了错误字符串"。要做到这一点，必须让"决定是否派发"这件事
 * 本身成为**可以在 JVM 里跑、可以用替身观察**的纯逻辑 —— 而不是埋在
 * {@code AccessibilityService} 里（那是 Android 类，JVM 测不到）。
 *
 * <p>所以：判定收敛到 {@link #decide}（纯函数），执行收敛到 {@link #runGuarded}
 * （把"只有 ALLOW 才调用 dispatch"这件事写在一处，测试可以传一个计数替身进去，
 * 直接断言拒绝场景下**派发次数为 0**）。
 *
 * <p>生产代码里，{@code DshaAccessibilityService.verifyActionTarget()} 调用
 * {@link #decide}，不是 {@code ALLOW} 就抛异常 —— 也就是说**这条判定真的在生产路径上**，
 * 不是为测试而写的影子逻辑。
 *
 * <h3>失败关闭（fail-closed）</h3>
 * 四种输入组合里只有一种放行：
 * <pre>
 *   hasAuthority=false  → REJECT_NO_AUTHORITY   （没有授权上下文，一律不放）
 *   live=false          → REJECT_STALE          （任务被取消 / 被新任务顶替）
 *   targetMatches=false → REJECT_TARGET_CHANGED （当前界面不是任务认准的那个）
 *   三者皆真            → ALLOW
 * </pre>
 *
 * <p><b>注意</b>：{@code hasAuthority=false} 时**拒绝**，这是相对旧行为的一处收紧 ——
 * 旧代码写的是 {@code if (authority != null && ...)}，即"没有授权上下文就不检查"。
 * 那条路径在接入 D-01 之前意味着"永远不检查"；收紧之后，
 * **任何没有显式授权的写操作都发不出去**。
 * 读操作（dump）不走这里，所以面板读屏不受影响。
 *
 * <p><b>能力边界</b>：{@code targetMatches} 来自 {@code ScreenTarget.matches()}，
 * 只能判「显示器 + 窗口 + 包名」，**判不了页面语义、判不了控件**。
 * 页面级判断依赖上层每步重新 dump。此处不把它包装成更强的保证。
 */
public final class ActionGate {

  private ActionGate() {}

  /** 判定结果。 */
  public enum Decision {
    /** 允许派发。 */
    ALLOW,
    /** 没有授权上下文 —— fail-closed。 */
    REJECT_NO_AUTHORITY,
    /** 任务已失效（取消 / 被顶替）。 */
    REJECT_STALE,
    /** 当前界面身份与任务认准的不符。 */
    REJECT_TARGET_CHANGED
  }

  /**
   * 纯函数判定：只有三项全真才放行。
   *
   * @param hasAuthority  是否存在授权上下文（{@code ACTION_AUTHORITY} 非空）
   * @param live          该任务是否仍有效（未被取消、代次未被顶替）
   * @param targetMatches 当前界面身份是否仍等于任务认准的身份
   */
  public static Decision decide(boolean hasAuthority, boolean live, boolean targetMatches) {
    if (!hasAuthority) return Decision.REJECT_NO_AUTHORITY;
    if (!live) return Decision.REJECT_STALE;
    if (!targetMatches) return Decision.REJECT_TARGET_CHANGED;
    return Decision.ALLOW;
  }

  /**
   * 带守卫执行：**只有 ALLOW 才调用 {@code dispatch}**，并返回判定结果。
   *
   * <p>把"判定"和"因此是否调用派发"写在同一处，是为了让测试能用一个
   * 计数替身直接观察：拒绝场景下 {@code dispatch} 到底有没有被调用。
   * 这样断言的是**行为**（没派发），而不是**返回值**（返回了错误字符串）。
   */
  public static Decision runGuarded(
      boolean hasAuthority, boolean live, boolean targetMatches, Runnable dispatch) {
    Decision d = decide(hasAuthority, live, targetMatches);
    if (d == Decision.ALLOW && dispatch != null) {
      dispatch.run();
    }
    return d;
  }

  /** 人类可读的拒绝原因（进日志/面板文案）。 */
  public static String reason(Decision d) {
    switch (d) {
      case REJECT_NO_AUTHORITY:
        return "没有任务授权上下文，拒绝派发";
      case REJECT_STALE:
        return "任务已取消或被新任务顶替，拒绝派发";
      case REJECT_TARGET_CHANGED:
        return "界面已不是任务认准的那个（应用/窗口变了），拒绝派发";
      default:
        return "允许派发";
    }
  }

  /**
   * 拒绝原因对应的异常码。
   *
   * <p>{@code SCREEN_TARGET_CHANGED} 沿用既有字面量 —— 上层（{@code AssistantTaskRunner}）
   * 已经在捕获它并走"停手重新评估"分支，不要改这个名字。
   */
  public static String exceptionCode(Decision d) {
    switch (d) {
      case REJECT_TARGET_CHANGED:
        return "SCREEN_TARGET_CHANGED";
      case REJECT_STALE:
        return "ACTION_NOT_AUTHORIZED";
      case REJECT_NO_AUTHORITY:
        return "ACTION_NOT_AUTHORIZED";
      default:
        return null; // ALLOW 不抛
    }
  }

  /** 是否应当在派发前抛异常（即不是 ALLOW）。 */
  public static boolean mustThrow(Decision d) {
    return d != Decision.ALLOW;
  }
}
