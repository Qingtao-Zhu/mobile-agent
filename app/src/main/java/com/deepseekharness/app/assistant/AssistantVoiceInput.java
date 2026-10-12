package com.deepseekharness.app.assistant;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

/**
 * 真·语音输入：录音 + 在线 ASR。
 *
 * <p>为什么不用系统 {@code SpeechRecognizer}：本机（华为 EMUI/Android 11）系统里
 * **根本没有可用的 RecognitionService** —— {@code pm query-services -a
 * android.speech.RecognitionService} 只列出一个（DSHA 自己的空壳 {@link DshaRecognitionService}，
 * 它 {@code onStartListening()} 第一句就 {@code ERROR_CLIENT}）。华为助手的识别服务随
 * {@code com.huawei.vassistant} 一起被停用（接管态），要靠它就得把华为助手放回来 →
 * 有丢回接管态的风险。所以录音在本机做，识别走 OpenAI 兼容的在线 ASR 端点（默认 Groq
 * whisper，端点/模型/Key 全部来自 {@link AssistantConfig} 的外部 JSON，可随时换）。
 */
final class AssistantVoiceInput {

  private AssistantVoiceInput() {}

  /** 录音机：MediaRecorder 出 m4a（AAC），不问系统要识别服务，只要 RECORD_AUDIO。 */
  static final class Recorder {
    private MediaRecorder recorder;
    private File output;
    private long startedAt;

    boolean isRecording() {
      return recorder != null;
    }

    long elapsedMs() {
      return recorder == null ? 0L : SystemClock.elapsedRealtime() - startedAt;
    }

    File output() {
      return output;
    }

    /**
     * 当前这一小段的峰值振幅（0~32767），面板那颗光球拿它做「跟着声音鼓」的效果。
     * 只在两次调用之间取最大值，所以轮询间隔越短波形越灵敏。
     */
    int amplitude() {
      MediaRecorder mr = recorder;
      if (mr == null) return 0;
      try {
        return mr.getMaxAmplitude();
      } catch (Throwable t) {
        return 0;
      }
    }

    void start(Context ctx) throws IOException {
      File dir = ctx.getCacheDir();
      File f = new File(dir, "assistant-voice-" + System.currentTimeMillis() + ".m4a");
      MediaRecorder mr = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(ctx) : new MediaRecorder();
      try {
        mr.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION);
        mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mr.setAudioSamplingRate(16000);
        mr.setAudioChannels(1);
        mr.setAudioEncodingBitRate(32000);
        mr.setOutputFile(f.getAbsolutePath());
        mr.prepare();
        mr.start();
      } catch (Throwable t) {
        try {
          mr.release();
        } catch (Throwable ignored) {
        }
        if (t instanceof IOException) throw (IOException) t;
        throw new IOException("录音启动失败：" + t);
      }
      recorder = mr;
      output = f;
      startedAt = SystemClock.elapsedRealtime();
    }

    /** 停止并返回录音文件；没在录音返回 null。 */
    File stop() {
      MediaRecorder mr = recorder;
      recorder = null;
      if (mr == null) return null;
      File f = output;
      output = null;
      try {
        mr.stop();
      } catch (Throwable t) {
        // 太短（<1s）时 stop() 会抛，文件不可用
        f = null;
      }
      try {
        mr.release();
      } catch (Throwable ignored) {
      }
      return f;
    }
  }

  /** 上传音频做识别。同步调用，调用方放后台线程。 */
  static String transcribe(AssistantConfig cfg, File audio) throws IOException {
    if (cfg.asrApiKey == null || cfg.asrApiKey.trim().isEmpty())
      throw new IOException("未配置语音识别 Key（" + cfg.source + "）");
    if (audio == null || !audio.isFile() || audio.length() == 0)
      throw new IOException("没录到声音，再试一次？");

    String boundary = "----DshaAssistant" + System.currentTimeMillis();
    byte[] fileBytes = readAll(audio);
    // 语音唤醒服务（AssistantWakeService）喂的是自己攒的 WAV；面板录音走的还是 m4a。
    // 两种格式 Content-Type 都要给对，不然有的 ASR 网关直接按扩展名猜会解错。
    boolean isWav = audio.getName().endsWith(".wav");
    String filename = isWav ? "voice.wav" : (audio.getName().endsWith(".m4a") ? "voice.m4a" : audio.getName());

    ByteArrayOutputStream body = new ByteArrayOutputStream();
    writeField(body, boundary, "model", cfg.asrModel);
    if (cfg.asrLanguage != null && !cfg.asrLanguage.isEmpty())
      writeField(body, boundary, "language", cfg.asrLanguage);
    write(body, "--" + boundary + "\r\n");
    // 注意 filename 后面那个 \" 不能漏（2026-10-11 血泪）：
    // 原来写的是 filename=\"" + filename + "\r\nContent-Type: ..."，
    // 少了一个收尾引号 → 头部变成 `filename="voice.wav`（引号没闭合）→
    // 服务端 multipart 解析器认定这个 part 头非法、直接丢掉 → Groq 回
    // 400 `file or url must be provided` / missing_audio_input →
    // 表现就是「说了你好大肥鱼完全没反应，但麦克风明明在听、每 5 秒都在发请求」。
    // 这种少一个字符的错，看代码很难发现，只能靠抓请求体/服务端报错反推。
    write(
        body,
        "Content-Disposition: form-data; name=\"file\"; filename=\""
            + filename
            + "\"\r\nContent-Type: "
            + (isWav ? "audio/wav" : "audio/mp4")
            + "\r\n\r\n");
    body.write(fileBytes);
    write(body, "\r\n--" + boundary + "--\r\n");

    HttpURLConnection conn = (HttpURLConnection) new URL(cfg.asrEndpoint).openConnection();
    try {
      conn.setConnectTimeout(15_000);
      conn.setReadTimeout(90_000);
      conn.setRequestMethod("POST");
      conn.setRequestProperty("Authorization", "Bearer " + cfg.asrApiKey);
      conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
      conn.setDoOutput(true);
      conn.setFixedLengthStreamingMode(body.size());
      try (OutputStream out = conn.getOutputStream()) {
        body.writeTo(out);
      }
      int code = conn.getResponseCode();
      String resp = readAllText(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
      if (code < 200 || code >= 300) throw new IOException("HTTP " + code + "：" + brief(resp));
      String text;
      try {
        text = new JSONObject(resp).optString("text", "").trim();
      } catch (org.json.JSONException e) {
        throw new IOException("识别服务返回了看不懂的内容：" + brief(resp));
      }
      if (text.isEmpty()) throw new IOException("识别结果为空，再说一遍？");
      return text;
    } finally {
      conn.disconnect();
      // 录音文件用完即删，不留在手机里
      try {
        audio.delete();
      } catch (Throwable ignored) {
      }
    }
  }

  /** 把异常翻成面板能直接显示的短句。 */
  static String friendlyError(Throwable t) {
    String s = String.valueOf(t);
    if (s.contains("401") || s.contains("403")) return "语音识别 Key 无效或没权限。";
    if (s.contains("429")) return "语音识别请求过于频繁，稍等再试。";
    if (s.contains("402") || s.contains("1113")) return "语音识别账户余额/额度不足。";
    if (t instanceof UnknownHostException || s.contains("Unable to resolve host"))
      return "网络不可用，检查手机网络。";
    if (t instanceof SocketTimeoutException) return "语音识别超时，再试一次。";
    return "语音识别失败：" + s;
  }

  private static void writeField(ByteArrayOutputStream out, String boundary, String name, String value) {
    write(out, "--" + boundary + "\r\n");
    write(out, "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
    write(out, value);
    write(out, "\r\n");
  }

  private static void write(ByteArrayOutputStream out, String s) {
    byte[] b = s.getBytes(StandardCharsets.UTF_8);
    out.write(b, 0, b.length);
  }

  private static String brief(String resp) {
    if (resp == null) return "";
    resp = resp.replaceAll("\\s+", " ").trim();
    return resp.length() > 200 ? resp.substring(0, 200) + "…" : resp;
  }

  private static byte[] readAll(InputStream in) throws IOException {
    if (in == null) return new byte[0];
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    byte[] chunk = new byte[8192];
    int n;
    while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
    in.close();
    return buf.toByteArray();
  }

  private static byte[] readAll(File f) throws IOException {
    return readAll(new FileInputStream(f));
  }

  private static String readAllText(InputStream in) throws IOException {
    return new String(readAll(in), StandardCharsets.UTF_8);
  }
}
