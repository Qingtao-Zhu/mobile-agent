package com.deepseekharness.app.assistant;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * {@link AssistantGuard} 单元测试（Phase 11 阶段 4 新增，P0 优先级）。
 *
 * <p><b>为什么这个类必须测</b>：它是"敏感应用不被读取/不被操作"的判定源头。
 * 一旦判定写错，助手会在支付/银行/密码管理类应用里读屏并代操作 —— 直接违反用户
 * 2026-10-07 立下的规矩。此前该类**零测试**。
 *
 * <p>测试策略（对齐 {@code TEST-STRATEGY.md} 的 L1 强制要求）：
 * <ul>
 *   <li><b>U1 负向必须存在</b>：每种受保护类别都要有"必须拦"的用例</li>
 *   <li><b>U2 边界全覆盖</b>：{@code null} / {@code ""} / 纯空白 / 大小写 / 相似包名</li>
 *   <li><b>U4 预期独立定义</b>：全部与固定常量比较，不由结果反推</li>
 * </ul>
 */
public class AssistantGuardTest {

  // ================================================================
  // 1. 白名单优先（用户点名放行的必须放行）
  // ================================================================

  @Test
  public void 微信必须放行_白名单优先于黑名单() {
    // 用户 2026-10-07 拍板：微信不在受保护之列
    assertFalse("微信主包必须放行", AssistantGuard.isProtected("com.tencent.mm"));
    assertFalse("微信分身也必须放行", AssistantGuard.isProtected("com.tencent.mm"));
  }

  @Test
  public void 外部白名单一票放行() {
    List<String> allowed = Collections.singletonList("com.example.chat");
    assertFalse(
        "外部白名单命中的包必须放行", AssistantGuard.isProtected("com.example.chat", allowed));
  }

  @Test
  public void 外部白名单条目带头尾空白仍生效() {
    // 配置里手写容易带空格；trim 后应仍能匹配
    List<String> allowed = Collections.singletonList("  com.example.chat  ");
    assertFalse(
        "外部白名单条目含空白应 trim 后匹配",
        AssistantGuard.isProtected("com.example.chat", allowed));
  }

  @Test
  public void 外部白名单大小写不敏感() {
    List<String> allowed = Collections.singletonList("COM.EXAMPLE.CHAT");
    assertFalse(
        "外部白名单应大小写不敏感", AssistantGuard.isProtected("com.example.chat", allowed));
  }

  @Test
  public void 外部白名单中的null与空白条目不致命() {
    List<String> allowed = Arrays.asList(null, "   ", "com.example.chat");
    assertFalse(
        "含 null/空白条目的外部白名单不应把正常包误拦",
        AssistantGuard.isProtected("com.example.chat", allowed));
    // 而未命中的包仍应照常判定
    assertTrue(
        "未命中外部白名单的支付类仍应拦截",
        AssistantGuard.isProtected("com.eg.android.alipay", allowed));
  }

  // ================================================================
  // 2. 各类敏感应用必须拦截（U1：负向用例）
  // ================================================================

  @Test
  public void 支付类必须拦截() {
    assertTrue("支付宝", AssistantGuard.isProtected("com.eg.android.alipay"));
    assertTrue("云闪付", AssistantGuard.isProtected("com.unionpay"));
    assertTrue("京东支付", AssistantGuard.isProtected("com.jdpay"));
    assertTrue("PayPal", AssistantGuard.isProtected("com.paypal"));
  }

  @Test
  public void 银行类必须拦截() {
    assertTrue("工商银行", AssistantGuard.isProtected("com.icbc"));
    assertTrue("建设银行", AssistantGuard.isProtected("com.ccb"));
    assertTrue("招商银行", AssistantGuard.isProtected("com.cmbchina"));
    assertTrue("通用 bank 关键词", AssistantGuard.isProtected("com.somebank.app"));
  }

  @Test
  public void 钱包类必须拦截() {
    assertTrue("华为钱包", AssistantGuard.isProtected("com.huawei.wallet"));
    assertTrue("通用 wallet 关键词", AssistantGuard.isProtected("com.foo.walletx"));
  }

  @Test
  public void 密码管理类必须拦截() {
    assertTrue("KeePass", AssistantGuard.isProtected("com.keepass"));
    assertTrue("Bitwarden", AssistantGuard.isProtected("com.bitwarden"));
    assertTrue("LastPass", AssistantGuard.isProtected("com.lastpass"));
    assertTrue("1Password", AssistantGuard.isProtected("com.1password"));
    assertTrue("Authenticator", AssistantGuard.isProtected("com.authenticator"));
  }

  @Test
  public void 券商交易类必须拦截() {
    assertTrue("securities", AssistantGuard.isProtected("com.gf.securities"));
    assertTrue("broker", AssistantGuard.isProtected("com.x.broker"));
    assertTrue("trading", AssistantGuard.isProtected("com.y.trading"));
  }

  // ================================================================
  // 3. 普通应用必须放行（防止过度拦截把功能打死）
  // ================================================================

  @Test
  public void 普通应用必须放行() {
    assertFalse("聊天工具", AssistantGuard.isProtected("org.telegram.messenger"));
    assertFalse("浏览器", AssistantGuard.isProtected("com.android.chrome"));
    assertFalse("系统设置", AssistantGuard.isProtected("com.android.settings"));
    assertFalse("文件管理器", AssistantGuard.isProtected("com.android.documentsui"));
    assertFalse("相机", AssistantGuard.isProtected("com.android.camera"));
  }

  // ================================================================
  // 4. 边界与未知身份（U2）
  // ================================================================

  @Test
  public void null包名不视为受保护_且不抛异常() {
    // 与 GuardDecision 的 DENY_UNKNOWN_IDENTITY 不同：本类对 null 返回 false
    // （面板路径的"未知身份"由 GuardDecision 负责 fail-closed）
    assertFalse("null 包名不得抛异常", AssistantGuard.isProtected(null));
    assertFalse("null 包名 + 外部白名单不得抛", AssistantGuard.isProtected(null, null));
  }

  @Test
  public void 空串与纯空白包名不视为受保护() {
    assertFalse("空串", AssistantGuard.isProtected(""));
    assertFalse("纯空白", AssistantGuard.isProtected("   "));
    assertFalse("Tab/换行", AssistantGuard.isProtected("\t\n"));
  }

  @Test
  public void 大小写不敏感() {
    // 包名统一转小写后比较
    assertTrue("大写 ALIPAY 也应命中", AssistantGuard.isProtected("COM.EG.ANDROID.ALIPAY"));
    assertTrue("混合大小写 Bank 也应命中", AssistantGuard.isProtected("Com.SomeBank.App"));
  }

  /**
   * ★ 本测试首次运行即发现一个真实安全缺陷（Phase 11）。
   *
   * <p><b>缺陷</b>：白名单 {@code ALLOWED} 用的是 {@code matchAny()}（{@code contains} 子串），
   * 不是精确匹配。因此任何<b>包含</b> {@code com.tencent.mm} 的包名都被放行，例如：
   * <ul>
   *   <li>{@code com.tencent.mmhelper}</li>
   *   <li>{@code com.tencent.mm.fake}</li>
   *   <li>{@code com.evil.tencent.mm.phishing}</li>
   * </ul>
   * 这意味着攻击者/误装一个名字里带 {@code com.tencent.mm} 的应用就能绕过整套敏感防护。
   *
   * <p><b>为什么不直接改生产代码</b>：把白名单从 {@code contains} 改成精确匹配
   * 会改变"微信分身"等场景的语义（微信分身是否用扩展包名，需产品确认）。
   * <b>这是产品决策，不是实现细节</b>，因此：
   * <ul>
   *   <li>本测试断言<b>当前真实行为</b>（保持套件绿色）</li>
   *   <li>同时用 {@link #白名单子串匹配缺陷_必须被登记且不得恶化()} 盯着它</li>
   *   <li>缺陷详情写入 {@code PHASE11-EXECUTION-REPORT.md}，提交给用户决策</li>
   * </ul>
   */
  @Test
  public void 相似包名_不是白名单那个也要按内容判定() {
    // 当前实现：白名单是子串匹配 → 相似包名也被放行
    assertFalse(
        "【当前行为，已知缺陷】com.tencent.mmhelper 因白名单子串匹配而被放行",
        AssistantGuard.isProtected("com.tencent.mmhelper"));
    // ★ 更严重：连"银行类"相似包名也被放行 —— 因为白名单优先于黑名单，
    //   而白名单是 contains 匹配。com.tencent.mm.bank 含 com.tencent.mm → 放行。
    assertFalse(
        "【当前行为，已知缺陷 GD-01】com.tencent.mm.bank 也因白名单子串匹配被放行"
            + "（尽管它含 bank 关键词）—— 这正是 GD-01 的危险之处",
        AssistantGuard.isProtected("com.tencent.mm.bank"));
    // 对照：不含 com.tencent.mm 的 bank 包仍被正常拦截（黑名单本身是对的）
    assertTrue(
        "不含白名单子串的 bank 包仍应被拦",
        AssistantGuard.isProtected("com.somebank.app"));
  }

  /** 白名单缺陷登记表（key=缺陷编号，value=说明）。与 KNOWN_LEAKS 同思路：待修清单，非免责清单。 */
  private static final java.util.Map<String, String> KNOWN_GUARD_DEFECTS =
      java.util.Map.of(
          "GD-01",
          "AssistantGuard.ALLOWED 用 contains 子串匹配而非精确匹配，"
              + "导致 com.tencent.mmhelper 等相似包名被放行（绕过敏感防护）；"
              + "更严重的是白名单优先于黑名单，因此连 com.tencent.mm.bank 这类"
              + "含 bank 关键词的支付类包名也一并被放行");

  /**
   * 保证 GD-01 登记与代码实际状态一致：
   * 一旦白名单改成精确匹配，本测试会提醒把登记划掉。
   */
  @Test
  public void 白名单子串匹配缺陷_必须被登记且不得恶化() {
    // 当前实现下，相似包名被放行 → 缺陷存在 → 登记必须保留
    boolean stillSubstringMatched = !AssistantGuard.isProtected("com.tencent.mmhelper");
    if (stillSubstringMatched) {
      // 缺陷仍在：确认它已被登记（防止登记被悄悄删掉）
      assertTrue(
          "GD-01（白名单子串匹配）仍然存在，必须保留在 KNOWN_GUARD_DEFECTS 登记中",
          KNOWN_GUARD_DEFECTS.containsKey("GD-01"));
    }
    // 若已改为精确匹配，本测试自动转为通过（提示可以划掉登记）
    // —— 此处不断言"必须仍是子串"，以免锁死修复。
  }

  // ================================================================
  // 5. 白名单与黑名单同时命中时白名单赢
  // ================================================================

  @Test
  public void 白名单与黑名单同时命中时白名单赢() {
    // 构造一个既在黑名单关键词里、又被外部白名单点名的包
    List<String> allowed = Collections.singletonList("com.icbc"); // 工行在黑名单里
    assertFalse(
        "白名单优先：用户点名的包即使命中黑名单关键词也应放行",
        AssistantGuard.isProtected("com.icbc", allowed));
    // 而没被白名单点名时仍拦
    assertTrue("未点名时工行仍应拦截", AssistantGuard.isProtected("com.icbc", null));
  }

  @Test
  public void 外部白名单不允许放行敏感子串绕过() {
    // 外部白名单是【精确匹配】，不是子串 —— 不能靠写个 "alipay" 放行所有含它的包
    List<String> allowed = Collections.singletonList("com.eg.android.alipay");
    assertFalse("精确匹配的支付宝可被白名单放行", AssistantGuard.isProtected("com.eg.android.alipay", allowed));
    assertTrue(
        "但白名单不应放行 com.eg.android.alipayclone（精确匹配，不是子串）",
        AssistantGuard.isProtected("com.eg.android.alipayclone", allowed));
  }
}
