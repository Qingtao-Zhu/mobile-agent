package com.deepseekharness.app.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * {@link GuardDecision} 的守卫判定测试（离线，纯 JVM）。
 *
 * <p><b>覆盖用户要求的四类场景</b>：
 * <ol>
 *   <li>普通允许场景
 *   <li>已配置的敏感包名
 *   <li>未知 / 无法确认身份的包名
 *   <li>任务执行过程中前台包名变化（逐步判定）
 * </ol>
 *
 * <p><b>D-07 回归</b>：旧实现对"身份读不出来"是**放行**的
 * （条件写成 {@code curPkg != null && !curPkg.isEmpty() && ...}，
 * 空包名让整个条件为假，于是既不拦也不停）。本文件里
 * {@link #未知身份必须停手_这是D07的回归} 就是守住这条的。
 *
 * <p><b>不接触真实敏感应用</b>：只喂包名字符串，不安装、不启动、不操作任何应用。
 */
public class GuardDecisionTest {

  // ================================================================
  // 1. 普通允许场景
  // ================================================================

  @Test
  public void 普通应用_允许() {
    assertEquals(
        GuardDecision.Verdict.ALLOW,
        GuardDecision.decide("com.example.chat", null));
  }

  @Test
  public void 普通应用_允许时不产生用户提示() {
    assertNull(GuardDecision.userMessage(GuardDecision.Verdict.ALLOW, "com.example.chat"));
    assertFalse(GuardDecision.mustStop(GuardDecision.Verdict.ALLOW));
  }

  // ================================================================
  // 2. 敏感包名
  // ================================================================

  @Test
  public void 支付类_拦截() {
    assertEquals(
        GuardDecision.Verdict.DENY_PROTECTED,
        GuardDecision.decide("com.eg.android.AlipayGphone", null));
  }

  @Test
  public void 银行类_拦截() {
    assertEquals(
        GuardDecision.Verdict.DENY_PROTECTED, GuardDecision.decide("com.icbc.mobile", null));
    assertEquals(
        GuardDecision.Verdict.DENY_PROTECTED, GuardDecision.decide("com.cmbchina.ccd", null));
  }

  @Test
  public void 钱包类_拦截() {
    assertEquals(
        GuardDecision.Verdict.DENY_PROTECTED,
        GuardDecision.decide("com.huawei.wallet", null));
  }

  @Test
  public void 密码管理器_拦截() {
    assertEquals(
        GuardDecision.Verdict.DENY_PROTECTED,
        GuardDecision.decide("com.keepass.droid", null));
  }

  @Test
  public void 券商类_拦截() {
    assertEquals(
        GuardDecision.Verdict.DENY_PROTECTED,
        GuardDecision.decide("com.example.securities.app", null));
  }

  @Test
  public void 大小写不敏感_仍拦截() {
    // 规则里做的是 toLowerCase 后子串匹配，所以大小写混写也要拦住
    assertEquals(
        GuardDecision.Verdict.DENY_PROTECTED,
        GuardDecision.decide("com.EG.Android.AlipayGphone", null));
  }

  @Test
  public void 受保护应用_文案含包名且要求停手() {
    String msg = GuardDecision.userMessage(GuardDecision.Verdict.DENY_PROTECTED, "com.foo.bank");
    assertNotNull(msg);
    assertTrue(msg.contains("com.foo.bank"));
    assertTrue(GuardDecision.mustStop(GuardDecision.Verdict.DENY_PROTECTED));
  }

  // ================================================================
  // 3. 未知 / 无法确认身份（D-07 核心）
  // ================================================================

  @Test
  public void 未知身份必须停手_这是D07的回归() {
    // 旧实现这三种输入都会"放行"（条件短路后既不是 protected 也不是 settings）
    assertEquals(
        "null 包名必须停手，不得放行",
        GuardDecision.Verdict.DENY_UNKNOWN_IDENTITY,
        GuardDecision.decide(null, null));
    assertEquals(
        "空包名必须停手，不得放行",
        GuardDecision.Verdict.DENY_UNKNOWN_IDENTITY,
        GuardDecision.decide("", null));
    assertEquals(
        "全空白包名必须停手，不得放行",
        GuardDecision.Verdict.DENY_UNKNOWN_IDENTITY,
        GuardDecision.decide("   ", null));
  }

  @Test
  public void 未知身份的文案与日志都不含屏幕内容() {
    String msg = GuardDecision.userMessage(GuardDecision.Verdict.DENY_UNKNOWN_IDENTITY, null);
    assertNotNull(msg);
    assertTrue(msg.contains("身份"));
    assertNotNull(GuardDecision.logReason(GuardDecision.Verdict.DENY_UNKNOWN_IDENTITY, null));
    assertTrue(GuardDecision.mustStop(GuardDecision.Verdict.DENY_UNKNOWN_IDENTITY));
  }

  @Test
  public void 从dump提取包名_正常格式() {
    String dump = "窗口应用: com.android.settings\n[1] \"设置\" 可点击 中心=(100,200)";
    assertEquals("com.android.settings", GuardDecision.extractPackage(dump));
  }

  @Test
  public void 从dump提取包名_没有该行则返回null() {
    assertEquals(null, GuardDecision.extractPackage("[1] \"什么都没有\" 中心=(1,1)"));
    assertEquals(null, GuardDecision.extractPackage("窗口应用: \n[1] x"));
    assertEquals(null, GuardDecision.extractPackage(""));
    assertEquals(null, GuardDecision.extractPackage(null));
  }

  @Test
  public void 提取失败时判定为未知身份_而不是放行() {
    String dumpWithoutPkg = "[1] \"只有节点没有窗口行\" 中心=(1,1)";
    String pkg = GuardDecision.extractPackage(dumpWithoutPkg);
    assertNull(pkg);
    assertEquals(
        "提取失败 → 未知身份 → 停手（这是 D-07 的完整链路）",
        GuardDecision.Verdict.DENY_UNKNOWN_IDENTITY,
        GuardDecision.decide(pkg, null));
  }

  @Test
  public void 从dump提取包名_单行dump也能处理() {
    assertEquals("com.a.b", GuardDecision.extractPackage("窗口应用: com.a.b"));
  }

  // ================================================================
  // 4. 系统设置
  // ================================================================

  @Test
  public void 系统设置_停手() {
    assertEquals(
        GuardDecision.Verdict.DENY_SETTINGS,
        GuardDecision.decide("com.android.settings", null));
  }

  @Test
  public void 系统设置的子页面_也停手() {
    assertEquals(
        GuardDecision.Verdict.DENY_SETTINGS,
        GuardDecision.decide("com.android.settings.Settings$BluetoothSettingsActivity", null));
  }

  @Test
  public void 名字相近但不是系统设置的_不误伤() {
    // 前缀匹配要求完全以 com.android.settings 开头
    assertEquals(
        GuardDecision.Verdict.ALLOW,
        GuardDecision.decide("com.android.settingsx", null));
    assertEquals(
        GuardDecision.Verdict.ALLOW,
        GuardDecision.decide("com.thirdparty.settings", null));
  }

  // ================================================================
  // 5. 前台包名变化（逐步判定的核心行为）
  // ================================================================

  @Test
  public void 任务执行中包名变化_每一步都独立判定() {
    // 模拟 runLoop 每步都调一次 decide：同一次任务里，落点从普通应用换到支付应用
    assertEquals(GuardDecision.Verdict.ALLOW, GuardDecision.decide("com.example.chat", null));
    assertEquals(GuardDecision.Verdict.ALLOW, GuardDecision.decide("com.example.chat", null));
    // 中途切到了支付应用 → 下一步必须拦下
    assertEquals(
        "中途切到支付应用必须被拦住",
        GuardDecision.Verdict.DENY_PROTECTED,
        GuardDecision.decide("com.eg.android.AlipayGphone", null));
    // 又切到身份读不出的界面 → 也要拦
    assertEquals(
        GuardDecision.Verdict.DENY_UNKNOWN_IDENTITY, GuardDecision.decide(null, null));
  }

  // ================================================================
  // 6. 白名单（用户拍板：微信要能读能操作）
  // ================================================================

  @Test
  public void 微信在白名单里_放行() {
    // com.tencent.mm 同时在 ALLOWED 和 MONEY_OR_SECRET 里 —— 白名单一票放行
    assertEquals(GuardDecision.Verdict.ALLOW, GuardDecision.decide("com.tencent.mm", null));
  }

  @Test
  public void 外部白名单可追加放行() {
    // 用户通过 AssistantConfig.allowedPackages 追加的包也应放行
    assertEquals(
        GuardDecision.Verdict.ALLOW,
        GuardDecision.decide("com.some.bank.app", Arrays.asList("com.some.bank.app")));
    // 没有白名单时同一个包仍被拦
    assertEquals(
        GuardDecision.Verdict.DENY_PROTECTED,
        GuardDecision.decide("com.some.bank.app", null));
  }

  @Test
  public void 外部白名单为空列表时不影响默认拦截() {
    assertEquals(
        GuardDecision.Verdict.DENY_PROTECTED,
        GuardDecision.decide("com.eg.android.AlipayGphone", Collections.<String>emptyList()));
  }

  // ================================================================
  // 7. 生产代码路径覆盖
  // ================================================================

  @Test
  public void 生产代码确实引用了GuardDecision() throws Exception {
    java.nio.file.Path src = locate("AssistantTaskRunner.java");
    String code = new String(java.nio.file.Files.readAllBytes(src), "UTF-8");
    assertTrue(
        "runLoop 必须真的调用 GuardDecision.decide（否则测试守护的是影子逻辑）",
        code.contains("GuardDecision.decide("));
    assertTrue("必须用 GuardDecision.extractPackage", code.contains("GuardDecision.extractPackage("));
    assertTrue("必须用 GuardDecision.mustStop", code.contains("GuardDecision.mustStop("));
  }

  private static java.nio.file.Path locate(String fileName) {
    java.nio.file.Path[] candidates = {
      java.nio.file.Paths.get("src/main/java/com/deepseekharness/app/assistant", fileName),
      java.nio.file.Paths.get("app/src/main/java/com/deepseekharness/app/assistant", fileName),
      java.nio.file.Paths.get("../app/src/main/java/com/deepseekharness/app/assistant", fileName)
    };
    for (java.nio.file.Path p : candidates) {
      if (java.nio.file.Files.exists(p)) return p;
    }
    throw new AssertionError("找不到源码文件：" + fileName);
  }
}
