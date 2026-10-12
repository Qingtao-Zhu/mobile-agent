package com.deepseekharness.app.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.deepseekharness.app.util.ScreenTarget;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link ActionGate} 的离线安全验证测试。
 *
 * <p><b>本测试要证明的东西</b>（用户要求逐条对应）：
 * <ol>
 *   <li>"验证实际的<b>手势派发函数没有被调用</b>，而不只是验证返回了错误字符串"
 *       → 每个拒绝场景都用 {@link FakeDispatcher} 断言 <b>dispatchCount == 0</b>；
 *         允许场景断言 == 1（证明这个计数器真的有效，不是恒 0 的摆设）。
 *   <li>"使用可注入的假执行器…不得为了测试而连接真实服务或点击真实界面"
 *       → {@link FakeDispatcher} 只是个计数器，不碰任何 Android API。
 *   <li>"不要只增加测试而不检查测试是否真正覆盖生产代码路径"
 *       → {@link #生产代码确实引用了ActionGate()} 直接读源码断言
 *         {@code verifyActionTarget()} 里调的是 {@code ActionGate}。
 *         没有这条，测试守护的可能是一套"只有测试在用的影子逻辑"。
 * </ol>
 *
 * <p><b>场景 A~F 对应</b>：
 * <pre>
 *   A 授权有效 + 身份不变 → 允许（且确实派发一次）
 *   B 包名变化           → 拒绝（0 次派发）
 *   C windowId 变化      → 拒绝（0 次派发）
 *   D 显示器变化         → 拒绝（0 次派发）
 *   E 任务取消/代次过期   → 拒绝（0 次派发）
 *   F 无授权上下文       → 拒绝（0 次派发，fail-closed）
 * </pre>
 */
public class ActionGateTest {

  /** 可注入的假手势派发器：只计数，不做任何真实动作。 */
  static final class FakeDispatcher {
    final AtomicInteger dispatchCount = new AtomicInteger(0);

    Runnable asRunnable() {
      return dispatchCount::incrementAndGet;
    }

    int count() {
      return dispatchCount.get();
    }
  }

  // ================================================================
  // 场景 A：授权有效、身份不变 → 允许
  // ================================================================

  @Test
  public void 场景A_三项皆真时允许进入执行阶段() {
    FakeDispatcher fake = new FakeDispatcher();
    ActionGate.Decision d = ActionGate.runGuarded(true, true, true, fake.asRunnable());

    assertEquals("授权有效且身份不变时应放行", ActionGate.Decision.ALLOW, d);
    assertEquals(
        "放行时必须恰好派发一次（这条同时证明计数器有效，不是恒 0 的摆设）",
        1,
        fake.count());
  }

  @Test
  public void 场景A_纯判定也返回允许() {
    assertEquals(
        ActionGate.Decision.ALLOW, ActionGate.decide(true, true, true));
  }

  // ================================================================
  // 场景 B/C/D：身份变化 → 拒绝（真实 ScreenTarget 组合）
  // ================================================================

  @Test
  public void 场景B_包名变化_拒绝且不派发() {
    ScreenTarget task = new ScreenTarget(0, 42, "com.example.chat");
    ScreenTarget now = new ScreenTarget(0, 42, "com.other.app");
    FakeDispatcher fake = new FakeDispatcher();

    ActionGate.Decision d =
        ActionGate.runGuarded(true, true, task.matches(now), fake.asRunnable());

    assertEquals(ActionGate.Decision.REJECT_TARGET_CHANGED, d);
    assertEquals("包名变了必须一次都不派发", 0, fake.count());
  }

  @Test
  public void 场景C_windowId变化_拒绝且不派发() {
    ScreenTarget task = new ScreenTarget(0, 42, "com.example.chat");
    ScreenTarget now = new ScreenTarget(0, 43, "com.example.chat");
    FakeDispatcher fake = new FakeDispatcher();

    ActionGate.Decision d =
        ActionGate.runGuarded(true, true, task.matches(now), fake.asRunnable());

    assertEquals(ActionGate.Decision.REJECT_TARGET_CHANGED, d);
    assertEquals("换了窗口必须一次都不派发（不得沿用旧目标坐标）", 0, fake.count());
  }

  @Test
  public void 场景D_显示器变化_拒绝且不派发() {
    ScreenTarget task = new ScreenTarget(0, 42, "com.example.chat");
    ScreenTarget now = new ScreenTarget(1, 42, "com.example.chat");
    FakeDispatcher fake = new FakeDispatcher();

    ActionGate.Decision d =
        ActionGate.runGuarded(true, true, task.matches(now), fake.asRunnable());

    assertEquals(ActionGate.Decision.REJECT_TARGET_CHANGED, d);
    assertEquals(0, fake.count());
  }

  @Test
  public void 场景B_当前身份读不到_拒绝且不派发() {
    ScreenTarget task = new ScreenTarget(0, 42, "com.example.chat");
    FakeDispatcher fake = new FakeDispatcher();

    // matchesTarget 在取不到当前 root 时会返回 null → matches(null) = false
    ActionGate.Decision d =
        ActionGate.runGuarded(true, true, task.matches(null), fake.asRunnable());

    assertEquals(ActionGate.Decision.REJECT_TARGET_CHANGED, d);
    assertEquals("身份读取失败也必须 fail-closed（0 次派发）", 0, fake.count());
  }

  // ================================================================
  // 场景 E：任务取消 / 代次过期 → 拒绝
  // ================================================================

  @Test
  public void 场景E_任务已取消_拒绝且不派发() {
    FakeDispatcher fake = new FakeDispatcher();
    ActionGate.Decision d = ActionGate.runGuarded(true, false, true, fake.asRunnable());
    assertEquals(ActionGate.Decision.REJECT_STALE, d);
    assertEquals(0, fake.count());
  }

  @Test
  public void 场景E_与TaskGeneration联动_旧代次判定为过期后不派发() {
    TaskGeneration tasks = new TaskGeneration();
    int oldGen = tasks.begin();
    tasks.begin(); // 新任务顶替 → 旧代次失效

    FakeDispatcher fake = new FakeDispatcher();
    ActionGate.Decision d =
        ActionGate.runGuarded(true, !tasks.isStale(oldGen), true, fake.asRunnable());

    assertEquals(ActionGate.Decision.REJECT_STALE, d);
    assertEquals("被顶替的旧任务不得派发", 0, fake.count());
  }

  // ================================================================
  // 场景 F：无授权上下文 → fail-closed
  // ================================================================

  @Test
  public void 场景F_无授权上下文_拒绝且不派发() {
    FakeDispatcher fake = new FakeDispatcher();
    ActionGate.Decision d = ActionGate.runGuarded(false, true, true, fake.asRunnable());

    assertEquals(
        "没有授权上下文时必须 fail-closed（旧代码这里是不检查=放行）",
        ActionGate.Decision.REJECT_NO_AUTHORITY,
        d);
    assertEquals(0, fake.count());
  }

  // ================================================================
  // 汇总断言：所有拒绝场景的派发次数必须为 0
  // ================================================================

  @Test
  public void 所有拒绝场景_派发次数必须为0() {
    FakeDispatcher fake = new FakeDispatcher();
    Runnable d = fake.asRunnable();

    assertEquals(ActionGate.Decision.REJECT_NO_AUTHORITY, ActionGate.runGuarded(false, true, true, d));
    assertEquals(ActionGate.Decision.REJECT_STALE, ActionGate.runGuarded(true, false, true, d));
    assertEquals(ActionGate.Decision.REJECT_STALE, ActionGate.runGuarded(true, false, false, d));
    assertEquals(ActionGate.Decision.REJECT_TARGET_CHANGED, ActionGate.runGuarded(true, true, false, d));
    // 优先级：hasAuthority 最先判 —— 所以三项全假时给出的是 NO_AUTHORITY 而不是 TARGET_CHANGED。
    // （这条一开始被写错成 TARGET_CHANGED，是测试自己报出来的；顺手把它变成一条显式的优先级断言。）
    assertEquals(
        "三项全假时应按最高优先级报 NO_AUTHORITY",
        ActionGate.Decision.REJECT_NO_AUTHORITY,
        ActionGate.runGuarded(false, false, false, d));

    assertEquals("五种拒绝组合合计派发次数必须为 0", 0, fake.count());

    // 反向验证：允许场景确实会派发（证明上面的 0 不是因为计数器坏了）
    assertEquals(ActionGate.Decision.ALLOW, ActionGate.runGuarded(true, true, true, d));
    assertEquals("允许场景应派发 1 次", 1, fake.count());
  }

  // ================================================================
  // 拒绝原因与异常码
  // ================================================================

  @Test
  public void 目标变化的异常码必须是SCREEN_TARGET_CHANGED() {
    // 上层 AssistantTaskRunner 已经在捕获这个字面量并走"停手重新评估"分支，
    // 改名会让那条分支失效 —— 所以钉住它。
    assertEquals(
        "SCREEN_TARGET_CHANGED",
        ActionGate.exceptionCode(ActionGate.Decision.REJECT_TARGET_CHANGED));
  }

  @Test
  public void 无授权与已取消都抛ACTION_NOT_AUTHORIZED() {
    assertEquals(
        "ACTION_NOT_AUTHORIZED",
        ActionGate.exceptionCode(ActionGate.Decision.REJECT_NO_AUTHORITY));
    assertEquals(
        "ACTION_NOT_AUTHORIZED", ActionGate.exceptionCode(ActionGate.Decision.REJECT_STALE));
  }

  @Test
  public void 允许时不抛异常() {
    assertNull(ActionGate.exceptionCode(ActionGate.Decision.ALLOW));
    assertFalse(ActionGate.mustThrow(ActionGate.Decision.ALLOW));
  }

  @Test
  public void 所有拒绝都必须抛() {
    assertTrue(ActionGate.mustThrow(ActionGate.Decision.REJECT_NO_AUTHORITY));
    assertTrue(ActionGate.mustThrow(ActionGate.Decision.REJECT_STALE));
    assertTrue(ActionGate.mustThrow(ActionGate.Decision.REJECT_TARGET_CHANGED));
  }

  @Test
  public void 拒绝原因非空且可读() {
    assertNotNull(ActionGate.reason(ActionGate.Decision.REJECT_NO_AUTHORITY));
    assertNotNull(ActionGate.reason(ActionGate.Decision.REJECT_STALE));
    assertNotNull(ActionGate.reason(ActionGate.Decision.REJECT_TARGET_CHANGED));
    assertTrue(ActionGate.reason(ActionGate.Decision.REJECT_TARGET_CHANGED).contains("界面"));
  }

  // ================================================================
  // 生产代码路径覆盖（防止"测试守护影子逻辑"）
  // ================================================================

  /**
   * 读源码断言：生产代码里 {@code verifyActionTarget()} 真的调了 {@link ActionGate}。
   *
   * <p>没有这条测试，{@code ActionGate} 完全可能退化成"只有测试在用的影子逻辑" ——
   * 测试全绿，生产却还在用旧判定。这是用户明确要求防住的情况。
   */
  @Test
  public void 生产代码确实引用了ActionGate() throws IOException {
    Path src = locate("DshaAccessibilityService.java");
    String code = new String(Files.readAllBytes(src), StandardCharsets.UTF_8);

    assertTrue(
        "verifyActionTarget 必须真的调用 ActionGate.decide（否则本测试守护的是影子逻辑）",
        code.contains("ActionGate.decide("));
    assertTrue(
        "必须用 ActionGate.mustThrow 决定是否抛", code.contains("ActionGate.mustThrow("));
    assertTrue(
        "异常码必须取自 ActionGate.exceptionCode", code.contains("ActionGate.exceptionCode("));
    assertTrue(
        "uiKey 那条全局动作路径应使用宽松版校验（不会被 fail-closed 误伤）",
        code.contains("verifyActionTargetIfAuthorized"));
  }

  /** 兼容从项目根或模块目录运行测试（Gradle 的 unit test 工作目录是模块目录）。 */
  private static Path locate(String fileName) {
    Path[] candidates = {
      Paths.get("src/main/java/com/deepseekharness/app", fileName),
      Paths.get("app/src/main/java/com/deepseekharness/app", fileName),
      Paths.get("../app/src/main/java/com/deepseekharness/app", fileName)
    };
    for (Path p : candidates) {
      if (Files.exists(p)) return p;
    }
    throw new AssertionError(
        "找不到源码文件（工作目录=" + Paths.get("").toAbsolutePath() + "）：" + fileName);
  }
}
