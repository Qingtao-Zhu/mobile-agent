package com.deepseekharness.app.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * {@link AssistantPanel} 纯逻辑单元测试（Phase 14 阶段 3 新增）。
 *
 * <p><b>为什么只测这三件事</b>：{@code AssistantPanel} 约 1400 行，其中绝大部分是
 * Android UI 代码（{@code WindowManager} / {@code View} / {@code Handler} / 语音录制），
 * 纯 JUnit 环境（本机只有 junit4 + org.json，<b>无 Robolectric</b>）下实例化不了。
 * 硬要去 mock 全部 Android 依赖，只会得到一堆"测了 mock 自己"的假测试。
 *
 * <p>所以这里只挑<b>真正有风险</b>的纯逻辑：
 * <ul>
 *   <li>{@code firstLineValue(dump, "窗口应用:")} —— <b>安全关键</b>：
 *       它从读屏 dump 里取出的包名，直接喂给 {@link AssistantGuard#isProtected}。
 *       取错一个字符，守卫看到的就是另一个应用的身份（见 {@link #D17_身份读不出来时面板是放行的()}）。</li>
 *   <li>{@code countEntries(dump)} —— 屏幕可读条目数，喂给面板摘要。</li>
 *   <li>{@code mentionsNotify(question)} —— 决定"要不要把通知正文拼进模型提示词"的门控。</li>
 * </ul>
 *
 * <p><b>本文件不断言</b>：Prompt 拼装（{@code askModel}）依赖 Config / API Key / Activity；
 * 模型输出展示（{@code showAnswer}）依赖 View；日志调用依赖 {@code android.util.Log}。
 * 这些的覆盖情况见 {@code TEST-COVERAGE-UPDATE.md}。
 */
public class AssistantPanelTest {

  // ---------------------------------------------------------------- 反射辅助

  private static String firstLineValue(String dump, String prefix) throws Exception {
    Method m = AssistantPanel.class.getDeclaredMethod("firstLineValue", String.class, String.class);
    m.setAccessible(true);
    return (String) m.invoke(null, dump, prefix);
  }

  private static int countEntries(String dump) throws Exception {
    Method m = AssistantPanel.class.getDeclaredMethod("countEntries", String.class);
    m.setAccessible(true);
    return (int) (Integer) m.invoke(null, dump);
  }

  private static boolean mentionsNotify(String q) throws Exception {
    Method m = AssistantPanel.class.getDeclaredMethod("mentionsNotify", String.class);
    m.setAccessible(true);
    return (Boolean) m.invoke(null, q);
  }

  /** 造一条 10000 行的读屏 dump（超长输入场景）。 */
  private static String hugeDump() {
    StringBuilder sb = new StringBuilder("窗口应用: com.example.chat\n");
    for (int i = 0; i < 10000; i++) sb.append("[节点").append(i).append("]\n");
    return sb.toString();
  }

  // ================================================================
  // 1. firstLineValue：安全关键的包名提取
  // ================================================================

  @Test
  public void 包名提取_正常dump() throws Exception {
    String dump = "窗口应用: com.tencent.mm\n[节点0]\n[节点1]\n";
    assertEquals("应取到首行冒号后的包名", "com.tencent.mm", firstLineValue(dump, "窗口应用:"));
  }

  @Test
  public void 包名提取_null与空串不抛() throws Exception {
    assertEquals("null dump 应返回空串", "", firstLineValue(null, "窗口应用:"));
    assertEquals("空 dump 应返回空串", "", firstLineValue("", "窗口应用:"));
  }

  @Test
  public void 包名提取_没有前缀行时返回空串() throws Exception {
    String dump = "[节点0]\n[节点1]\n";
    assertEquals("没有『窗口应用:』行时应返回空串", "", firstLineValue(dump, "窗口应用:"));
  }

  @Test
  public void 包名提取_前缀匹配但值为空() throws Exception {
    // dumpRoot 在 pkg 为 null 时写的是「窗口应用: 未知」，不是空；
    // 但前缀后直接换行的情况也要兜住
    assertEquals("前缀后无内容应返回空串", "", firstLineValue("窗口应用:\n[节点0]\n", "窗口应用:"));
  }

  @Test
  public void 包名提取_前后空白被trim() throws Exception {
    assertEquals(
        "包名前后的空白应被去掉", "com.example.app", firstLineValue("窗口应用:   com.example.app  \n", "窗口应用:"));
  }

  @Test
  public void 包名提取_超长dump不抛且能取到() throws Exception {
    assertEquals("10000 行 dump 仍应取到首行包名", "com.example.chat", firstLineValue(hugeDump(), "窗口应用:"));
  }

  @Test
  public void 包名提取_前缀出现在后续行也能取到() throws Exception {
    String dump = "第一行不是它\n窗口应用: com.late.app\n";
    assertEquals("应扫描所有行", "com.late.app", firstLineValue(dump, "窗口应用:"));
  }

  @Test
  public void 包名提取_特殊字符与注入形状() throws Exception {
    // 包名里出现奇怪字符时不得抛，也不得把后续行带出来
    String dump = "窗口应用: com.evil\u0000app\n[节点]\n";
    String got = firstLineValue(dump, "窗口应用:");
    assertTrue("含 NUL 的包名不应抛，且应取到首行内容", got.startsWith("com.evil"));
    assertFalse("不得把后续行带出来", got.contains("节点"));
  }

  // ================================================================
  // 2. countEntries：屏幕可读条目数
  // ================================================================

  @Test
  public void 条目计数_null与空串() throws Exception {
    assertEquals(0, countEntries(null));
    assertEquals(0, countEntries(""));
  }

  @Test
  public void 条目计数_只数以方括号开头的行() throws Exception {
    String dump = "窗口应用: com.x\n[a]\n[b]\n[c]\n不带括号的行\n";
    assertEquals("只有以 [ 开头的行算条目", 3, countEntries(dump));
  }

  @Test
  public void 条目计数_行中间有方括号不算() throws Exception {
    assertEquals("方括号不在行首不算", 0, countEntries("abc [def] ghi\n"));
  }

  @Test
  public void 条目计数_超长dump不抛() throws Exception {
    assertEquals("10000 个节点行", 10000, countEntries(hugeDump()));
  }

  // ================================================================
  // 3. mentionsNotify：通知进提示词的门控
  // ================================================================

  @Test
  public void 门控_null空串与纯空白不命中() throws Exception {
    assertFalse(mentionsNotify(null));
    assertFalse(mentionsNotify(""));
    assertFalse(mentionsNotify("   "));
    assertFalse(mentionsNotify("\t\n"));
  }

  @Test
  public void 门控_普通问题不命中() throws Exception {
    assertFalse("今天天气怎么样", mentionsNotify("今天天气怎么样"));
    assertFalse("帮我定个闹钟", mentionsNotify("帮我定个闹钟"));
    assertFalse("这句只有通知栏但没有问句语气", mentionsNotify("把通知栏开关打开"));
  }

  @Test
  public void 门控_问通知要命中() throws Exception {
    assertTrue("谁在找我", mentionsNotify("谁在找我"));
    assertTrue("通知栏有啥", mentionsNotify("通知栏有啥"));
    assertTrue("看看有啥消息", mentionsNotify("看看有啥消息"));
  }

  @Test
  public void 门控_特殊字符不抛() throws Exception {
    // emoji / 控制字符 / 全角符号 / SQL 注入形状 —— 都不能抛
    String[] weird = {
      "谁在找我😀😀", "\u0000\u0001通知\u0002有啥", "谁在找我' OR 1=1--",
      "通知栏有啥<script>alert(1)</script>", "谁在找我％％％", "通知" + "啥".repeat(5000),
    };
    for (String q : weird) {
      mentionsNotify(q); // 不抛即通过
    }
  }

  @Test
  public void 门控_超长输入不抛且不误命中() throws Exception {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 20000; i++) sb.append('啊');
    assertFalse("20000 字的普通文本不应命中", mentionsNotify(sb.toString()));
  }

  // ================================================================
  // 4. D-17：身份读不出来时，面板这条路是放行的（已知缺陷）
  // ================================================================

  /**
   * ★ D-17（Phase 14 新发现，<b>未修复</b>，仅登记）。
   *
   * <p><b>问题</b>：{@code DshaAccessibilityService.dumpRoot()} 在取不到包名时写的是
   * {@code "窗口应用: 未知"}（不是空串）。于是：
   * <ol>
   *   <li>{@code firstLineValue} 返回 {@code "未知"}</li>
   *   <li>{@code AssistantGuard.isProtected("未知")} —— "未知"既不在白名单也不在黑名单，
   *       返回 {@code false}（不视为受保护）</li>
   *   <li>{@code readScreen} 于是把整屏文字读进内存、发给模型</li>
   * </ol>
   *
   * <p>而同一份 dump 走到 {@link GuardDecision#decide} 时，身份读不出来是
   * <b>fail-closed</b> 的（{@code DENY_UNKNOWN_IDENTITY}，D-07 专门修过这个方向）。
   * <b>两条路径对"身份未知"的口径相反</b>：任务执行器停手，助手面板放行。
   *
   * <p><b>为什么本轮不修</b>：
   * <ul>
   *   <li>修它要改 {@code readScreen} 的主路径（Android 代码），本机无真机/ADB 验证；</li>
   *   <li>若某些正常场景下 {@code getPackageName()} 也会返回 null，"改成停手"会让面板
   *       直接不能读屏 —— 这是<b>功能回归风险</b>，需真机确认；</li>
   *   <li>停手 vs "读但不发给模型" 是产品决策。</li>
   * </ul>
   * 所以本条只把现状钉住：<b>让这个 fail-open 行为可见，且不能悄悄改变而不被注意到</b>。
   */
  @Test
  public void D17_身份读不出来时面板是放行的() throws Exception {
    String dumpWhenPkgNull = "窗口应用: 未知\n[节点0]\n";
    String app = firstLineValue(dumpWhenPkgNull, "窗口应用:");
    assertEquals("包名取不到时 dumpRoot 写的是字面量『未知』", "未知", app);
    assertFalse(
        "【已知缺陷 D-17】『未知』不在任何名单里 → isProtected 返回 false → 面板会读屏",
        AssistantGuard.isProtected(app));
    // 对照组：同样的"身份未知"在任务执行器那边是停手的
    assertEquals(
        "同样读不到身份，GuardDecision 是 fail-closed",
        GuardDecision.Verdict.DENY_UNKNOWN_IDENTITY, GuardDecision.decide(null, null));
  }

  /**
   * 已登记缺陷表（与 {@code LogPrivacyTest.KNOWN_LEAKS}、
   * {@code AssistantGuardTest.KNOWN_GUARD_DEFECTS} 同思路：待修清单，非免责清单）。
   *
   * <p><b>GD-01 于 Phase 14 修复后已从此类登记表中划掉</b>（见
   * {@code AssistantGuardTest.KNOWN_GUARD_DEFECTS}，现在是空表）。
   */
  private static final java.util.Map<String, String> KNOWN_PANEL_DEFECTS =
      java.util.Map.of(
          "D-17",
          "dumpRoot 在包名为 null 时写『窗口应用: 未知』，AssistantGuard 不认这个字面量"
              + "→ isProtected(\"未知\") 为 false → readScreen 放行并读屏；"
              + "而 GuardDecision 对身份未知是 fail-closed（DENY_UNKNOWN_IDENTITY）。"
              + "两条路径口径相反，需产品决策 + 真机验证后再修");

  /** 登记表必须与现实一致：缺陷修好后必须划掉，否则测试失败。 */
  @Test
  public void 面板缺陷登记必须与实际情况一致() throws Exception {
    String appWhenUnknown = firstLineValue("窗口应用: 未知\n", "窗口应用:");
    boolean stillFailOpen = !AssistantGuard.isProtected(appWhenUnknown);
    if (stillFailOpen) {
      assertTrue(
          "D-17（身份未知时面板放行）仍然存在，必须保留在 KNOWN_PANEL_DEFECTS 登记中",
          KNOWN_PANEL_DEFECTS.containsKey("D-17"));
    } else {
      assertFalse(
          "D-17 已修复，登记必须划掉 —— 留着只会让待修清单越积越脏",
          KNOWN_PANEL_DEFECTS.containsKey("D-17"));
    }
  }

  // ================================================================
  // 5. 与 GuardDecision 的实现一致性（影子逻辑风险）
  // ================================================================

  /**
   * {@code firstLineValue} 与 {@link GuardDecision#extractPackage} 是<b>两份</b>
   * "从 dump 首行取包名"的实现，口径有细微差别：
   * <ul>
   *   <li>{@code firstLineValue} 用 {@code startsWith("窗口应用:")}（无尾随空格）</li>
   *   <li>{@code extractPackage} 用 {@code indexOf("窗口应用: ")}（**有**尾随空格）</li>
   * </ul>
   *
   * <p>对 {@code dumpRoot} 实际产出的格式（{@code "窗口应用: <pkg>"}）两者结果一致，
   * 所以当前不影响正确性。但两份实现就有两份会漂移的风险 —— 与 Phase 13 的
   * D-15（{@code dumpMetrics} 内联副本）属同一类问题。
   *
   * <p>本条不断言"必须一样"，而是<b>把差异钉住</b>：一旦有人改了其中一份的格式假设，
   * 这里会失败并提醒他去统一。
   */
  @Test
  public void 两处包名提取实现对标准dump结果一致() throws Exception {
    String std = "窗口应用: com.tencent.mm\n[节点0]\n";
    assertEquals(
        "标准 dump 下两处实现应给出相同包名",
        firstLineValue(std, "窗口应用:"), GuardDecision.extractPackage(std));
  }

  @Test
  public void 两处包名提取实现_对无空格变体行为不同_已被记录() throws Exception {
    // dumpRoot 总是带空格，所以这个分支当前不会触发；
    // 但两份实现对"冒号后没有空格"的处理确实不同 —— 记录在案，避免以后误以为它们等价。
    String noSpace = "窗口应用:com.tencent.mm\n";
    assertEquals(
        "firstLineValue 用 startsWith，冒号后无空格也能取到",
        "com.tencent.mm", firstLineValue(noSpace, "窗口应用:"));
    assertEquals(
        "extractPackage 用 indexOf(\"窗口应用: \")，冒号后无空格则取不到 → null（fail-closed）",
        null, GuardDecision.extractPackage(noSpace));
  }

  // ================================================================
  // 6. 日志安全：本文件覆盖不到的静态兜底
  // ================================================================

  /**
   * 面板这条路上"用户输入/屏幕文字/模型回复不进日志"由 {@code LogPrivacyTest}
   * 静态扫描 + {@code LogFormatTest} 行为测试守着（Phase 13 已做）。
   * 这里只补一条：{@code AssistantPanel} 里不得出现"把整屏 dump 直接拼进日志"的形状。
   *
   * <p><b>为什么是静态断言</b>：{@code runText} / {@code askModel} 依赖 Activity 与 Config，
   * JVM 下跑不起来，无法做行为级验证。
   */
  @Test
  public void 面板不得把整屏dump或用户输入拼进日志() throws Exception {
    String src = readMainSource("AssistantPanel.java");
    assertFalse(
        "不得把 screenText 直接拼进日志",
        src.contains("Log.") && src.matches("(?s).*Log\\.[a-z]+\\([^)]*\\+\\s*screenText\\b.*"));
    assertFalse(
        "不得把 question 直接拼进日志（D-12 已修，防复发）",
        src.matches("(?s).*Log\\.[a-z]+\\([^)]*\"[^\"]*\"\\s*\\+\\s*question\\b.*"));
    assertFalse(
        "不得把 dump 正文切片拼进日志",
        src.contains("dump.substring"));
  }

  /** 读一个生产源码文件（去掉注释行）。 */
  private static String readMainSource(String fileName) throws Exception {
    java.nio.file.Path[] roots = {
      java.nio.file.Paths.get("src/main/java/com/deepseekharness/app"),
      java.nio.file.Paths.get("app/src/main/java/com/deepseekharness/app"),
      java.nio.file.Paths.get("../app/src/main/java/com/deepseekharness/app")
    };
    for (java.nio.file.Path root : roots) {
      if (!java.nio.file.Files.exists(root)) continue;
      List<java.nio.file.Path> hits = new ArrayList<>();
      java.nio.file.Files.walk(root)
          .filter(p -> p.getFileName().toString().equals(fileName))
          .forEach(hits::add);
      if (hits.isEmpty()) continue;
      StringBuilder out = new StringBuilder();
      for (String line :
          new String(java.nio.file.Files.readAllBytes(hits.get(0)),
              java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
        String t = line.trim();
        if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue;
        int c = line.indexOf("//");
        out.append(c >= 0 ? line.substring(0, c) : line).append('\n');
      }
      return out.toString();
    }
    throw new AssertionError("找不到生产源码：" + fileName);
  }
}
