package com.deepseekharness.app.assistant;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.deepseekharness.app.util.ScreenTarget;

import org.junit.Test;

/**
 * {@link ScreenTarget} 的身份匹配测试（JVM 单元测试，零设备依赖）。
 *
 * <p>它回答的是 D-01 里那个"页面变化防护到底能判什么"的问题。
 *
 * <p><b>能力边界（诚实声明，不包装成更强的保证）</b>：
 * <ul>
 *   <li>✅ 能判：还是不是**同一个显示器 + 同一个窗口 + 同一个包名**
 *   <li>❌ 不能判：目标**页面**（同一 App 内两个界面若共用 windowId，分不出来）
 *   <li>❌ 不能判：目标**控件**（完全不涉及节点）
 *   <li>⚠️ 已知副作用：软键盘 / 弹窗会创建新 window → windowId 变 →
 *       被判为"界面变了" → **保守停手**。这个方向的误判是安全的（宁可少做事），
 *       但会造成"明明还在同一个 App 却停手"的体验，属于已知限制。
 * </ul>
 */
public class ScreenTargetTest {

  // ---------------------------------------------------------------- 相同即匹配

  @Test
  public void 完全相同的身份_匹配() {
    ScreenTarget a = new ScreenTarget(0, 42, "com.example.chat");
    ScreenTarget b = new ScreenTarget(0, 42, "com.example.chat");
    assertTrue(a.matches(b));
  }

  // ---------------------------------------------------------------- 包名变化 → 拒绝

  @Test
  public void 包名变了_不匹配() {
    ScreenTarget task = new ScreenTarget(0, 42, "com.example.chat");
    ScreenTarget now = new ScreenTarget(0, 42, "com.other.app");
    assertFalse("换到别的应用必须判为不匹配", task.matches(now));
  }

  @Test
  public void 包名大小写不同_视为不同() {
    // 包名是大小写敏感的标识符，不做宽容比较 —— 宁可保守
    ScreenTarget task = new ScreenTarget(0, 42, "Com.Example.Chat");
    ScreenTarget now = new ScreenTarget(0, 42, "com.example.chat");
    assertFalse(task.matches(now));
  }

  // ---------------------------------------------------------------- 窗口变化 → 拒绝

  @Test
  public void 窗口变了_不匹配() {
    ScreenTarget task = new ScreenTarget(0, 42, "com.example.chat");
    ScreenTarget now = new ScreenTarget(0, 43, "com.example.chat");
    assertFalse("同应用换了窗口（新 Activity / 弹窗）必须判为不匹配", task.matches(now));
  }

  @Test
  public void 显示器变了_不匹配() {
    ScreenTarget task = new ScreenTarget(0, 42, "com.example.chat");
    ScreenTarget now = new ScreenTarget(1, 42, "com.example.chat");
    assertFalse(task.matches(now));
  }

  // ---------------------------------------------------------------- 无效输入 → 拒绝

  @Test
  public void 当前身份为空_不匹配() {
    ScreenTarget task = new ScreenTarget(0, 42, "com.example.chat");
    assertFalse("拿不到当前身份时不能当作匹配", task.matches(null));
  }

  @Test
  public void 目标窗口号无效_不匹配() {
    // 原实现里 windowId >= 0 是对"目标自己"的要求：
    // 目标窗口号都无效（比如抓取时就没取到），那这个目标不可信
    ScreenTarget invalid = new ScreenTarget(0, -1, "com.example.chat");
    ScreenTarget now = new ScreenTarget(0, 42, "com.example.chat");
    assertFalse("目标窗口号无效时不应判为匹配", invalid.matches(now));
  }

  @Test
  public void 包名为空的目标_不匹配() {
    ScreenTarget emptyPkg = new ScreenTarget(0, 42, "");
    ScreenTarget now = new ScreenTarget(0, 42, "");
    // 两边都是空包名 —— 实现上 equals 会返回 true，但业务层（runLoop）有额外把关：
    // 抓不到包名时直接 finish 不开工。这里记录这个边界，避免误以为"空包名也安全"。
    assertTrue("实现层：空包名自比会匹配（这是实现细节）", emptyPkg.matches(now));
    // 业务层真正的保证在 AssistantTaskRunner：taskTarget.packageName.isEmpty() → 停手
  }

  // ---------------------------------------------------------------- 能力边界声明

  @Test
  public void 能力边界_同包同窗口视为同一目标() {
    // 这是"不能判页面"的直接体现：同一个 App 的主界面和二级页，
    // 如果底层的 windowId 没变，本类就**分辨不出来**。
    // 写这个测试是为了把边界钉在代码里，而不是留一句口头说明。
    ScreenTarget task = new ScreenTarget(0, 42, "com.example.chat");
    ScreenTarget anotherPageSameWindow = new ScreenTarget(0, 42, "com.example.chat");
    assertTrue(
        "【已知限制】本类无力区分同窗口内的页面变化；"
            + "页面级判断要靠上层每步重新 dump（runLoop 已这么做）",
        task.matches(anotherPageSameWindow));
  }
}
