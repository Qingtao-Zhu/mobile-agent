package com.deepseekharness.app.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * {@link ConfigStoreLite} 的纯逻辑测试（JVM 单元测试，不需要设备/模拟器）。
 *
 * <p>覆盖用户要求（第二阶段 P0-1 / P0-2）：
 * <ul>
 *   <li>配置来源决策的四种组合
 *   <li><b>内部已存在时绝不被旧位置覆盖</b>（"不要默默覆盖已有用户数据"）
 *   <li>原子写入：正常写、覆盖写、父目录不存在时自建
 *   <li>写入失败要抛出（而不是静默成功）
 * </ul>
 */
public class ConfigStoreLiteTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  // ---------------------------------------------------------------- 来源决策

  @Test
  public void 内部有配置时_永远选内部() {
    ConfigStoreLite.Decision d = ConfigStoreLite.decide(true, true, true);
    assertEquals(ConfigStoreLite.Source.INTERNAL, d.source);
  }

  @Test
  public void 内部有配置时_即使旧位置存在也不迁移() {
    // 这是最容易被写错的一条：如果这里判成"迁移"，用户在新版里改的配置会被旧文件吃掉
    ConfigStoreLite.Decision d = ConfigStoreLite.decide(true, true, true);
    assertFalse("内部已有时不应迁移", d.source == ConfigStoreLite.Source.MIGRATED_FROM_LEGACY);
  }

  @Test
  public void 内部没有_旧位置有且可读_应迁移() {
    ConfigStoreLite.Decision d = ConfigStoreLite.decide(false, true, true);
    assertEquals(ConfigStoreLite.Source.MIGRATED_FROM_LEGACY, d.source);
  }

  @Test
  public void 内部没有_旧位置有但读不到_退回默认而不是假装读到() {
    // Android 11+ 没宽存储权限时就是这种状态：文件在，但读不到
    ConfigStoreLite.Decision d = ConfigStoreLite.decide(false, true, false);
    assertEquals(ConfigStoreLite.Source.DEFAULTS, d.source);
    assertTrue("应说明原因", d.reason.contains("读不到"));
  }

  @Test
  public void 都没有_用默认() {
    ConfigStoreLite.Decision d = ConfigStoreLite.decide(false, false, false);
    assertEquals(ConfigStoreLite.Source.DEFAULTS, d.source);
  }

  @Test
  public void 决策一定带原因说明() {
    assertFalse(ConfigStoreLite.decide(true, false, false).reason.isEmpty());
    assertFalse(ConfigStoreLite.decide(false, true, true).reason.isEmpty());
    assertFalse(ConfigStoreLite.decide(false, false, false).reason.isEmpty());
  }

  // ---------------------------------------------------------------- 原子写入

  @Test
  public void 原子写入_新文件() throws IOException {
    File target = new File(tmp.getRoot(), "cfg.json");
    assertTrue(ConfigStoreLite.atomicWrite(target, "{\"a\":1}"));
    assertEquals("{\"a\":1}", read(target));
  }

  @Test
  public void 原子写入_覆盖已有文件() throws IOException {
    File target = new File(tmp.getRoot(), "cfg.json");
    ConfigStoreLite.atomicWrite(target, "old");
    ConfigStoreLite.atomicWrite(target, "new");
    assertEquals("new", read(target));
  }

  @Test
  public void 原子写入_父目录不存在时自建() throws IOException {
    File target = new File(new File(tmp.getRoot(), "a/b/c"), "cfg.json");
    assertTrue(ConfigStoreLite.atomicWrite(target, "x"));
    assertTrue(target.isFile());
  }

  @Test
  public void 原子写入_不留临时文件() throws IOException {
    File target = new File(tmp.getRoot(), "cfg.json");
    ConfigStoreLite.atomicWrite(target, "data");
    File tmpFile = new File(tmp.getRoot(), "cfg.json.tmp");
    assertFalse("临时文件应当已被 rename 掉", tmpFile.exists());
  }

  @Test
  public void 原子写入_写入失败要抛而不是静默成功() {
    // 目标是一个"父级是文件"的非法路径 → 必须抛，调用方才知道要提示用户
    try {
      File bad = new File(tmp.getRoot(), "afile");
      ConfigStoreLite.atomicWrite(bad, "x"); // 先把它变成一个文件
      File child = new File(bad, "child.json"); // 再往里写 → 不可能成功
      ConfigStoreLite.atomicWrite(child, "y");
      org.junit.Assert.fail("应当抛 IOException");
    } catch (IOException expected) {
      assertNotNull(expected.getMessage());
    }
  }

  // ---------------------------------------------------------------- 路径

  @Test
  public void 内部路径落在_dsha_子目录() {
    File f = ConfigStoreLite.internalFile(new File("/tmp/files"), "assistant-voice.json");
    assertTrue(f.getPath().replace('\\', '/').contains("/dsha/assistant-voice.json"));
  }

  @Test
  public void 旧位置路径保持兼容() {
    assertTrue(ConfigStoreLite.legacyFile("x.json").getPath().replace('\\', '/')
        .endsWith("/sdcard/dsh/x.json"));
  }

  // ---------------------------------------------------------------- 辅助

  private static String read(File f) throws IOException {
    try (InputStream in = new FileInputStream(f)) {
      byte[] buf = new byte[(int) f.length()];
      int n = in.read(buf);
      return new String(buf, 0, Math.max(0, n), StandardCharsets.UTF_8);
    }
  }
}
