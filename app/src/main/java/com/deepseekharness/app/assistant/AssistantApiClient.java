package com.deepseekharness.app.assistant;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * DeepSeek 在线补全的最小客户端：助手面板直连官方 API，不经本地 harness 回环。
 * 与项目既有网络代码保持一致，只用 HttpURLConnection（项目里没有 OkHttp）。
 */
final class AssistantApiClient {

  static final String ENDPOINT = AssistantConfig.CHAT_ENDPOINT_DEFAULT;
  static final String MODEL = AssistantConfig.CHAT_MODEL_DEFAULT;
  static final int MAX_TOKENS = 800;

  private AssistantApiClient() {}

  static final class Message {
    final String role;
    final String content;

    Message(String role, String content) {
      this.role = role;
      this.content = content;
    }
  }

  /**
   * 同步调用，调用方负责放到后台线程。返回 choices[0].message.content。
   *
   * <p>端点/模型都从 {@link AssistantConfig} 传进来：默认是 DeepSeek 官方，
   * 用户想换别家（或换模型）只要改那个 JSON，不用重编 APK。
   */
  static String chat(
      String endpoint, String model, String apiKey, List<Message> messages, int maxTokens)
      throws IOException, JSONException {
    JSONArray array = new JSONArray();
    for (Message m : messages)
      array.put(new JSONObject().put("role", m.role).put("content", m.content));
    return post(endpoint, apiKey, model, array, maxTokens);
  }

  /**
   * 带截图的视觉问答 —— 「微信等应用在 app 层屏蔽了无障碍节点」（2026-10-07 实测：QQ 同在
   * 华为保护名单却可读，微信自己反自动化），读不到字时改走「截屏 → 视觉模型看图」。
   * 图片用 data URI（OpenAI 兼容格式，已在 PC 上用 glm-4v-flash 实测通过）。
   *
   * @param imageB64 裸 base64 PNG（不带 data: 前缀）
   */
  static String chatVision(
      String endpoint,
      String model,
      String apiKey,
      String system,
      String user,
      String imageB64,
      int maxTokens)
      throws IOException, JSONException {
    JSONArray array = new JSONArray();
    if (system != null && !system.isEmpty())
      array.put(new JSONObject().put("role", "system").put("content", system));
    JSONArray content = new JSONArray();
    content.put(new JSONObject().put("type", "text").put("text", user));
    content.put(
        new JSONObject()
            .put("type", "image_url")
            .put("image_url", new JSONObject().put("url", "data:image/png;base64," + imageB64)));
    array.put(new JSONObject().put("role", "user").put("content", content));
    return post(endpoint, apiKey, model, array, maxTokens);
  }

  private static String post(
      String endpoint, String apiKey, String model, JSONArray messages, int maxTokens)
      throws IOException, JSONException {
    JSONObject body = new JSONObject();
    body.put("model", model);
    body.put("stream", false);
    body.put("max_tokens", maxTokens);
    body.put("messages", messages);

    // 2026-10-09 加：模型接口偶尔 429 限流（"该模型当前访问量过大，请您稍后再试"），
    // 直接炸整个任务太浪费。这里做指数退避重试，最多 4 次（初 3s → 6s → 12s → 24s）。
    int maxRetries = 4;
    long backoffMs = 3000L;
    IOException lastErr = null;
    for (int attempt = 0; attempt <= maxRetries; attempt++) {
      if (attempt > 0) {
        android.util.Log.w(
            "DSHA/ApiClient", "模型接口 429 限流，第 " + attempt + " 次重试，等待 " + backoffMs + "ms");
        try {
          Thread.sleep(backoffMs);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        }
        backoffMs *= 2;
      }
      HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
      try {
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(60_000);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        try (OutputStream out = conn.getOutputStream()) {
          out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        String resp = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
        if (code == 429 && attempt < maxRetries) {
          lastErr = new IOException("HTTP 429: " + brief(resp));
          continue; // 退避后重试
        }
        if (code < 200 || code >= 300) throw new IOException("HTTP " + code + ": " + brief(resp));
        JSONArray choices = new JSONObject(resp).getJSONArray("choices");
        if (choices.length() == 0) throw new IOException("响应里没有 choices");
        JSONObject msg = choices.getJSONObject(0).getJSONObject("message");
        String content = msg.optString("content", "").trim();
        if (content.isEmpty()) {
          // 思考型模型（deepseek-reasoner 等）把 token 全花在 reasoning_content 上时
          // content 会是空串（2026-10-09 真机踩到：任务执行 9 次调用 5 次空回）。
          // 里面确实带着动作 JSON 的话就拿来用，没有就照旧返回空让调用方重试。
          String rc = msg.optString("reasoning_content", "");
          if (rc.contains("\"act\"")) {
            android.util.Log.i(
                "DSHA/ApiClient",
                "content 为空（finish_reason="
                    + choices.getJSONObject(0).optString("finish_reason", "?")
                    + "），从 reasoning_content 里抠动作");
            content = rc.trim();
          }
        }
        return content;
      } finally {
        conn.disconnect();
      }
    }
    throw lastErr != null ? lastErr : new IOException("模型接口重试后仍然失败");
  }

  /**
   * 断网 / 没配 Key 时的兜底说明。以前模型拉不起来就只回一句「网络不可用，检查手机网络」，
   * 用户除了知道坏了一件事之外什么也拿不到，面板还一直挂着像个坏掉的机器。
   * 现在改成：先说清楚哪些**不用网就秒回**，再说联网才能干啥 —— 断网 ≠ 助手废了。
   */
  private static final String OFFLINE_HELP =
      "联网那部分暂时用不了，但下面这些我不用网也能直接给你：问时间日期、算数、"
          + "打开你装的应用（说「打开XX」）、列有哪些应用、打电话发短信、设闹钟和倒计时、"
          + "静音或调音量、开关 Wi-Fi、查电量/存储/现在走的什么网、导航、看你现在屏幕上是什么。";

  /** 把裸异常翻成人话，面板直接展示。 */
  static String friendlyError(Throwable t) {
    String s = String.valueOf(t);
    if (s.contains("DSHA 还没配置 API Key") || s.contains("credential") || s.contains("还没配置"))
      return "模型还没接上：先去 DSHA 里配 API Key（设置 → 接入），配好之前上面这些本地指令照常能用。";
    if (s.contains("401")) return "API Key 无效或已过期。";
    if (s.contains("402")) return "DeepSeek 账户余额不足。";
    if (s.contains("429")) return "请求太频繁，稍等几秒再试。";
    if (t instanceof UnknownHostException || s.contains("Unable to resolve host")
        || s.contains("Failed to connect") || s.contains("Network is not available")) {
      return "现在没网，模型答不了。" + OFFLINE_HELP + " 想聊点别的，等有网再说。";
    }
    if (t instanceof SocketTimeoutException) return "模型回答超时了，再问一次试试。" + OFFLINE_HELP;
    if (s.startsWith("HTTP ")) return "模型那边报错了：" + brief(s);
    return "没答上来（" + s + "）。" + OFFLINE_HELP;
  }

  private static String brief(String resp) {
    if (resp == null) return "";
    resp = resp.replaceAll("\\s+", " ").trim();
    return resp.length() > 160 ? resp.substring(0, 160) + "…" : resp;
  }

  private static String readAll(InputStream in) throws IOException {
    if (in == null) return "";
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    byte[] chunk = new byte[8192];
    int n;
    while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
    in.close();
    return new String(buf.toByteArray(), StandardCharsets.UTF_8);
  }
}
