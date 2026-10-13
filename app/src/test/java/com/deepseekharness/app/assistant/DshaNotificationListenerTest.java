package com.deepseekharness.app.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * {@link DshaNotificationListener} 单元测试（Phase 11 阶段 4 新增，P0 优先级）。
 *
 * <p><b>为什么这个类必须测</b>：它把**通知栏内容**（可能含短信验证码、聊天消息、
 * 银行动账）摘要后念给用户、并喂给模型。判定/截断/过滤写错会直接泄露敏感内容。
 * 此前该类**零测试**。
 *
 * <p>可测点（均为包级私有静态方法，用反射注入 {@code cache} 后调用）：
 * <ul>
 *   <li>{@code summarize(int)} —— 念给用户的摘要（含截断 {@code clip}）</li>
 *   <li>{@code digest(int)} —— 喂给模型的条目（无引导语）</li>
 *   <li>{@code transitPkg(String)} —— 公交类包名判定</li>
 * </ul>
 */
public class DshaNotificationListenerTest {

  /** 用反射把测试数据塞进私有的 static cache 字段。 */
  private static void setCache(List<String> data) throws Exception {
    Field f = DshaNotificationListener.class.getDeclaredField("cache");
    f.setAccessible(true);
    f.set(null, data);
  }

  private static String call(String name, Class<?>[] sig, Object... args) throws Exception {
    Method m = DshaNotificationListener.class.getDeclaredMethod(name, sig);
    m.setAccessible(true);
    return (String) m.invoke(null, args);
  }

  // ================================================================
  // 1. digest：喂给模型的那条
  // ================================================================

  @Test
  public void digest_空缓存返回空串() throws Exception {
    setCache(null);
    assertEquals("缓存为 null 时应返回空串", "", call("digest", new Class<?>[] {int.class}, 3));

    setCache(Arrays.asList());
    assertEquals("缓存为空列表时应返回空串", "", call("digest", new Class<?>[] {int.class}, 3));
  }

  @Test
  public void digest_按want截断且不带引导语() throws Exception {
    setCache(Arrays.asList("第一条", "第二条", "第三条", "第四条"));
    String d = call("digest", new Class<?>[] {int.class}, 2);
    assertNotNull(d);
    assertTrue("应包含第 1 条", d.contains("第一条"));
    assertTrue("应包含第 2 条", d.contains("第二条"));
    assertFalse("不应包含第 3 条（want=2）", d.contains("第三条"));
    assertFalse("digest 不应带『通知栏 N 条』引导语", d.contains("通知栏"));
  }

  @Test
  public void digest_want小于1时按1处理() throws Exception {
    setCache(Arrays.asList("唯一一条"));
    String d = call("digest", new Class<?>[] {int.class}, 0);
    assertTrue("want=0 也应至少给出 1 条", d.contains("唯一一条"));
  }

  @Test
  public void digest_want超过总数时给全部() throws Exception {
    setCache(Arrays.asList("甲", "乙"));
    String d = call("digest", new Class<?>[] {int.class}, 99);
    assertTrue(d.contains("甲"));
    assertTrue(d.contains("乙"));
  }

  // ================================================================
  // 2. summarize：念给用户的摘要
  // ================================================================

  @Test
  public void summarize_空缓存返回null() throws Exception {
    setCache(null);
    assertNull("无通知时应返回 null（调用方自己回『现在没通知』）",
        call("summarize", new Class<?>[] {int.class}, 3));

    setCache(Arrays.asList());
    assertNull("空列表同样返回 null", call("summarize", new Class<?>[] {int.class}, 3));
  }

  @Test
  public void summarize_应含总数与条数引导语() throws Exception {
    setCache(Arrays.asList("A", "B", "C", "D", "E"));
    String s = call("summarize", new Class<?>[] {int.class}, 3);
    assertNotNull(s);
    assertTrue("应说明总条数", s.contains("通知栏 5 条"));
    assertTrue("应说明取最近几条", s.contains("最近的 3 条"));
    assertTrue("应提示还有多少没念", s.contains("还有 2 条没念"));
  }

  @Test
  public void summarize_未超want时不出现还有字样() throws Exception {
    setCache(Arrays.asList("A", "B"));
    String s = call("summarize", new Class<?>[] {int.class}, 5);
    assertTrue("应说明总条数", s.contains("通知栏 2 条"));
    assertFalse("不满一屏时不应说『还有 N 条没念』", s.contains("没念"));
    assertFalse("不满一屏时不应说『最近的』", s.contains("最近的"));
  }

  @Test
  public void summarize_长条目必须被截断() throws Exception {
    // clip(46)：超过 46 字的条目要被截断，防止把整条短信/聊天念出来
    StringBuilder long_ = new StringBuilder();
    for (int i = 0; i < 100; i++) long_.append('长');
    setCache(Arrays.asList(long_.toString()));
    String s = call("summarize", new Class<?>[] {int.class}, 1);
    assertNotNull(s);
    // clip(46)：条目正文截到 46 字符。这里数一下正文里 '长' 字的个数，
    // 必须 <= 46（原始 100 个）。
    int longCount = 0;
    for (char c : s.toCharArray()) {
      if (c == '长') longCount++;
    }
    assertTrue("条目应被 clip 截断到 46 字符以内，实际 '长' 字 " + longCount, longCount <= 46);
    assertTrue("且应确实发生了截断（不是原样 100 字）", longCount < 100);
  }

  @Test
  public void summarize_want异常值不崩() throws Exception {
    setCache(Arrays.asList("A"));
    assertNotNull("want=0 不应崩", call("summarize", new Class<?>[] {int.class}, 0));
    assertNotNull("want 负数不应崩", call("summarize", new Class<?>[] {int.class}, -5));
    assertNotNull("want 极大不应崩", call("summarize", new Class<?>[] {int.class}, 99999));
  }

  // ================================================================
  // 3. transitPkg：公交类包名判定
  // ================================================================

  @Test
  public void transitPkg_识别常见公交地图类包名() throws Exception {
    Method m = DshaNotificationListener.class.getDeclaredMethod("transitPkg", String.class);
    m.setAccessible(true);
    assertTrue("车乐乐", (Boolean) m.invoke(null, "com.chelaile.voice"));
    assertTrue("百度地图", (Boolean) m.invoke(null, "com.baidu.map.location"));
    assertTrue("高德", (Boolean) m.invoke(null, "com.autonavi.minimap"));
    assertTrue("amap", (Boolean) m.invoke(null, "com.amap.app"));
    assertTrue("通用 bus 关键词", (Boolean) m.invoke(null, "com.city.bus"));
  }

  @Test
  public void transitPkg_普通应用不误判() throws Exception {
    Method m = DshaNotificationListener.class.getDeclaredMethod("transitPkg", String.class);
    m.setAccessible(true);
    assertFalse("微信不是公交类", (Boolean) m.invoke(null, "com.tencent.mm"));
    assertFalse("设置不是公交类", (Boolean) m.invoke(null, "com.android.settings"));
    assertFalse("支付宝不是公交类", (Boolean) m.invoke(null, "com.eg.android.alipay"));
  }

  @Test
  public void transitPkg_null不误判且不抛() throws Exception {
    Method m = DshaNotificationListener.class.getDeclaredMethod("transitPkg", String.class);
    m.setAccessible(true);
    assertFalse("null 包名应返回 false 且不抛", (Boolean) m.invoke(null, new Object[] {null}));
  }

  // ================================================================
  // 4. 隐私关联：digest/summarize 的内容来自通知栏，必须能被截断
  // ================================================================

  @Test
  public void 摘要不得原样吐出超长通知内容() throws Exception {
    // 模拟一条很长的"验证码短信"式通知
    StringBuilder sb = new StringBuilder("验证码 ");
    for (int i = 0; i < 80; i++) sb.append((char) ('0' + i % 10));
    setCache(Arrays.asList(sb.toString()));

    String sum = call("summarize", new Class<?>[] {int.class}, 1);
    assertNotNull(sum);
    assertFalse("summarize 必须截断，不得原样包含整条长通知",
        sum.contains(sb.toString()));

    // ★ 本条断言在 Phase 13 被**改正**（原文是 assertTrue(dig.contains(...))，
    //   即"digest 必须原样吐出完整长通知"）。
    //   那个方向是错的：它把"喂模型的出口不做最小化"这条缺陷固化成了"设计"，
    //   等于给后来的修复设了一道测试障碍。D-14 修复后两个出口共用 item()，
    //   断言随之改为"digest 同样不得原样吐出"，才是数据最小化应有的方向。
    String dig = call("digest", new Class<?>[] {int.class}, 1);
    assertNotNull(dig);
    assertFalse("digest 也必须截断：喂给远端模型的正文不该比念给用户听的更完整",
        dig.contains(sb.toString()));
  }

  // ================================================================
  // 5. D-14 回归（Phase 13）：通知正文进模型提示词之前的数据流
  // ================================================================

  /** 唯一标记：只在测试里编出来的串，真实通知里不可能出现。 */
  private static final String MARK = "NTMARK7F3QZ";

  /** 造一条"长通知"，把唯一标记放在尾部 —— 只有不做截断，标记才会漏出去。 */
  private static String longNoticeWithTailMark() {
    StringBuilder body = new StringBuilder("张三：");
    for (int i = 0; i < 60; i++) body.append('正');
    body.append(MARK);
    return body.toString();
  }

  @Test
  public void D14_尾部标记不得经模型段落漏出() throws Exception {
    setCache(Arrays.asList(longNoticeWithTailMark()));
    String section = DshaNotificationListener.modelSection(5);
    assertFalse(
        "喂给模型的通知段落里不得出现通知尾部内容（说明没做最小化）",
        section.contains(MARK));
    assertTrue("段落应带上小标题", section.contains("【最近的通知"));
  }

  @Test
  public void D14_念给用户与喂给模型必须用同一个最小化口径() throws Exception {
    setCache(Arrays.asList(longNoticeWithTailMark()));

    String sum = call("summarize", new Class<?>[] {int.class}, 1);
    String dig = call("digest", new Class<?>[] {int.class}, 1);

    String sumItem = sum.substring(sum.indexOf("· ") + 2).trim();
    String digItem = dig.substring(dig.indexOf("· ") + 2).trim();
    assertEquals(
        "两条出口对同一条缓存必须产出完全相同的条目（口径不一致就是最小化缺口）",
        sumItem, digItem);
    assertFalse("两边都不应含尾部标记", sum.contains(MARK) || dig.contains(MARK));
  }

  @Test
  public void D14_单条输出长度不得超过上限() throws Exception {
    setCache(Arrays.asList(longNoticeWithTailMark(), "短一条"));
    String dig = call("digest", new Class<?>[] {int.class}, 5);
    for (String part : dig.split("\n· ")) {
      if (part.trim().isEmpty()) continue;
      assertTrue(
          "单条输出应 <= " + (DshaNotificationListener.ITEM_CLIP) + " 字符，实际 "
              + part.length() + "：" + part,
          part.trim().length() <= DshaNotificationListener.ITEM_CLIP);
    }
  }

  @Test
  public void D14_通知里的令牌形状串不得原样进模型段落() throws Exception {
    // 假令牌（格式像、值是编的，不会被任何服务接受）
    String fake = "sk-FAKE000011112222333344445555";
    setCache(Arrays.asList("快递通知 取件码在 " + fake + " 后面那几位"));
    String section = DshaNotificationListener.modelSection(5);
    assertFalse("令牌形状串必须先过脱敏再进模型请求", section.contains(fake));
  }

  @Test
  public void D14_没通知时模型段落必须是空串() throws Exception {
    setCache(null);
    assertEquals("缓存为 null 时不应往提示词里塞任何东西",
        "", DshaNotificationListener.modelSection(5));
    setCache(Arrays.asList());
    assertEquals("空缓存同理", "", DshaNotificationListener.modelSection(5));
  }

  @Test
  public void D14_异常条目不崩且不放大内容() throws Exception {
    // 缓存里混进 null / 超长 / 空白，出口都不能炸，也不能把缓存整体吐出来
    StringBuilder huge = new StringBuilder();
    for (int i = 0; i < 5000; i++) huge.append('X');
    setCache(Arrays.asList(null, "", "   ", huge.toString(), null));
    String section = DshaNotificationListener.modelSection(5);
    assertNotNull("不应抛异常", section);
    assertFalse("超长条目不得原样进提示词", section.contains(huge.toString()));
    assertTrue("输出本身也要有界（5000 字的条目不该带出几千字）", section.length() < 500);
  }

  // ---------------------------------------------------------------- 门控

  private static boolean mentionsNotify(String q) throws Exception {
    Method m = AssistantPanel.class.getDeclaredMethod("mentionsNotify", String.class);
    m.setAccessible(true);
    return (Boolean) m.invoke(null, q);
  }

  @Test
  public void D14_门控_不问通知时不得命中() throws Exception {
    assertFalse("今天天气怎么样", mentionsNotify("今天天气怎么样"));
    assertFalse("帮我定个闹钟", mentionsNotify("帮我定个闹钟"));
    assertFalse("这句话里有个通知两个字但没在问", mentionsNotify("把通知栏开关打开"));
    assertFalse("null 问题", mentionsNotify(null));
  }

  @Test
  public void D14_门控_问通知时要命中() throws Exception {
    assertTrue("谁在找我", mentionsNotify("谁在找我"));
    assertTrue("通知栏有啥", mentionsNotify("通知栏有啥"));
    assertTrue("有啥消息", mentionsNotify("看看有啥消息"));
  }

  /**
   * 门控的**接线**是否正确（{@code modelSection} 必须挂在 {@code mentionsNotify &&
   * isConnected} 这个条件里）。
   *
   * <p>为什么只能用静态断言：{@code AssistantPanel.askModel} 依赖 Activity / Config /
   * API Key，纯 JUnit 环境（本机没配 Robolectric）里跑不起来，
   * 所以"整段提示词真的不含通知正文"这条无法在本机做行为级验证 —— 记为
   * <b>NOT TESTED</b>，不冒充 PASS。这条静态断言 + 上面 {@code modelSection} 的
   * 行为测试，是目前能拿到的最强证据。
   */
  @Test
  public void D14_模型段落的调用点必须被门控包住() throws Exception {
    String src = readMainSource("AssistantPanel.java");
    assertTrue("提示词里必须存在 modelSection 调用", src.contains("DshaNotificationListener.modelSection("));
    assertTrue(
        "modelSection 必须挂在 mentionsNotify(question) 与 isConnected() 的双重条件内",
        src.contains("if (mentionsNotify(question) && DshaNotificationListener.isConnected())"));
    assertFalse(
        "不得出现不被门控的 digest 直调（绕开最小化口径的旧写法）",
        src.contains("DshaNotificationListener.digest("));
  }

  /**
   * 入口闸门：{@code line()} 必须在**入库时**就用 {@link AssistantGuard} 过滤敏感包名。
   *
   * <p>这是目前 digest 路径上唯一的"按来源"保护 —— {@code transit()} 那道
   * {@code isProtected} 只在公交类通知上触发，管不到 digest（见 DATA-FLOW-FINDINGS）。
   * 所以这道闸门的正确性格外关键，而它依赖的 {@code AssistantGuard.isProtected}
   * 由 {@code AssistantGuardTest} 做行为级覆盖（18 条）；这里只能静态确认"确实接了线"。
   */
  @Test
  public void D14_入库闸门必须调用敏感应用判定() throws Exception {
    String src = readMainSource("DshaNotificationListener.java");
    assertTrue("line() 必须在入库前判定受保护包名",
        src.contains("if (AssistantGuard.isProtected(pkg)) return null;"));
    assertTrue("transit() 那道纵深防御应保留",
        src.contains("if (AssistantGuard.isProtected(pkg)) return;"));
    // ★ 这里数的是**出现次数**，不是 contains —— 变异 M5（只把 digest 改回 c.get(i)）
    //   实测证明：summary 还在用 item()，src.contains(...) 依然为真，
    //   那条断言会**放过**"两个出口口径不一致"这个正是 D-14 要防的问题。
    //   必须两个出口都在用，所以要求 >= 2 处。
    int itemUses = 0;
    int at = 0;
    while ((at = src.indexOf("item(c.get(i))", at)) >= 0) {
      itemUses++;
      at += "item(c.get(i))".length();
    }
    assertTrue(
        "summarize 与 digest 两个出口都必须走 item()，实际只有 " + itemUses + " 处在用",
        itemUses >= 2);
  }

  /** 读一个生产源码文件（纯代码，去掉注释行）。 */
  private static String readMainSource(String fileName) throws Exception {
    java.nio.file.Path[] roots = {
      java.nio.file.Paths.get("src/main/java/com/deepseekharness/app"),
      java.nio.file.Paths.get("app/src/main/java/com/deepseekharness/app"),
      java.nio.file.Paths.get("../app/src/main/java/com/deepseekharness/app")
    };
    for (java.nio.file.Path root : roots) {
      if (!java.nio.file.Files.exists(root)) continue;
      java.util.List<java.nio.file.Path> hits = new java.util.ArrayList<>();
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
    throw new AssertionError("找不到生产源码：" + fileName
        + "（cwd=" + java.nio.file.Paths.get("").toAbsolutePath() + "）");
  }
}
