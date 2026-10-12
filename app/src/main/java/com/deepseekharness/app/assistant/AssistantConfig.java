package com.deepseekharness.app.assistant;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 助手面板的「外部可调配置」。
 *
 * <p>面板默认用 DSHA 自己设置里那份 DeepSeek API Key（{@code ConfigStore}，Keystore 加密），
 * 但 key 失效 / 换供应商 / 语音识别要接别家时，不想每次都重编 APK，
 * 所以所有可调项都从下面这个 JSON 文件读（按顺序取第一个存在的）：
 *
 * <pre>
 * 1) /sdcard/dsh/assistant-voice.json          ← 手机存储里，用户/ADB 可直接改
 * 2) Android/data/com.dsh.client/files/assistant-voice.json
 * 3) SharedPreferences("assistant_config").voice_json
 * </pre>
 *
 * 文件内容（字段全部可选，缺省走内置值）：
 * <pre>
 * {
 *   "chatEndpoint": "https://api.deepseek.com/chat/completions",
 *   "chatModel":    "deepseek-chat",
 *   "chatApiKey":   "",                        // 留空 = 用 DSHA 设置里那份 Key
 *   "asrEndpoint":  "https://api.groq.com/openai/v1/audio/transcriptions",
 *   "asrModel":     "whisper-large-v3-turbo",
 *   "asrApiKey":    "gsk_...",
 *   "asrLanguage":  "zh",
 *   "ttsVoice":     "",        // 留空 = 自动挑本机质量最高的中文音色
 *   "ttsRate":      0.98,      // 朗读语速 0.5~2.0
 *   "ttsPitch":     0.96,      // 朗读音调 0.5~2.0
 *   "historyTurns": 20,        // 多轮上下文留几轮（用户挂的 DeepSeek 是百万上下文，放大很便宜）
 *   "screenChars":  12000      // 一次喂给模型的屏幕文本上限（读屏本身最多 12000 字）
 * }
 * </pre>
 *
 * 隐私：这个文件里可能有第三方 Key，面板只把它读进内存用于自己的网络请求，不写日志、不外传。
 */
final class AssistantConfig {

  static final String CHAT_ENDPOINT_DEFAULT = "https://api.deepseek.com/chat/completions";
  static final String CHAT_MODEL_DEFAULT = "deepseek-chat";
  static final String ASR_ENDPOINT_DEFAULT =
      "https://api.groq.com/openai/v1/audio/transcriptions";
  static final String ASR_MODEL_DEFAULT = "whisper-large-v3-turbo";
  static final String ASR_LANGUAGE_DEFAULT = "zh";
  /** 视觉兜底模型：微信等应用屏蔽无障碍时，截屏给它看图回答（免费档）。 */
  static final String CHAT_VISION_MODEL_DEFAULT = "glm-4v-flash";

  /** 朗读音色名默认值：空 = 自动挑本机「质量最高 + 已下载」的中文音色。 */
  static final String TTS_VOICE_DEFAULT = "";
  /** 朗读语速默认值（1 = 正常；Siri 那种偏慢一点更有「说话感」）。 */
  static final float TTS_RATE_DEFAULT = 0.98f;
  /** 朗读音调默认值（1 = 正常；略降一点更自然，机器味更淡）。 */
  static final float TTS_PITCH_DEFAULT = 0.96f;
  /** 多轮上下文默认留几轮（用户挂 DeepSeek 百万上下文，这不是瓶颈，宁可多留）。 */
  static final int HISTORY_TURNS_DEFAULT = 20;
  /** 屏幕文本喂模型的默认上限（读屏 dump 最多 12000 字，这里再留一倍余量给拼装开销）。 */
  static final int SCREEN_CHARS_DEFAULT = 12000;

  static final String FILE_SUFFIX = "assistant-voice.json";
  static final String PREFS = "assistant_config";
  static final String PREF_KEY = "voice_json";
  static final String SDCARD_PATH = "/sdcard/dsh/" + FILE_SUFFIX;

  final String chatEndpoint;
  final String chatModel;
  final String chatApiKey;
  final String asrEndpoint;
  final String asrModel;
  final String asrApiKey;
  final String asrLanguage;
  /** 视觉兜底模型（看图回答用），可由 json 的 "chatVisionModel" 覆盖。 */
  final String chatVisionModel;
  /** 敏感应用白名单追加项（{@code AssistantGuard} 用）；微信已在内置白名单里。 */
  final java.util.List<String> allowedPackages;
  /** 朗读音色名（TTS Voice.name）；空 = 自动挑本机质量最高的中文音色。 */
  final String ttsVoice;
  /** 朗读语速（0.5~2.0，1 = 正常）。 */
  final float ttsRate;
  /** 朗读音调（0.5~2.0，1 = 正常）。 */
  final float ttsPitch;
  /**
   * 多轮上下文保留几轮（一轮 = 一问一答）。
   *
   * <p>2026-10-07 从 4 提到 20：用户挂的 DeepSeek 是百万上下文，
   * 4 轮短到"你上句问的啥它不记得了"，聊三句就开始答非所问。
   */
  final int historyTurns;
  /**
   * 一次喂给模型的屏幕文本上限。
   *
   * <p>2026-10-07 从 6000 提到 12000（读屏 dump 本身最多就 12000 字，再大也拿不到更多），
   * 长页面（长文、长聊天记录）现在能整段读进去而不是被截半句。
   */
  final int screenChars;
  /** 配置来源描述，面板出错时告诉用户"去改哪个文件"。 */
  final String source;

  private AssistantConfig(
      String chatEndpoint,
      String chatModel,
      String chatApiKey,
      String asrEndpoint,
      String asrModel,
      String asrApiKey,
      String asrLanguage,
      String chatVisionModel,
      java.util.List<String> allowedPackages,
      String ttsVoice,
      float ttsRate,
      float ttsPitch,
      int historyTurns,
      int screenChars,
      String source) {
    this.chatEndpoint = chatEndpoint;
    this.chatModel = chatModel;
    this.chatApiKey = chatApiKey;
    this.asrEndpoint = asrEndpoint;
    this.asrModel = asrModel;
    this.asrApiKey = asrApiKey;
    this.asrLanguage = asrLanguage;
    this.chatVisionModel =
        chatVisionModel == null || chatVisionModel.trim().isEmpty()
            ? CHAT_VISION_MODEL_DEFAULT
            : chatVisionModel.trim();
    this.allowedPackages =
        allowedPackages == null
            ? java.util.Collections.<String>emptyList()
            : java.util.Collections.unmodifiableList(allowedPackages);
    this.ttsVoice = ttsVoice == null ? "" : ttsVoice.trim();
    this.ttsRate = clamp(ttsRate, 0.5f, 2.0f, TTS_RATE_DEFAULT);
    this.ttsPitch = clamp(ttsPitch, 0.5f, 2.0f, TTS_PITCH_DEFAULT);
    this.historyTurns = clampInt(historyTurns, 0, 200, HISTORY_TURNS_DEFAULT);
    this.screenChars = clampInt(screenChars, 1000, 24000, SCREEN_CHARS_DEFAULT);
    this.source = source;
  }

  private static float clamp(float v, float lo, float hi, float fallback) {
    if (Float.isNaN(v) || v < lo || v > hi) return fallback;
    return v;
  }

  private static int clampInt(int v, int lo, int hi, int fallback) {
    return v < lo || v > hi || v == 0 ? fallback : v;
  }

  private static AssistantConfig defaults() {
    return new AssistantConfig(
        CHAT_ENDPOINT_DEFAULT,
        CHAT_MODEL_DEFAULT,
        "",
        ASR_ENDPOINT_DEFAULT,
        ASR_MODEL_DEFAULT,
        "",
        ASR_LANGUAGE_DEFAULT,
        null,
        null,
        TTS_VOICE_DEFAULT,
        TTS_RATE_DEFAULT,
        TTS_PITCH_DEFAULT,
        HISTORY_TURNS_DEFAULT,
        SCREEN_CHARS_DEFAULT,
        "内置默认");
  }

  /**
   * 读取配置；读不到就返回内置默认（source 会写明）。
   *
   * <p><b>2026-10-11 第二阶段 P0-1 改造</b>：读取顺序从"外部 sdcard 优先"改为
   * <b>应用内部存储优先</b>。
   *
   * <p>原因是原来的第一优先级 `/sdcard/dsh/assistant-voice.json` 在 Android 11+
   * 需要 {@code MANAGE_EXTERNAL_STORAGE}，而这个权限**系统不给普通 App 弹框、
   * 只能用电脑 ADB 授** —— 普通用户装完读不到配置，联网问答和语音唤醒全废。
   *
   * <p>现在的顺序：
   * <ol>
   *   <li><b>内部存储</b> {@code filesDir/dsha/assistant-voice.json}（零权限，主存储）
   *       —— 若内部没有而旧位置有，{@link ConfigStoreLite} 会<b>单向迁移</b>过来
   *   <li>老的 {@code getExternalFilesDir}（应用专属外部目录，同样不需要宽权限，保留兼容）
   *   <li>老的 SharedPreferences（兼容更早的版本）
   *   <li>内置默认
   * </ol>
   */
  static AssistantConfig load(Context ctx) {
    String json = null;
    String source = "内置默认";

    // 1) 应用内部存储（含从 /sdcard/dsh 的单向迁移）
    try {
      json = ConfigStoreLite.read(ctx, FILE_SUFFIX);
      if (json != null) source = "内部存储(filesDir/dsha)";
    } catch (Throwable t) {
      android.util.Log.w("DSHA/Config", "读内部配置失败: " + t);
    }

    // 2) 兼容：应用专属外部目录（不需宽权限）
    if (json == null) {
      try {
        File ext = ctx.getExternalFilesDir(null);
        File f = ext == null ? null : new File(ext, FILE_SUFFIX);
        if (f != null && f.isFile()) {
          json = read(f);
          source = f.getAbsolutePath();
        }
      } catch (Throwable ignored) {
      }
    }
    if (json == null) {
      try {
        String stored = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_KEY, null);
        if (stored != null && !stored.trim().isEmpty()) {
          json = stored;
          source = "SharedPreferences(" + PREFS + ")";
        }
      } catch (Throwable ignored) {
      }
    }
    if (json == null) {
      // 首次启动：把一份默认配置落到内部存储，用户有个可编辑的底子
      //（用户要求"首次启动时自动创建必要的默认配置"）。
      // 注意：这里只写"空壳模板"，**不预填任何密钥**。
      try {
        boolean created = ConfigStoreLite.ensureDefault(ctx, FILE_SUFFIX, defaultJsonTemplate());
        if (created) android.util.Log.i("DSHA/Config", "已创建默认配置文件");
      } catch (Throwable ignored) {
      }
      return defaults();
    }
    try {
      return parse(json, source);
    } catch (Throwable t) {
      // JSON 坏了：不静默吞、也不删用户文件（留着让人能看出是哪写坏的），
      // 退回内置默认并记明确日志。上层会看到 source 仍是原来源，便于定位。
      android.util.Log.w("DSHA/Config",
          "配置解析失败（来源 " + source + "），已退回内置默认；文件未改动: " + t);
      return defaults();
    }
  }

  /**
   * 默认配置模板：只包含"填了就能用"的最小字段，值一律留空。
   *
   * <p><b>绝不预填任何真实 Key</b> —— 用户明确要求"不要将个人密钥写死在 APK / 源码里"。
   * 这份模板的作用只是让用户打开文件时知道该填什么。
   */
  static String defaultJsonTemplate() {
    return "{\n"
        + "  \"_说明\": \"填好下面几项即可使用。改完保存，助手会自动读取。\",\n"
        + "  \"chatEndpoint\": \"\",\n"
        + "  \"chatModel\": \"\",\n"
        + "  \"chatApiKey\": \"\",\n"
        + "  \"asrEndpoint\": \"\",\n"
        + "  \"asrModel\": \"\",\n"
        + "  \"asrApiKey\": \"\",\n"
        + "  \"asrLanguage\": \"zh\"\n"
        + "}\n";
  }

  static AssistantConfig parse(String json, String source) throws Exception {
    JSONObject o = new JSONObject(json);
    java.util.List<String> allowed = new java.util.ArrayList<>();
    org.json.JSONArray arr = o.optJSONArray("allowedPackages");
    if (arr != null) {
      for (int i = 0; i < arr.length(); i++) {
        String v = arr.optString(i, "").trim();
        if (!v.isEmpty()) allowed.add(v);
      }
    }
    return new AssistantConfig(
        text(o, "chatEndpoint", CHAT_ENDPOINT_DEFAULT),
        text(o, "chatModel", CHAT_MODEL_DEFAULT),
        text(o, "chatApiKey", ""),
        text(o, "asrEndpoint", ASR_ENDPOINT_DEFAULT),
        text(o, "asrModel", ASR_MODEL_DEFAULT),
        text(o, "asrApiKey", ""),
        text(o, "asrLanguage", ASR_LANGUAGE_DEFAULT),
        o.optString("chatVisionModel", "").trim(),
        allowed,
        o.optString("ttsVoice", "").trim(),
        (float) o.optDouble("ttsRate", TTS_RATE_DEFAULT),
        (float) o.optDouble("ttsPitch", TTS_PITCH_DEFAULT),
        o.optInt("historyTurns", HISTORY_TURNS_DEFAULT),
        o.optInt("screenChars", SCREEN_CHARS_DEFAULT),
        source);
  }

  private static String text(JSONObject o, String key, String fallback) {
    String v = o.optString(key, "");
    return v == null || v.trim().isEmpty() ? fallback : v.trim();
  }

  /** 把一份配置模板写到手机存储（面板「语音」按钮发现没配时用它生成，方便用户照着手改）。 */
  static String template(AssistantConfig c) {
    return "{\n"
        + "  \"chatEndpoint\": \"" + c.chatEndpoint + "\",\n"
        + "  \"chatModel\": \"" + c.chatModel + "\",\n"
        + "  \"chatApiKey\": \"\",\n"
        + "  \"chatVisionModel\": \"" + c.chatVisionModel + "\",\n"
        + "  \"asrEndpoint\": \"" + c.asrEndpoint + "\",\n"
        + "  \"asrModel\": \"" + c.asrModel + "\",\n"
        + "  \"asrApiKey\": \"你自己的语音识别 Key\",\n"
        + "  \"asrLanguage\": \"zh\",\n"
        + "  \"ttsVoice\": \"\",\n"
        + "  \"ttsRate\": 1.0,\n"
        + "  \"ttsPitch\": 1.0,\n"
        + "  \"historyTurns\": 20,\n"
        + "  \"screenChars\": 12000,\n"
        + "  \"allowedPackages\": []\n"
        + "}\n";
  }

  private static String read(File f) throws Exception {
    try (InputStream in = new FileInputStream(f)) {
      ByteArrayOutputStream buf = new ByteArrayOutputStream();
      byte[] chunk = new byte[4096];
      int n;
      while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
      return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }
  }
}
