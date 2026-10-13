package com.deepseekharness.app.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

/**
 * {@link AssistantTaskRunner} 纯逻辑单元测试（Phase 14 阶段 4 新增）。
 *
 * <p><b>覆盖范围与边界</b>：本类约 1065 行，其中手势派发、读屏、授权上下文、
 * 线程管理都是 Android 强相关，纯 JUnit（本机无 Robolectric）下无法实例化。
 * 这里只测<b>真正有风险</b>的纯函数：
 * <ul>
 *   <li>{@code extractJson(resp)} —— 模型响应解析。解析错一步，执行器就把
 *       模型的废话当成动作、或者把动作当废话，直接决定"会不会点错东西"。</li>
 *   <li>{@code describe(act, a)} —— 既是给用户看的动作描述，也是<b>原地打转检测的键</b>。
 *       描述不含关键字段时，两个不同动作会被判成"同一个"→ 误停手。</li>
 *   <li>{@code tidy(s)} / {@code pick(s)} —— 用户口语任务目标的解析。</li>
 *   <li>{@code isPlaceholder(t)} / {@code inputText(dump)} / {@code screenChanged(a,b)}
 *       —— "消息到底发出去没有"的地面真相判据。判错会把没发成的消息报成成功。</li>
 * </ul>
 */
public class AssistantTaskRunnerTest {

  // ---------------------------------------------------------------- 反射辅助

  private static Object invoke(String name, Class<?>[] sig, Object... args) throws Exception {
    Method m = AssistantTaskRunner.class.getDeclaredMethod(name, sig);
    m.setAccessible(true);
    return m.invoke(null, args);
  }

  private static JSONObject extractJson(String resp) throws Exception {
    return (JSONObject)
        invoke("extractJson", new Class<?>[] {String.class}, resp);
  }

  private static String describe(String act, JSONObject a) throws Exception {
    return (String) invoke("describe", new Class<?>[] {String.class, JSONObject.class}, act, a);
  }

  private static String tidy(String s) throws Exception {
    return (String) invoke("tidy", new Class<?>[] {String.class}, s);
  }

  private static String pick(String s) throws Exception {
    return (String) invoke("pick", new Class<?>[] {String.class}, s);
  }

  private static boolean isPlaceholder(String t) throws Exception {
    return (Boolean) invoke("isPlaceholder", new Class<?>[] {String.class}, t);
  }

  private static String inputText(String dump) throws Exception {
    return (String) invoke("inputText", new Class<?>[] {String.class}, dump);
  }

  private static boolean screenChanged(String before, String after) throws Exception {
    return (Boolean)
        invoke("screenChanged", new Class<?>[] {String.class, String.class}, before, after);
  }

  // ================================================================
  // 1. extractJson：模型响应解析（正常/空/异常/超长/非法）
  // ================================================================

  @Test
  public void 模型响应_正常JSON() throws Exception {
    JSONObject a = extractJson("{\"act\":\"tap\",\"text\":\"发送\"}");
    assertNotNull("应解析出动作", a);
    assertEquals("tap", a.optString("act"));
    assertEquals("发送", a.optString("text"));
  }

  @Test
  public void 模型响应_null与空串返回null() throws Exception {
    assertNull("null 响应应返回 null", extractJson(null));
    assertNull("空串应返回 null", extractJson(""));
    assertNull("纯空白应返回 null", extractJson("   \n\t "));
  }

  @Test
  public void 模型响应_没有JSON返回null() throws Exception {
    assertNull("纯文本应返回 null", extractJson("我现在就帮你点发送按钮"));
    assertNull("只有左括号应返回 null", extractJson("{"));
    assertNull("只有右括号应返回 null", extractJson("}"));
    assertNull("左右括号顺序颠倒应返回 null", extractJson("} {"));
  }

  @Test
  public void 模型响应_容忍markdown围栏与前后废话() throws Exception {
    String fenced = "好的，我来操作：\n```json\n{\"act\":\"input\",\"text\":\"你好\"}\n```\n以上。";
    JSONObject a = extractJson(fenced);
    assertNotNull("应能从围栏里抠出 JSON", a);
    assertEquals("input", a.optString("act"));
  }

  @Test
  public void 模型响应_多个JSON只取第一个() throws Exception {
    // ★ 实测行为（Phase 14 首次写测试时预期写错，已核实后改正）：
    //   extractJson 用 indexOf('{') 到 lastIndexOf('}') 取整段，交给 new JSONObject(...)。
    //   org.json 对"一个合法对象 + 尾部垃圾"是**宽容**的 —— 它解析第一个对象、
    //   忽略后面的内容。所以两个 JSON 相连时拿到的是第一个。
    //   这个行为是可接受的（取第一个动作），但它不是"返回 null"。
    String two = "{\"act\":\"wait\"} 然后 {\"act\":\"done\"}";
    JSONObject a = extractJson(two);
    assertNotNull("org.json 宽容解析：应拿到第一个对象", a);
    assertEquals("wait", a.optString("act"));
  }

  @Test
  public void 模型响应_非法JSON不抛() throws Exception {
    assertNull("语法错误应返回 null 而不是抛", extractJson("{\"act\":}"));
    assertNull("未闭合应返回 null", extractJson("{\"act\":\"tap\""));
    // ★ 实测：org.json 连单引号都接受（宽容解析），所以这条不是 null。
    //   记录在案 —— 不要以为"格式不对就一定解析失败"。
    assertNotNull("org.json 宽容解析：单引号 JSON 也能解析", extractJson("{'act':'tap'}"));
  }

  @Test
  public void 模型响应_嵌套括号正确处理() throws Exception {
    JSONObject a = extractJson("{\"act\":\"tap\",\"args\":{\"x\":{\"y\":1}}}");
    assertNotNull("嵌套对象应能解析", a);
    assertEquals("tap", a.optString("act"));
  }

  @Test
  public void 模型响应_超长响应不抛且有界() throws Exception {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 200000; i++) sb.append("废话");
    sb.append("{\"act\":\"done\",\"note\":\"ok\"}");
    JSONObject a = extractJson(sb.toString());
    assertNotNull("超长废话后面的 JSON 仍应能解析出来", a);
    assertEquals("done", a.optString("act"));
  }

  @Test
  public void 模型响应_特殊字符与注入形状() throws Exception {
    // 引号/反斜杠/换行/NUL 混在 JSON 字符串里，都不能抛
    String[] weird = {
      "{\"act\":\"input\",\"text\":\"他说\\\"你好\\\"\"}",
      "{\"act\":\"input\",\"text\":\"a\\nb\\tc\"}",
      "{\"act\":\"input\",\"text\":\"😀😀\"}",
      "{\"act\":\"tap\",\"text\":\"' OR 1=1--\"}",
      "{\"act\":\"input\",\"text\":\"<script>alert(1)</script>\"}",
    };
    for (String w : weird) {
      extractJson(w); // 不抛即通过
    }
  }

  // ================================================================
  // 2. describe：动作描述 = 用户可见文案 + 原地打转检测的键
  // ================================================================

  @Test
  public void 动作描述_各动作都有可读文案() throws Exception {
    assertTrue(describe("tap", obj("{\"text\":\"发送\"}")).contains("发送"));
    assertTrue(describe("tap_xy", obj("{\"x\":10,\"y\":20}")).contains("10"));
    assertTrue(describe("input", obj("{\"text\":\"你好\"}")).contains("你好"));
    assertTrue(describe("key", obj("{\"name\":\"back\"}")).contains("back"));
    assertTrue(describe("scroll", obj("{\"dir\":\"up\"}")).contains("上"));
    assertTrue(describe("wait", obj("{}")).length() > 0);
    assertEquals("完成", describe("done", obj("{}")));
    assertEquals("放弃", describe("fail", obj("{}")));
  }

  @Test
  public void 动作描述_未知动作不得抛() throws Exception {
    String d = describe("teleport", obj("{}"));
    assertNotNull("未知动作应给出占位文案而不是抛", d);
    assertTrue("应带出动作名便于排障", d.contains("teleport"));
  }

  @Test
  public void 动作描述_字段缺失用默认值不抛() throws Exception {
    assertNotNull("tap 缺 text 应退回 ?", describe("tap", obj("{}")));
    assertNotNull("tap_xy 缺坐标应退回 -1", describe("tap_xy", obj("{}")));
    assertNotNull("input 缺 text 应退回 ?", describe("input", obj("{}")));
    assertNotNull("key 缺 name 应退回 back", describe("key", obj("{}")));
    assertNotNull("scroll 缺 dir 应退回 down", describe("scroll", obj("{}")));
  }

  /**
   * ★ 这条测的是<b>功能正确性</b>，不是隐私：
   * {@code describe} 的返回值同时是"原地打转检测"的键（连续 3 步相同就停手）。
   * 如果 {@code input} 的描述不含 text，两个不同的输入内容会被判成同一个动作
   * → 任务被误停。所以"描述里带 text"是**必要的**，不是可以随手删的。
   */
  @Test
  public void 动作描述_不同输入内容必须产生不同描述() throws Exception {
    String a = describe("input", obj("{\"text\":\"你好\"}"));
    String b = describe("input", obj("{\"text\":\"你好呀\"}"));
    assertFalse(
        "不同输入内容必须产生不同描述，否则原地打转检测会误停手", a.equals(b));
  }

  @Test
  public void 动作描述_相同输入必须产生相同描述() throws Exception {
    assertEquals(
        "相同动作必须稳定复现（否则打转检测永远不触发）",
        describe("tap", obj("{\"text\":\"发送\"}")), describe("tap", obj("{\"text\":\"发送\"}")));
  }

  // ================================================================
  // 3. tidy / pick：用户口语任务目标解析
  // ================================================================

  @Test
  public void 应用名清理_null与空串() throws Exception {
    assertEquals("", tidy(null));
    assertEquals("", tidy(""));
    assertEquals("", tidy("   "));
  }

  @Test
  public void 应用名清理_去掉帮我打开与语气词() throws Exception {
    assertEquals("微信", tidy("帮我打开微信"));
    assertEquals("微信", tidy("打开微信啊"));
    assertEquals("Telegram", tidy("帮我打开Telegram吧"));
  }

  @Test
  public void 应用名清理_超长与特殊字符不抛() throws Exception {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 5000; i++) sb.append("帮我打开");
    tidy(sb.toString()); // 不抛即通过
    tidy("帮我打开\u0000\u0001微信");
    tidy("帮我打开<script>alert(1)</script>");
  }

  @Test
  public void 收件人提取_优先取对称引号内() throws Exception {
    // ★ 以下期望值均用 Python 精确复刻 pick() 逻辑预先验证过，不是拍脑袋写的。
    assertEquals("小飞鱼", pick("机器人'小飞鱼'"));
    assertEquals("小明", pick("给\"小明\"发消息"));
  }

  /**
   * ★ D-20（Phase 14 新发现，<b>未修复</b>，仅登记）。
   *
   * <p><b>问题</b>：{@code pick} 的引号表 {@code QUOTES} 同时含对称引号
   * （{@code '} {@code "}）和非对称引号（{@code 「」} {@code ‘’} {@code “”}），
   * 但找闭引号用的是 {@code t.indexOf(c, i + 1)} —— 找的是<b>同一个字符</b>。
   * 于是非对称引号永远找不到闭合，提取静默失败，退回"剥前缀"分支。
   *
   * <p><b>实测影响</b>（期望值是复刻逻辑验证过的，不是猜的）：
   * <ul>
   *   <li>{@code pick("机器人「小飞鱼」")} → {@code 「小飞鱼」}（引号留在结果里）</li>
   *   <li>{@code pick("给“小明”发消息")} → {@code 给“小明”发消息}（<b>原样返回</b>，
   *       因为没有任何前缀可剥）</li>
   * </ul>
   * 执行器随后按这个带引号/带废话的串去屏幕上找文字 → 找不到 → 任务失败并明确告知。
   * **是功能退化，不是安全问题**，且失败是 graceful 的。
   *
   * <p><b>为什么本轮不修</b>：属于用户输入解析的功能改进，不在
   * "安全判定收敛 + 高风险类测试补强"范围内；且改它要动 {@code parse} 的主路径，
   * 需要真机验证各种口语说法。已登记待决。
   */
  @Test
  public void D20_非对称引号提取失败_已知缺陷() throws Exception {
    assertEquals(
        "【已知缺陷 D-20】「小飞鱼」提取失败，引号被留在结果里",
        "「小飞鱼」", pick("机器人「小飞鱼」"));
    assertEquals(
        "【已知缺陷 D-20】“小明” 提取失败，且没有前缀可剥 → 原样返回",
        "给“小明”发消息", pick("给“小明”发消息"));
    assertEquals(
        "【已知缺陷 D-20】‘小明’ 同样提取失败",
        "给‘小明’发消息", pick("给‘小明’发消息"));
  }

  /**
   * 已登记缺陷表（与 {@code LogPrivacyTest.KNOWN_LEAKS}、
   * {@code AssistantGuardTest.KNOWN_GUARD_DEFECTS} 同思路：待修清单，非免责清单）。
   */
  private static final java.util.Map<String, String> KNOWN_RUNNER_DEFECTS =
      java.util.Map.of(
          "D-20",
          "pick() 的引号表含非对称引号（「」‘’“”），但找闭引号用的是 indexOf(同一个字符)，"
              + "导致这些引号永远提取失败；实测 pick(\"给“小明”发消息\") 原样返回。"
              + "功能退化（graceful 失败），非安全问题；需真机验证口语说法后再修");

  /** 登记表必须与现实一致：修好后必须划掉，否则测试失败。 */
  @Test
  public void 执行器缺陷登记必须与实际情况一致() throws Exception {
    boolean stillBroken = "给“小明”发消息".equals(pick("给“小明”发消息"));
    if (stillBroken) {
      assertTrue(
          "D-20（非对称引号提取失败）仍然存在，必须保留在 KNOWN_RUNNER_DEFECTS 登记中",
          KNOWN_RUNNER_DEFECTS.containsKey("D-20"));
    } else {
      assertFalse(
          "D-20 已修复，登记必须划掉 —— 留着只会让待修清单越积越脏",
          KNOWN_RUNNER_DEFECTS.containsKey("D-20"));
    }
  }

  @Test
  public void 收件人提取_无引号时剥前缀() throws Exception {
    assertEquals("小飞鱼", pick("我的机器人小飞鱼"));
    assertEquals("小飞鱼", pick("自己的小飞鱼"));
  }

  @Test
  public void 收件人提取_null与空串() throws Exception {
    assertEquals("", pick(null));
    assertEquals("", pick(""));
  }

  @Test
  public void 收件人提取_只有半个引号不崩() throws Exception {
    // 只有开引号没有闭引号：循环找不到同字符的 close，落到前缀剥离分支。
    // 实测返回值是 "'未闭合"（剥掉了『我的』，开引号留下）—— 不越界、不抛。
    String got = pick("我的'未闭合");
    assertNotNull("不得抛异常", got);
    assertFalse("应剥掉『我的』前缀", got.contains("我的"));
  }

  // ================================================================
  // 4. 发送确认判据：isPlaceholder / inputText / screenChanged
  // ================================================================

  @Test
  public void 占位符判定_常见占位文案算占位() throws Exception {
    assertTrue("非话题消息", isPlaceholder("非话题消息"));
    assertTrue("说点什么", isPlaceholder("说点什么"));
    assertTrue("Send a message", isPlaceholder("Send a message"));
    assertTrue("发消息", isPlaceholder("点这里发消息"));
  }

  @Test
  public void 占位符判定_真实内容不算占位() throws Exception {
    assertFalse("用户打的字不是占位", isPlaceholder("你好呀"));
    assertFalse("空串不是占位", isPlaceholder(""));
    assertFalse("null 不是占位", isPlaceholder(null));
  }

  @Test
  public void 输入框文字_null与无可输入行返回null() throws Exception {
    assertNull(inputText(null));
    assertNull(inputText("窗口应用: com.x\n[节点0]\n"));
  }

  /**
   * dump 行的真实格式（{@code DshaAccessibilityService.dumpNode}）是：
   * <pre>
   *   [序号] "label" 可点击 可输入 已选中 不可用 中心=(x,y) 区域=(l,t,r,b) …
   * </pre>
   * 一行里**只有一个**引号串，就是 label（对可输入节点来说就是框里的当前文字，
   * 空框时是 contentDescription 即占位提示）。所以 {@code inputText} 取第一个引号串
   * 就是取框里的文字 —— 这是对的。
   *
   * <p>（Phase 14 第一版测试自己编了个 {@code 文字="..."} 的假格式，那是错的：
   * 生产 dump 里没有这个字段。已按真实格式改正。）
   */
  @Test
  public void 输入框文字_取可输入行的label() throws Exception {
    String dump = "窗口应用: com.tencent.mm\n[1] \"发送\" 可点击 中心=(900,1900)\n"
        + "[2] \"你好呀\" 可输入 中心=(500,1900)\n";
    assertEquals("应取到可输入节点自己的 label（= 框里的文字）", "你好呀", inputText(dump));
  }

  @Test
  public void 输入框文字_空框时取到的是占位提示() throws Exception {
    // 空输入框：getText() 为空 → label 退到 contentDescription（占位文字）
    String dump = "窗口应用: com.tencent.mm\n[1] \"非话题消息\" 可输入 中心=(500,1900)\n";
    assertEquals("非话题消息", inputText(dump));
    assertTrue("且它会被判成占位（于是走气泡计数兜底）", isPlaceholder(inputText(dump)));
  }

  @Test
  public void 输入框文字_可输入行无引号时返回null() throws Exception {
    assertNull(inputText("窗口应用: com.x\n[0] 可输入\n"));
  }

  @Test
  public void 输入框文字_超长dump不抛() throws Exception {
    StringBuilder sb = new StringBuilder("窗口应用: com.x\n");
    for (int i = 0; i < 5000; i++) sb.append("[节点").append(i).append("] \"x").append(i).append("\"\n");
    sb.append("[框] \"最后输入的文字\" 可输入 中心=(1,2)\n");
    assertEquals("最后输入的文字", inputText(sb.toString()));
  }

  @Test
  public void 屏幕变化_读不到时算已变化() throws Exception {
    assertTrue("after 为 null 应算变化（别重试乱点）", screenChanged("a", null));
    assertTrue("读屏报错应算变化", screenChanged("a", "[ERR] 读屏失败"));
    // ★ Phase 14 变异 M10 逼出来的用例。
    //   原来只断言上面两条 —— 但 `screenChanged("a", "[ERR] 读屏失败")` 即使
    //   **没有** [ERR] 那道检查也会返回 true（"a" 和 "[ERR] …" 本来就不相等），
    //   所以那两条根本测不出检查是否还存在。真正能区分的用例是 before 也是 [ERR]：
    //   有检查 → 算变化（true）；没检查 → 两边相同 → 算没变化（false）。
    assertTrue(
        "before 也是 [ERR] 时仍应算变化 —— 这才是那道检查唯一可观测的效果",
        screenChanged("[ERR] 读屏失败", "[ERR] 读屏失败"));
  }

  @Test
  public void 屏幕变化_忽略纯空白差异() throws Exception {
    assertFalse("只差空白不算变化", screenChanged("[a] [b]", "[a]  [b]"));
    assertFalse("只差换行不算变化", screenChanged("[a]\n[b]", "[a] [b]"));
  }

  @Test
  public void 屏幕变化_内容不同算变化() throws Exception {
    assertTrue(screenChanged("[a]", "[a] [b]"));
    assertTrue(screenChanged("[a] [b]", "[a]"));
  }

  // ================================================================
  // 5. 日志安全：静态兜底（行为级需 Android 环境，见 TEST-COVERAGE-UPDATE）
  // ================================================================

  /**
   * D-13 已把"模型完整响应进日志"修掉（{@code LogFormat.modelAction}）。
   * 这条是静态回归：确认没有新的形状把响应正文/用户目标拼进日志。
   *
   * <p><b>为什么只能静态</b>：{@code askAction} 需要 {@code AssistantConfig} +
   * {@code AssistantApiClient.chat}（HTTP）+ {@code DshaAccessibilityService}，
   * 纯 JUnit 跑不起来。
   */
  @Test
  public void 不得把完整模型响应或用户目标拼进日志() throws Exception {
    String src = readMainSource("AssistantTaskRunner.java");
    assertTrue("模型动作日志必须走 LogFormat.modelAction", src.contains("LogFormat.modelAction("));
    assertFalse(
        "不得再出现把 resp 原样拼进日志（D-13 复发形状）",
        src.matches("(?s).*Log\\.[a-z]+\\([^)]*\"[^\"]*\"\\s*\\+\\s*resp\\b.*"));
    assertFalse(
        "不得把 task.goal 原样拼进日志（D-10 复发形状）",
        src.matches("(?s).*Log\\.[a-z]+\\([^)]*\"[^\"]*\"\\s*\\+\\s*task\\.goal\\b.*"));
    assertFalse(
        "不得把动作描述 trace 原样拼进日志（trace 里含 input 的 text）",
        src.matches("(?s).*Log\\.[a-z]+\\([^)]*\"[^\"]*\"\\s*\\+\\s*trace\\b.*"));
    // ★ 只查**日志行**里的 dump.substring —— Phase 14 第一版写成全文 contains，
    //   结果把 :311 的 pkgLine 提取和 :326 的 dump 截断（都不是日志）也算成违规，
    //   报了个假阳性。真正的判据是"dump 正文切片有没有进 Log"。
    java.util.regex.Pattern logLine =
        java.util.regex.Pattern.compile("Log\\.[a-z]+\\([^;]*dump\\.substring[^;]*;");
    assertFalse(
        "不得把屏幕 dump 正文切片拼进日志",
        logLine.matcher(src).find());

    // ★ Phase 14 新补：LogPrivacyTest 的 SAFE_FIELDS 里放了 `detail`，
    //   理由是"detail 已过 SensitiveData.redact"。但**没有任何测试守这个前提** ——
    //   把 redact 删掉，白名单照样放行，异常里的 URL/请求体/凭据片段就进日志了。
    //   这条静态断言把那个前提钉住。
    assertTrue(
        "任务异常的 detail 必须过 SensitiveData.redact（SAFE_FIELDS 放行它的唯一依据）",
        src.contains("SensitiveData.redact(String.valueOf(t))"));
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

  private static JSONObject obj(String json) throws Exception {
    return new JSONObject(json);
  }
}
