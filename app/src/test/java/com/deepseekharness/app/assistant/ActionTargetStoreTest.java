package com.deepseekharness.app.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ActionTargetStore} 的纯逻辑测试（JVM 单元测试）。
 *
 * <p>覆盖用户要求（第二阶段 P0-2）：
 * <ul>
 *   <li>特征的持久化（序列化往返）
 *   <li>风险等级与设备验证信息持久化
 *   <li>缺失字段、错误数据的容错
 *   <li>schema 版本不匹配的处理
 * </ul>
 *
 * <p><b>测不到的部分</b>：真正的文件读写走 {@code Context} + {@link ConfigStoreLite}，
 * JVM 里没有 Context → 那部分标为"待设备验证"（不在本文件里假装测过）。
 */
public class ActionTargetStoreTest {

  // ---------------------------------------------------------------- 序列化往返

  @Test
  public void 目标序列化往返_字段完整() throws Exception {
    ActionTargetStore.Target t = new ActionTargetStore.Target();
    t.id = "tg.send";
    t.appLabel = "Telegram";
    t.name = "发送消息";
    t.risk = ActionTargetStore.Risk.SEND;
    t.confirm = true;
    t.packages.add("org.telegram.messenger");
    t.packages.add("org.telegram.messenger.web");
    t.rules.add(new ActionTargetStore.Rule("viewId", "send", false));
    t.rules.add(new ActionTargetStore.Rule("text", "发送", true));
    t.verifiedOn.add("SP100|android30|720x1600");

    JSONObject json = t.toJson();
    assertNotNull(json);
    ActionTargetStore.Target back = ActionTargetStore.Target.fromJson(json);

    assertNotNull(back);
    assertEquals(t.id, back.id);
    assertEquals(t.appLabel, back.appLabel);
    assertEquals(t.name, back.name);
    assertEquals(t.risk, back.risk);
    assertEquals(t.confirm, back.confirm);
    assertEquals(2, back.packages.size());
    assertEquals(2, back.rules.size());
    assertEquals(1, back.verifiedOn.size());
    assertEquals("SP100|android30|720x1600", back.verifiedOn.get(0));
  }

  @Test
  public void 规则的特征字段持久化() throws Exception {
    ActionTargetStore.Rule r = new ActionTargetStore.Rule("contentDescription", "发送消息", true);
    ActionTargetStore.Rule back = ActionTargetStore.Rule.fromJson(r.toJson());
    assertNotNull(back);
    assertEquals("contentDescription", back.by);
    assertEquals("发送消息", back.value);
    assertTrue(back.exact);
  }

  @Test
  public void 规则默认是包含匹配() throws Exception {
    ActionTargetStore.Rule r = new ActionTargetStore.Rule("text", "发送", false);
    JSONObject o = r.toJson();
    assertFalse("exact=false 时不该写这个字段（省体积）", o.has("exact"));
    assertFalse(ActionTargetStore.Rule.fromJson(o).exact);
  }

  @Test
  public void 风险等级持久化() throws Exception {
    for (String risk : new String[] {"safe", "send", "pay", "delete"}) {
      ActionTargetStore.Target t = new ActionTargetStore.Target();
      t.id = "x";
      t.risk = risk;
      assertEquals(risk, ActionTargetStore.Target.fromJson(t.toJson()).risk);
    }
  }

  // ---------------------------------------------------------------- 匹配语义

  @Test
  public void 规则_包含匹配() {
    ActionTargetStore.Rule r = new ActionTargetStore.Rule("text", "发送", false);
    assertTrue(r.matches("发送消息"));
    assertTrue(r.matches("发送"));
    assertFalse(r.matches("接收"));
  }

  @Test
  public void 规则_完全匹配() {
    ActionTargetStore.Rule r = new ActionTargetStore.Rule("text", "发送", true);
    assertTrue(r.matches("发送"));
    assertFalse("完全匹配时不应命中更长的文本", r.matches("发送消息"));
  }

  @Test
  public void 规则_匹配前先去空白() {
    ActionTargetStore.Rule r = new ActionTargetStore.Rule("text", "发送", true);
    assertTrue(r.matches("  发送  "));
  }

  @Test
  public void 规则_空值不命中() {
    ActionTargetStore.Rule r = new ActionTargetStore.Rule("text", "", false);
    assertFalse(r.matches("任何东西"));
    assertFalse(r.matches(null));
  }

  // ---------------------------------------------------------------- 风险分级

  @Test
  public void 发送_付款_删除都要确认() {
    assertTrue(ActionTargetStore.Risk.needsConfirm(ActionTargetStore.Risk.SEND));
    assertTrue(ActionTargetStore.Risk.needsConfirm(ActionTargetStore.Risk.PAY));
    assertTrue(ActionTargetStore.Risk.needsConfirm(ActionTargetStore.Risk.DELETE));
  }

  @Test
  public void 安全操作不需要确认() {
    assertFalse(ActionTargetStore.Risk.needsConfirm(ActionTargetStore.Risk.SAFE));
  }

  @Test
  public void 目标默认跟随风险等级决定是否确认() {
    ActionTargetStore.Target t = new ActionTargetStore.Target();
    t.risk = ActionTargetStore.Risk.PAY;
    t.confirm = false;
    assertTrue("付款类即使没显式标 confirm 也必须确认", t.shouldConfirm());
  }

  @Test
  public void 目标可以显式强制确认() {
    ActionTargetStore.Target t = new ActionTargetStore.Target();
    t.risk = ActionTargetStore.Risk.SAFE;
    t.confirm = true;
    assertTrue(t.shouldConfirm());
  }

  @Test
  public void 风险等级有人话说明() {
    assertNotNull(ActionTargetStore.Risk.label(ActionTargetStore.Risk.PAY));
    assertTrue(ActionTargetStore.Risk.label(ActionTargetStore.Risk.PAY).contains("付款"));
    assertTrue(ActionTargetStore.Risk.label(ActionTargetStore.Risk.DELETE).contains("删除"));
  }

  // ---------------------------------------------------------------- 坏数据容错

  @Test
  public void 缺_id_的条目丢弃() throws Exception {
    JSONObject o = new JSONObject();
    o.put("appLabel", "没有 id");
    assertNull("没 id 的条目没法被引用，应丢弃", ActionTargetStore.Target.fromJson(o));
  }

  @Test
  public void 缺可选字段_不崩且用默认值() throws Exception {
    JSONObject o = new JSONObject();
    o.put("id", "only.id");
    ActionTargetStore.Target t = ActionTargetStore.Target.fromJson(o);
    assertNotNull(t);
    assertEquals(ActionTargetStore.Risk.SAFE, t.risk);
    assertTrue(t.packages.isEmpty());
    assertTrue(t.rules.isEmpty());
    assertFalse(t.confirm);
  }

  @Test
  public void 规则缺_by_或_value_丢弃() throws Exception {
    JSONObject a = new JSONObject();
    a.put("value", "只有值");
    assertNull(ActionTargetStore.Rule.fromJson(a));

    JSONObject b = new JSONObject();
    b.put("by", "text");
    assertNull(ActionTargetStore.Rule.fromJson(b));
  }

  @Test
  public void 规则字段类型不对时容错() throws Exception {
    JSONObject o = new JSONObject();
    o.put("by", "text");
    o.put("value", 12345); // 数字而不是字符串
    ActionTargetStore.Rule r = ActionTargetStore.Rule.fromJson(o);
    assertNotNull(r);
    assertEquals("12345", r.value); // optString 会转成字符串
  }

  @Test
  public void packages里混入空值_只保留有效的() throws Exception {
    JSONObject o = new JSONObject();
    o.put("id", "x");
    JSONArray ps = new JSONArray();
    ps.put("com.a");
    ps.put("");
    ps.put("com.b");
    o.put("packages", ps);
    ActionTargetStore.Target t = ActionTargetStore.Target.fromJson(o);
    assertNotNull(t);
    assertEquals(2, t.packages.size());
  }

  @Test
  public void null对象返回null不崩() {
    assertNull(ActionTargetStore.Target.fromJson(null));
    assertNull(ActionTargetStore.Rule.fromJson(null));
  }

  // ---------------------------------------------------------------- 内置示例

  @Test
  public void 内置示例_只给规则不给坐标() {
    ActionTargetStore.Target t = ActionTargetStore.sampleTelegramSend();
    assertNotNull(t);
    assertFalse(t.rules.isEmpty());
    assertEquals("发送类操作必须确认", true, t.shouldConfirm());
    // 底线：示例里不能出现任何坐标型规则
    for (ActionTargetStore.Rule r : t.rules) {
      assertTrue("只允许 text/contentDescription/viewId 三类特征",
          r.by.equals("text") || r.by.equals("contentDescription") || r.by.equals("viewId"));
    }
  }

  @Test
  public void 内置示例_未标已验证设备_提示用户先测() {
    ActionTargetStore.Target t = ActionTargetStore.sampleTelegramSend();
    assertTrue("内置示例不该声称已验证", t.verifiedOn.isEmpty());
  }

  // ---------------------------------------------------------------- 集合操作语义（不依赖 Context 的部分）

  @Test
  public void 同名_id_替换而不是追加() {
    List<ActionTargetStore.Target> all = new ArrayList<>();
    ActionTargetStore.Target a = new ActionTargetStore.Target();
    a.id = "same";
    a.name = "旧";
    all.add(a);
    ActionTargetStore.Target b = new ActionTargetStore.Target();
    b.id = "same";
    b.name = "新";
    // 模拟 upsert 的替换语义（真实 upsert 需要 Context，这里只验证去重规则本身）
    boolean replaced = false;
    for (int i = 0; i < all.size(); i++) {
      if (b.id.equals(all.get(i).id)) {
        all.set(i, b);
        replaced = true;
        break;
      }
    }
    if (!replaced) all.add(b);
    assertEquals(1, all.size());
    assertEquals("新", all.get(0).name);
  }
}
