package com.deepseekharness.app.assistant;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 统一配置读写层（2026-10-11 第二阶段 P0-1）。
 *
 * <p><b>为什么要重做</b>：第一阶段的配置读取**第一优先级是 `/sdcard/dsh/*.json`**，
 * 而 Android 11+ 读那里需要 `MANAGE_EXTERNAL_STORAGE` —— 这个权限系统不给普通 App 弹框，
 * **只能靠电脑 ADB 授予**。结果：普通用户装完 APK → 读不到配置 → 联网问答和语音唤醒全废。
 * 这与"大众化"直接冲突。
 *
 * <p><b>新方案：内部存储优先</b>
 * <pre>
 *   1) 应用内部  filesDir/dsha/&lt;name&gt;        ← 零权限，任何设备都能读写【主存储】
 *   2) 旧位置    /sdcard/dsh/&lt;name&gt;          ← 兼容既有用户；读到就【迁移】进内部
 * </pre>
 *
 * <p><b>迁移纪律（用户明确要求"不要默默覆盖已有用户数据"）</b>：
 * <ul>
 *   <li>只在「内部<b>不存在</b> 且 旧位置<b>存在</b>」时迁移 —— 单向、一次性。
 *   <li>内部已存在时**绝不**用旧位置覆盖（否则用户在新版里改的配置会被老文件吃掉）。
 *   <li>迁移后**不删**旧文件（留作回滚与取证；用户自己愿意删是他的事）。
 * </ul>
 *
 * <p><b>可测性设计</b>：所有"决策逻辑"都是<b>纯函数</b>（{@link #decide}、
 * {@link #atomicWrite}），不碰 Android API，可直接在 JVM 单元测试里覆盖；
 * 只有最外层的 {@code Context} 相关方法才依赖 Android。
 */
public final class ConfigStoreLite {

  private static final String TAG = "DSHA/Config";

  /** 应用内部配置目录名（放在 filesDir 下）。 */
  static final String INTERNAL_DIR = "dsha";

  /** 旧的外部目录（只读兼容用，不再作为主存储）。 */
  static final String LEGACY_DIR = "/sdcard/dsh";

  /** 配置来源。 */
  public enum Source {
    /** 应用内部存储（正常路径）。 */
    INTERNAL,
    /** 从旧的外部位置迁移而来（本次读到的就是它）。 */
    MIGRATED_FROM_LEGACY,
    /** 都没有，用的是内置默认值。 */
    DEFAULTS
  }

  /** 一次"该从哪读"的决策结果。 */
  public static final class Decision {
    public final Source source;
    public final String reason;

    Decision(Source source, String reason) {
      this.source = source;
      this.reason = reason == null ? "" : reason;
    }

    @Override
    public String toString() {
      return source + "(" + reason + ")";
    }
  }

  private ConfigStoreLite() {}

  // ================================================================
  // 纯逻辑（可在 JVM 单元测试里直接跑，无需 Android）
  // ================================================================

  /**
   * 决定配置该从哪读。**纯函数** —— 这是整个迁移策略的核心，
   * 也是单元测试的主要目标。
   *
   * @param internalExists   应用内部配置是否存在
   * @param legacyExists     旧外部位置配置是否存在
   * @param legacyReadable   旧外部位置是否读得到（Android 11+ 没宽存储权限时为 false）
   */
  public static Decision decide(boolean internalExists, boolean legacyExists,
      boolean legacyReadable) {
    if (internalExists) {
      // 内部有 → 一律以内部为准。**绝不被旧文件覆盖**（这是"不默默覆盖用户数据"的关键）
      return new Decision(Source.INTERNAL, "内部配置存在");
    }
    if (legacyExists && legacyReadable) {
      // 内部没有、旧的有且读得到 → 迁移
      return new Decision(Source.MIGRATED_FROM_LEGACY, "内部无配置，从旧位置迁移");
    }
    if (legacyExists && !legacyReadable) {
      // 旧的有但读不到 → 不能假装读到了，退默认值并让上层提示用户
      return new Decision(Source.DEFAULTS, "旧位置有配置但读不到（缺少存储权限）");
    }
    return new Decision(Source.DEFAULTS, "没有任何配置，用内置默认值");
  }

  /**
   * 原子写入：先写同目录下的临时文件，再 rename 覆盖目标。
   *
   * <p>为什么必须这样：直接覆盖写如果中途断电/被杀，会留下一个**半截的 JSON**，
   * 下次启动解析失败 → 用户的配置就"没了"。同目录 rename 在 POSIX 语义下是原子的
   * （要么旧内容、要么新内容，不会出现半截）。
   *
   * @return true = 写入成功
   */
  public static boolean atomicWrite(File target, String content) throws IOException {
    if (target == null) throw new IOException("目标为空");
    File dir = target.getParentFile();
    if (dir != null && !dir.exists() && !dir.mkdirs()) {
      throw new IOException("建目录失败: " + dir);
    }
    File tmp = new File(dir, target.getName() + ".tmp");
    try (FileOutputStream out = new FileOutputStream(tmp)) {
      out.write(content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8));
      out.flush();
      // 尽量把数据刷到磁盘再 rename，缩小"rename 成功但内容还在缓存里"的窗口
      try {
        out.getFD().sync();
      } catch (Throwable ignored) {
        // 某些文件系统/测试桩不支持 sync，忽略即可（不影响 rename 的原子性）
      }
    }
    // 目标已存在时 rename 会替换它；Windows 上 rename 到已存在文件会失败，先尽力删
    if (target.exists() && !target.delete()) {
      // 删不掉就退回"直接写"（至少能写进去，虽然失去了原子性）
      try (FileOutputStream out = new FileOutputStream(target)) {
        out.write(content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8));
      }
      try {
        tmp.delete();
      } catch (Throwable ignored) {
      }
      return true;
    }
    if (!tmp.renameTo(target)) {
      tmp.delete();
      throw new IOException("rename 失败: " + tmp + " -> " + target);
    }
    return true;
  }

  /** 内部配置文件对象（纯函数，便于测试传入不同的 base 目录）。 */
  public static File internalFile(File filesDir, String name) {
    return new File(new File(filesDir, INTERNAL_DIR), name);
  }

  /** 旧位置配置文件对象。 */
  public static File legacyFile(String name) {
    return new File(LEGACY_DIR, name);
  }

  // ================================================================
  // Android 相关（依赖 Context）
  // ================================================================

  /** 读配置文本。顺序见 {@link #decide}；读不到返回 null（调用方走默认值）。 */
  public static String read(Context ctx, String name) {
    if (ctx == null || name == null) return null;

    File internal = internalFile(ctx.getFilesDir(), name);
    File legacy = legacyFile(name);
    boolean legacyExists = legacy.isFile();
    boolean legacyReadable = false;
    if (legacyExists && !internal.isFile()) {
      // 只有真要去读它时才探测可读性（避免每次都读旧文件）
      legacyReadable = probeReadable(legacy);
    }

    Decision d = decide(internal.isFile(), legacyExists, legacyReadable);
    android.util.Log.i(TAG, name + " 来源决策: " + d);

    switch (d.source) {
      case INTERNAL:
        return readFile(internal);
      case MIGRATED_FROM_LEGACY:
        String text = readFile(legacy);
        if (text != null) {
          // 迁移：把旧内容写进内部，之后就以内部为准。旧文件**保留**（可回滚/取证）
          boolean ok = write(ctx, name, text);
          android.util.Log.i(TAG, "已从旧位置迁移 " + name + " 到内部存储: " + ok
              + "（旧文件保留未删）");
        }
        return text;
      default:
        return null;
    }
  }

  /** 写配置到内部存储（原子）。失败返回 false，调用方必须提示用户而不是假装成功。 */
  public static boolean write(Context ctx, String name, String content) {
    if (ctx == null || name == null) return false;
    try {
      atomicWrite(internalFile(ctx.getFilesDir(), name), content);
      return true;
    } catch (Throwable t) {
      android.util.Log.w(TAG, "写内部配置失败: " + t);
      return false;
    }
  }

  /** 内部配置是否存在。 */
  static boolean existsInternal(Context ctx, String name) {
    return ctx != null && name != null && internalFile(ctx.getFilesDir(), name).isFile();
  }

  /** 旧位置是否存在（用于给用户提示"你有旧配置可以迁移"）。 */
  static boolean existsLegacy(String name) {
    return name != null && legacyFile(name).isFile();
  }

  /**
   * 首次启动时确保配置就位：没有就写一份默认的。
   *
   * @return true = 本次创建了默认配置
   */
  static boolean ensureDefault(Context ctx, String name, String defaultJson) {
    if (ctx == null || name == null || defaultJson == null) return false;
    if (existsInternal(ctx, name)) return false;
    boolean ok = write(ctx, name, defaultJson);
    android.util.Log.i(TAG, "创建默认配置 " + name + ": " + ok);
    return ok;
  }

  /** 诊断信息（给权限页/日志用）。 */
  static String describe(Context ctx, String name) {
    File in = ctx == null ? null : internalFile(ctx.getFilesDir(), name);
    File lg = legacyFile(name);
    return name
        + " · 内部=" + (in != null && in.isFile() ? "有" : "无")
        + " · 旧位置=" + (lg.isFile() ? "有" : "无")
        + " · 可读旧位置=" + (lg.isFile() && probeReadable(lg));
  }

  // ================================================================
  // 私有
  // ================================================================

  /** 真去读一个字节来判断可读性（Android 11+ 无权限时会抛 SecurityException/IOException）。 */
  private static boolean probeReadable(File f) {
    try (InputStream in = new FileInputStream(f)) {
      return in.read() >= -1; // 能打开就算可读（空文件返回 -1 也算可读）
    } catch (Throwable t) {
      return false;
    }
  }

  private static String readFile(File f) {
    try (InputStream in = new FileInputStream(f)) {
      ByteArrayOutputStream buf = new ByteArrayOutputStream();
      byte[] chunk = new byte[8192];
      int n;
      while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
      return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    } catch (Throwable t) {
      android.util.Log.w(TAG, "读 " + f + " 失败: " + t);
      return null;
    }
  }
}
