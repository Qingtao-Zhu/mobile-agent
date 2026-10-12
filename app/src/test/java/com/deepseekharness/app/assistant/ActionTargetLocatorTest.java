package com.deepseekharness.app.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * {@link ActionTargetLocator} 的纯逻辑测试（JVM 单元测试，零设备依赖）。
 *
 * <p>覆盖用户要求（第二阶段 P0-2）：
 * <ul>
 *   <li>各定位策略的优先级
 *   <li>匹配失败时是否正确降级（而不是乱点）
 *   <li>多候选同时命中时是否避免任意选择
 *   <li>置信度不足时是否返回"无法确认"
 *   <li>定位结果是否携带足够诊断信息
 * </ul>
 */
public class ActionTargetLocatorTest {

  /** 模仿 uiDump 的真实输出格式（含 2026-10-11 新增的 id= / cd= 字段）。 */
  private static final String DUMP =
      "窗口应用: org.example.chat\n"
          + "[1] \"聊天\" 可点击 中心=(160,100) 区域=0,80,320,120 id=title\n"
          + "[2] \"发送\" 可点击 中心=(280,600) 区域=240,580,320,620 id=send_btn cd=\"发送消息\"\n"
          + "[3] \"取消\" 可点击 中心=(120,600) 区域=80,580,160,620 id=cancel_btn\n"
          + "[4] 可点击 中心=(160,700) 区域=0,650,320,750 id=root_container\n"
          + "[5] 输入框 可输入 中心=(160,500) 区域=0,470,320,530 id=input_box\n";

  private static ActionTargetStore.Target target(String... ruleSpecs) {
    ActionTargetStore.Target t = new ActionTargetStore.Target();
    t.id = "test.target";
    t.appLabel = "ExampleChat";
    t.name = "发送";
    t.packages.add("org.example.chat");
    for (String spec : ruleSpecs) {
      // 格式 "by|value|exact"
      String[] p = spec.split("\\|", -1);
      t.rules.add(new ActionTargetStore.Rule(p[0], p[1], p.length > 2 && "true".equals(p[2])));
    }
    return t;
  }

  // ---------------------------------------------------------------- 命中

  @Test
  public void 按_viewId_命中并在中心坐标() {
    ActionTargetLocator.Result r =
        ActionTargetLocator.locate(DUMP, target("viewId|send_btn"), "org.example.chat");
    assertTrue(r.found());
    assertEquals(280, r.cx);
    assertEquals(600, r.cy);
    assertEquals(1.0f, r.confidence, 0.001f); // viewId 命中给最高置信度
  }

  @Test
  public void 按_contentDescription_命中() {
    ActionTargetLocator.Result r =
        ActionTargetLocator.locate(DUMP, target("contentDescription|发送消息|true"),
            "org.example.chat");
    assertTrue(r.found());
    assertEquals(280, r.cx);
  }

  @Test
  public void 按_text_命中() {
    ActionTargetLocator.Result r =
        ActionTargetLocator.locate(DUMP, target("text|发送|true"), "org.example.chat");
    assertTrue(r.found());
    assertEquals(0.7f, r.confidence, 0.001f); // 文本命中置信度低于 viewId
  }

  // ---------------------------------------------------------------- 规则优先级

  @Test
  public void 规则是有序候选_靠前的先命中() {
    // 第一条规则指向"取消"，第二条指向"发送" → 应命中"取消"（因为它是第一候选）
    ActionTargetLocator.Result r = ActionTargetLocator.locate(DUMP,
        target("text|取消|true", "text|发送|true"), "org.example.chat");
    assertTrue(r.found());
    assertEquals(120, r.cx); // 取消按钮的 x
  }

  @Test
  public void 第一条不中时自动落到第二条() {
    ActionTargetLocator.Result r = ActionTargetLocator.locate(DUMP,
        target("viewId|does_not_exist", "text|发送|true"), "org.example.chat");
    assertTrue(r.found());
    assertEquals(280, r.cx);
    assertTrue("应记录试过哪些规则", r.tried.size() >= 1);
  }

  // ---------------------------------------------------------------- 降级与失败

  @Test
  public void 全不命中_返回失败并建议走AI() {
    ActionTargetLocator.Result r = ActionTargetLocator.locate(DUMP,
        target("text|不存在的按钮|true"), "org.example.chat");
    assertFalse(r.found());
    assertTrue("应建议升级到 AI", r.needsAi);
    assertNotNull(r.reason);
  }

  @Test
  public void 全不命中_绝不返回猜测坐标() {
    ActionTargetLocator.Result r = ActionTargetLocator.locate(DUMP,
        target("text|不存在的按钮|true"), "org.example.chat");
    // 这是安全底线：找不到就是找不到，不能给出任何坐标让上层去点
    assertNull(r.bounds);
  }

  @Test
  public void 界面为空_失败且建议AI() {
    ActionTargetLocator.Result r =
        ActionTargetLocator.locate("", target("text|发送|true"), "org.example.chat");
    assertFalse(r.found());
    assertTrue(r.needsAi);
  }

  @Test
  public void dump为null_不崩且失败() {
    ActionTargetLocator.Result r =
        ActionTargetLocator.locate(null, target("text|发送|true"), "org.example.chat");
    assertFalse(r.found());
    assertNotNull(r.reason);
  }

  @Test
  public void 目标为null_不崩且失败() {
    ActionTargetLocator.Result r = ActionTargetLocator.locate(DUMP, null, "org.example.chat");
    assertFalse(r.found());
  }

  @Test
  public void 没有配置规则_明确说明而不是静默失败() {
    ActionTargetLocator.Result r =
        ActionTargetLocator.locate(DUMP, target(), "org.example.chat");
    assertFalse(r.found());
    assertTrue(r.reason.contains("还没配置"));
  }

  // ---------------------------------------------------------------- 包名校验

  @Test
  public void 不在目标应用里_拒绝定位() {
    ActionTargetLocator.Result r =
        ActionTargetLocator.locate(DUMP, target("text|发送|true"), "com.other.app");
    assertFalse("不在目标应用里不应该找按钮（可能在别处点到同名按钮）", r.found());
    assertTrue(r.reason.contains("不是目标应用"));
  }

  @Test
  public void 包名匹配时正常定位() {
    ActionTargetLocator.Result r =
        ActionTargetLocator.locate(DUMP, target("text|发送|true"), "org.example.chat");
    assertTrue(r.found());
  }

  // ---------------------------------------------------------------- 多候选

  @Test
  public void 多候选命中_优先可点击而不是容器() {
    // ["发送" 可点击] 与 [一个同名但不可点击的容器]
    String dump = "窗口应用: org.example.chat\n"
        + "[1] \"发送\" 中心=(10,10) 区域=0,0,20,20 id=label_only\n"
        + "[2] \"发送\" 可点击 中心=(280,600) 区域=240,580,320,620 id=btn\n";
    ActionTargetLocator.Result r =
        ActionTargetLocator.locate(dump, target("text|发送|true"), "org.example.chat");
    assertTrue(r.found());
    assertEquals("应挑可点击的那个", 280, r.cx);
  }

  @Test
  public void 多候选命中_同可点击时取面积小的那个() {
    // 大容器 与 小按钮 都叫"发送"且都可点 → 按钮通常面积小，点它的中心才落在按钮上
    String dump = "窗口应用: org.example.chat\n"
        + "[1] \"发送\" 可点击 中心=(160,400) 区域=0,0,320,800 id=big_wrapper\n"
        + "[2] \"发送\" 可点击 中心=(280,600) 区域=240,580,320,620 id=real_btn\n";
    ActionTargetLocator.Result r =
        ActionTargetLocator.locate(dump, target("text|发送|true"), "org.example.chat");
    assertTrue(r.found());
    assertEquals("应挑面积更小的那个", 280, r.cx);
  }

  @Test
  public void 命中但不可点击_不交出坐标并建议复核() {
    String dump = "窗口应用: org.example.chat\n"
        + "[1] \"发送\" 中心=(280,600) 区域=240,580,320,620 id=static_label\n";
    ActionTargetLocator.Result r =
        ActionTargetLocator.locate(dump, target("text|发送|true"), "org.example.chat");

    // 安全底线：found() 的语义是"可以对这个位置下手"。
    // 不可点击的节点**绝不能**算作 found，也**绝不能**留下坐标——
    // 否则上层拿到一个 (280,600) 就可能真去点，而那里只是个文字标签。
    assertFalse("不可点击的节点不能被当成可下手的目标", r.found());
    assertNull("更不能交出坐标让上层乱点", r.bounds);

    assertTrue("要说清是「看见了却不能点」", r.reason.contains("不可点击"));
    assertTrue("且建议 AI 复核", r.needsAi);
    assertNotNull("仍要说明是靠哪条规则看见的（便于诊断）", r.via);
  }

  // ---------------------------------------------------------------- 诊断信息

  @Test
  public void 成功时携带命中依据() {
    ActionTargetLocator.Result r =
        ActionTargetLocator.locate(DUMP, target("viewId|send_btn"), "org.example.chat");
    assertNotNull("要说明是哪条规则命中的", r.via);
    assertTrue(r.via.contains("send_btn"));
  }

  @Test
  public void 失败时携带试过的规则列表() {
    ActionTargetLocator.Result r = ActionTargetLocator.locate(DUMP,
        target("viewId|nope1", "text|nope2"), "org.example.chat");
    assertFalse(r.found());
    assertEquals("应记录两条规则都试过", 2, r.tried.size());
    assertTrue(r.tried.get(0).contains("nope1"));
  }

  // ---------------------------------------------------------------- learnRules（用户校准）

  @Test
  public void 从用户点中的节点学习规则_优先viewId() {
    List<ActionTargetStore.Rule> rules = ActionTargetLocator.learnRules(DUMP, 280, 600);
    assertFalse("应学到规则", rules.isEmpty());
    assertEquals("第一条应当是 viewId", "viewId", rules.get(0).by);
    assertEquals("send_btn", rules.get(0).value);
  }

  @Test
  public void 学习出的规则不含坐标() {
    List<ActionTargetStore.Rule> rules = ActionTargetLocator.learnRules(DUMP, 280, 600);
    for (ActionTargetStore.Rule r : rules) {
      assertFalse("规则只能存特征，绝不能存坐标", r.by.toLowerCase().contains("coord"));
      assertFalse(r.value.matches("^\\d+$"));
    }
  }

  @Test
  public void 点在空白处_学不到规则而不是乱猜() {
    List<ActionTargetStore.Rule> rules = ActionTargetLocator.learnRules(DUMP, 9999, 9999);
    assertTrue(rules.isEmpty());
  }
}
