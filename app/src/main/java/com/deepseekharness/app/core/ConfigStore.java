package com.deepseekharness.app.core;

import android.content.Context;

import com.deepseekharness.app.util.CredentialRead;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 轻量版配置入口（DSHA-lite）。
 *
 * <p>原 DSHA 的 ConfigStore 依赖 KeyVault（Keystore 加密）/ Constants / PreferenceValue /
 * 备份体系 / 容器运行时选择，那些全是 rootfs 那套重型能力用的，助手根本不碰。
 * 这里只保留助手真正用到的一件事：拿到一个能发请求的 chat API Key。
 *
 * <p>Key 的来源只有一处：{@code /sdcard/dsh/assistant-voice.json} 的 {@code chatApiKey}
 * （与 {@code AssistantConfig} 读的是同一个文件、同一个字段，保证两处不会打架）。
 * 读不到 / 解析失败一律优雅降级为「未配置」，绝不抛异常把面板崩掉。
 */
public class ConfigStore {

  private static final String CONFIG_PATH = "/sdcard/dsh/assistant-voice.json";

  private final Context appContext;

  public ConfigStore(Context ctx) {
    this.appContext = ctx == null ? null : ctx.getApplicationContext();
  }

  /**
   * 读助手用的 chat API Key。
   *
   * @return 有值 = {@code AVAILABLE}；文件不存在/没配/读不了 = {@code NOT_CONFIGURED}。
   */
  public CredentialRead readApiKey() {
    String key = readChatApiKey();
    if (key == null || key.trim().isEmpty()) return CredentialRead.missing();
    return CredentialRead.available(key.trim());
  }

  /** 兼容旧调用：同 {@link #readApiKey()}。 */
  public String getApiKey() {
    return readApiKey().requireValue();
  }

  public static String credentialMessage(CredentialRead read) {
    if (read == null) return "当前设备无法读取已保存的 API Key。";
    if (read.state == CredentialRead.State.TEMPORARILY_UNAVAILABLE)
      return "API Key 暂时无法读取，请解锁设备后重试。";
    if (read.state == CredentialRead.State.NOT_CONFIGURED)
      return "DSHA 还没配置 API Key（改 /sdcard/dsh/assistant-voice.json 的 chatApiKey）。";
    return "当前设备无法读取已保存的 API Key，请重试或重新输入。";
  }

  private String readChatApiKey() {
    // 1) 应用内部存储（零权限，主路径）—— 2026-10-11 第二阶段 P0-1 改造。
    //    原来第一优先级是 /sdcard/dsh/，那需要 MANAGE_EXTERNAL_STORAGE，
    //    而该权限系统不给普通 App 弹框、只能 ADB 授 → 普通用户读不到配置。
    //    ConfigStoreLite 会在内部为空时，从旧位置做一次性迁移，且不删旧文件。
    if (appContext != null) {
      try {
        String internal =
            com.deepseekharness.app.assistant.ConfigStoreLite.read(
                appContext, "assistant-voice.json");
        String v = extractKey(internal);
        if (v != null) return v;
      } catch (Throwable ignored) {
      }
    }
    // 2) 兼容：旧的外部配置文件（有宽存储权限的老用户仍能读到）
    String fromSdcard = fromFile(new File(CONFIG_PATH));
    if (fromSdcard != null) return fromSdcard;
    // 3) 应用外部私有目录（没有「所有文件访问」权限时用户也能放一份）
    if (appContext != null) {
      File ext = null;
      try {
        ext = appContext.getExternalFilesDir(null);
      } catch (Throwable ignored) {
      }
      if (ext != null) {
        String fromExt = fromFile(new File(ext, "assistant-voice.json"));
        if (fromExt != null) return fromExt;
      }
    }
    return null;
  }

  /** 从一段 JSON 文本里取 chatApiKey；文本为空/解析失败/字段为空都返回 null（不抛）。 */
  private static String extractKey(String json) {
    if (json == null || json.trim().isEmpty()) return null;
    try {
      String v = new JSONObject(json).optString("chatApiKey", "");
      return v == null || v.trim().isEmpty() ? null : v.trim();
    } catch (Throwable ignored) {
      return null;
    }
  }

  /** 读文件并取 chatApiKey；文件不存在/解析失败/字段为空都返回 null（不抛）。 */
  private static String fromFile(File f) {
    if (f == null || !f.isFile()) return null;
    try (InputStream in = new FileInputStream(f)) {
      ByteArrayOutputStream buf = new ByteArrayOutputStream();
      byte[] chunk = new byte[4096];
      int n;
      while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
      JSONObject o = new JSONObject(new String(buf.toByteArray(), StandardCharsets.UTF_8));
      String v = o.optString("chatApiKey", "");
      return v == null || v.trim().isEmpty() ? null : v.trim();
    } catch (Throwable ignored) {
      return null;
    }
  }
}
