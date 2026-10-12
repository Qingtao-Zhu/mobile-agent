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

    // digest 目前不截断（这是设计：喂给模型的要完整）——
    // 这一点记为已知特性，在报告中说明其隐私含义
    String dig = call("digest", new Class<?>[] {int.class}, 1);
    assertTrue("digest 设计上给完整条目（喂模型用）", dig.contains(sb.toString()));
  }
}
