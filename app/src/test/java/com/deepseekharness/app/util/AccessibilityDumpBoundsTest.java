package com.deepseekharness.app.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link AccessibilityDumpBounds} 单元测试（Phase 14 阶段 5 新增，P1）。
 *
 * <p><b>为什么补这个</b>：{@code DshaAccessibilityService.dumpNode} 用它决定
 * "这个节点的 bounds 要不要输出"。判错有两种后果：
 * <ul>
 *   <li>该输出的元素被丢掉 → 模型看不到按钮 → 点不中；</li>
 *   <li>反向 bounds（right &lt; left / bottom &lt; top）的噪音被输出 → 模型对着
 *       不存在的区域瞎指挥。</li>
 * </ul>
 * 而这是一个**已经抽成纯函数、公开、零依赖**的方法，却一直是零测试 ——
 * 是整个项目里唯一漏网的已抽取纯逻辑。
 *
 * <p>类注释写明"Accessibility text can have zero area; only reversed bounds are noise"，
 * 本测试把这句话钉成断言。
 */
public class AccessibilityDumpBoundsTest {

  @Test
  public void 正常bounds算有序() {
    assertTrue("左上到右下", AccessibilityDumpBounds.ordered(0, 0, 100, 200));
    assertTrue("中间一段", AccessibilityDumpBounds.ordered(50, 60, 150, 260));
    assertTrue("负起点也合法", AccessibilityDumpBounds.ordered(-10, -20, 30, 40));
  }

  @Test
  public void 零面积算有序_这是明确允许的() {
    // 类注释：Accessibility text can have zero area —— 宽或高为 0 是允许的
    assertTrue("零宽度", AccessibilityDumpBounds.ordered(10, 20, 10, 200));
    assertTrue("零高度", AccessibilityDumpBounds.ordered(10, 20, 100, 20));
    assertTrue("零宽零高（一个点）", AccessibilityDumpBounds.ordered(5, 5, 5, 5));
  }

  @Test
  public void 反向bounds算噪音_必须判为无序() {
    assertFalse("右 < 左", AccessibilityDumpBounds.ordered(100, 0, 50, 200));
    assertFalse("下 < 上", AccessibilityDumpBounds.ordered(0, 200, 100, 50));
    assertFalse("两个方向都反", AccessibilityDumpBounds.ordered(100, 200, 50, 50));
  }

  @Test
  public void 相等边界算有序_不是反向() {
    // 只有严格反向才算噪音；相等是零面积，允许
    assertTrue("左右相等", AccessibilityDumpBounds.ordered(30, 0, 30, 100));
    assertTrue("上下相等", AccessibilityDumpBounds.ordered(0, 40, 100, 40));
  }

  @Test
  public void 极端值不抛() {
    // 屏幕坐标的边界：全 0、全负数、极大值
    AccessibilityDumpBounds.ordered(0, 0, 0, 0);
    AccessibilityDumpBounds.ordered(-1, -1, -1, -1);
    AccessibilityDumpBounds.ordered(Integer.MIN_VALUE, Integer.MIN_VALUE,
        Integer.MAX_VALUE, Integer.MAX_VALUE);
    AccessibilityDumpBounds.ordered(Integer.MAX_VALUE, Integer.MAX_VALUE,
        Integer.MIN_VALUE, Integer.MIN_VALUE);
  }

  @Test
  public void 全负数且反向仍判无序() {
    // 负屏幕坐标（多屏/虚拟屏场景）下，判据仍然是 right>=left && bottom>=top
    assertFalse(AccessibilityDumpBounds.ordered(0, 0, -100, -100));
    assertTrue(AccessibilityDumpBounds.ordered(-100, -100, 0, 0));
  }
}
