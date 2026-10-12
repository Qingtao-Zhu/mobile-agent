package com.deepseekharness.app.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.R;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 语音唤醒前台服务（2026-10-09 用户需求）："你好大肥鱼" → 屏幕上文字回应。
 *
 * <p><b>用户原话</b>：能不能加个语音唤醒功能啊？比如我用"你好大肥鱼"来唤醒，
 * 它就可以在屏幕上用文字回应我。唤醒时默认不出声，只是我出声，它单单听着，仅做文字输出。
 *
 * <p><b>为什么是这条路（自研 VAD + 在线 ASR 判词），而不是官方 AlwaysOnHotwordDetector</b>：
 * 官方热词 API 要系统 SoundTrigger DSP 支持，零售机 EMUI 上大概率没有（助手按键也早已实测
 * 被小艺截走）；第三方唤醒引擎（Porcupine）要注册账号拿 AccessKey。这条自研路线零新依赖：
 * <ol>
 *   <li>麦克风 16kHz 常开，逐 100ms 算能量（RMS）做 <b>VAD</b> —— CPU 开销极小；
 *   <li>检测到有人说话 → 攒一段（0.35~3.5 秒）存成 WAV → 丢给<b>现成的在线 ASR</b>
 *   （{@link AssistantVoiceInput#transcribe}，面板语音输入同一套端点/Key）；
 *   <li>识别文本里含「大肥鱼/大飞鱼」等同音变体 → 唤醒：亮面板 + 写一句"在呢"，默认静音。
 * </ol>
 *
 * <p><b>代价（如实记）</b>：麦克风要常开（前台服务 + 常驻通知，可在通知上一键关）；
 * 每次疑似人声都要发一次 ASR 请求（安静环境下一晚也就几次）；省电策略可能杀它 ——
 * 所以要在 EMUI 启动管理里给 DSHA 放行，最好再进电池优化白名单。
 *
 * <p><b>并发冲突</b>：面板自己的录音（MediaRecorder）和这里的 AudioRecord 不能同时抢麦。
 * 面板一亮就让出麦克风（释放 AudioRecord，纯 sleep 值守），面板收了再重新开麦。
 */
public final class AssistantWakeService extends Service {

  private static final String TAG = "DSHA/Wake";
  private static final String PREF = "dsha_panel";
  private static final String KEY_WAKE = "wake_enabled";
  private static final String CHANNEL = "dsha_wake";
  private static final int NOTIF_ID = 9417;

  private static final int SAMPLE_RATE = 16000;
  /** 唤醒词本体：ASR 对"肥/飞/费/废/腓"这类同音字没谱，全列上，命中任何一个就算。 */
  private static final String[] HOTWORDS = {"大肥鱼", "大飞鱼", "大费鱼", "大废鱼", "大腓鱼", "大肥渔"};

  private static final long COOLDOWN_MS = 2500L;

  private final Handler ui = new Handler(Looper.getMainLooper());
  private final ExecutorService io = Executors.newSingleThreadExecutor();

  private volatile boolean running;
  private AssistantConfig cfg;
  /** 上次巡检无障碍服务的时间戳（{@code elapsedRealtime}，毫秒）。 */
  private long lastHealCheck = 0L;

  // ---------------------------------------------------------------- 开关

  static boolean enabled(Context ctx) {
    try {
      return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_WAKE, true);
    } catch (Throwable ignored) {
      return true;
    }
  }

  /** 开/关唤醒。开 → 起前台服务；关 → 停服务。落盘持久。 */
  static void setEnabled(Context ctx, boolean on) {
    try {
      ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_WAKE, on).apply();
    } catch (Throwable ignored) {
    }
    if (on) {
      Intent i = new Intent(ctx, AssistantWakeService.class);
      if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
      else ctx.startService(i);
    } else {
      ctx.stopService(new Intent(ctx, AssistantWakeService.class));
    }
  }

  /** 服务重连（开机/被杀重启）后由 {@code DshaAccessibilityService.onServiceConnected} 调。 */
  public static void startIfEnabled(Context ctx) {
    if (enabled(ctx)) {
      Intent i = new Intent(ctx, AssistantWakeService.class);
      try {
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
      } catch (Throwable t) {
        android.util.Log.w(TAG, "唤醒服务没起来: " + t);
      }
    }
  }

  /** 唤醒词命中判定（给测试命令复用）。 */
  static boolean isHotword(String text) {
    if (text == null) return false;
    String t = text.replaceAll("[\\s，,。！!？?．.、]", "");
    if (t.isEmpty()) return false;
    for (String h : HOTWORDS) if (t.contains(h)) return true;
    // 兜底：整句非常短且以"你好"开头时也算半个 —— 误唤醒由面板"在呢"兜底，不算事故
    return t.startsWith("你好") && t.length() <= 4;
  }

  // ---------------------------------------------------------------- 生命周期

  @Override
  public void onCreate() {
    super.onCreate();
    cfg = AssistantConfig.load(this);
    NotificationManager nm = getSystemService(NotificationManager.class);
    if (nm != null && Build.VERSION.SDK_INT >= 26) {
      nm.createNotificationChannel(
          new NotificationChannel(CHANNEL, "DSHA 语音唤醒", NotificationManager.IMPORTANCE_LOW));
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && "stop".equals(intent.getAction())) {
      setEnabled(this, false);
      stopSelf();
      return START_NOT_STICKY;
    }
    startForeground(NOTIF_ID, buildNotification());
    // 常驻兜底闹钟跟着唤醒服务一起排：只要唤醒开着，就保证有一条系统级闹钟
    // 能在进程被回收后把助手救回来（详见 AssistantKeepAliveReceiver 类注释）。
    AssistantKeepAliveReceiver.schedule(this);
    if (!running) {
      running = true;
      Thread t = new Thread(this::loop, "dsha-wake");
      t.setPriority(Thread.NORM_PRIORITY - 1);
      t.start();
    }
    return START_STICKY;
  }

  @Override
  public void onDestroy() {
    running = false;
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  private Notification buildNotification() {
    Intent stop = new Intent(this, AssistantWakeService.class).setAction("stop");
    PendingIntent stopPi =
        PendingIntent.getService(this, 9418, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    Notification.Builder b =
        Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
    b.setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("DSHA 语音唤醒")
        .setContentText("说「你好大肥鱼」唤醒我 · 不出声只亮屏")
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getService(
                this, 9419, new Intent(this, AssistantWakeService.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    b.addAction(new Notification.Action.Builder(null, "停止唤醒", stopPi).build());
    return b.build();
  }

  // ---------------------------------------------------------------- 主循环

  private void loop() {
    // 没有 ASR Key 这条路就是死的，别假装在听
    if (cfg == null || cfg.asrApiKey == null || cfg.asrApiKey.trim().isEmpty()) {
      AssistantPanel.note("语音唤醒没法用：还没配置语音识别的 Key（设置 → 接入）。");
      stopSelf();
      return;
    }
    if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      AssistantPanel.note("语音唤醒没法用：麦克风权限没给。");
      stopSelf();
      return;
    }

    AudioRecord rec = null;
    try {
      int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
          AudioFormat.ENCODING_PCM_16BIT);
      rec = openMic(minBuf * 4);
      if (rec == null) {
        AssistantPanel.note("语音唤醒没法用：麦克风被别的应用占着。");
        stopSelf();
        return;
      }

      short[] buf = new short[SAMPLE_RATE / 10]; // 100ms 一窗
      long noise = 350L;                         // 环境噪声的滑动估计（RMS）
      boolean inSpeech = false;
      long speechStartAt = 0L;
      long silenceSince = 0L;
      long cooledUntil = 0L;
      ByteArrayOutputStream speech = new ByteArrayOutputStream();

      while (running) {
        // 无障碍服务自愈巡检（每 10 秒一次）。它被系统判崩溃后**不会再自动重绑**，
        // 而唤醒服务是本 App 唯一常驻后台的东西，所以由它兜底把服务救回来。
        // 正常情况 healIfNeeded 内部发现服务好着就直接返回，不会 sleep、不影响听词。
        long tick = SystemClock.elapsedRealtime();
        if (tick - lastHealCheck > 10_000L) {
          lastHealCheck = tick;
          AccessibilityHealer.healIfNeeded(AssistantWakeService.this);
        }
        // 面板亮着 = 用户可能正对着它说话/录音，让出麦克风
        if (AssistantPanel.visible()) {
          if (rec != null) {
            try {
              rec.stop();
            } catch (Throwable ignored) {
            }
            rec.release();
            rec = null;
          }
          SystemClock.sleep(400L);
          continue;
        }
        if (rec == null) {
          rec = openMic(minBuf * 4);
          if (rec == null) {
            SystemClock.sleep(800L);
            continue;
          }
        }

        int n = rec.read(buf, 0, buf.length);
        if (n <= 0) {
          SystemClock.sleep(20L);
          continue;
        }
        long now = SystemClock.elapsedRealtime();
        long rms = rms(buf, n);

        if (!inSpeech) {
          // 噪声地板只在安静时缓慢跟随，说话时不动
          if (rms < noise * 2) noise = (noise * 7 + rms) / 8;
          long threshold = Math.max(850L, noise * 3);
          if (rms > threshold && now > cooledUntil) {
            inSpeech = true;
            speechStartAt = now;
            silenceSince = 0L;
            speech.reset();
            append(speech, buf, n);
          }
        } else {
          append(speech, buf, n);
          long threshold = Math.max(850L, noise * 3);
          if (rms < threshold * 7 / 10) {
            if (silenceSince == 0L) silenceSince = now;
            if (now - silenceSince > 700L) { // 静音 0.7s 算说完
              inSpeech = false;
              cooledUntil = maybeWake(speech.toByteArray());
              speech.reset();
            }
          } else {
            silenceSince = 0L;
          }
          if (inSpeech && now - speechStartAt > 3500L) { // 兜底：说太长也切
            inSpeech = false;
            cooledUntil = maybeWake(speech.toByteArray());
            speech.reset();
          }
        }
      }
    } catch (Throwable t) {
      android.util.Log.w(TAG, "唤醒循环退出: " + t);
    } finally {
      if (rec != null) {
        try {
          rec.stop();
        } catch (Throwable ignored) {
        }
        try {
          rec.release();
        } catch (Throwable ignored) {
        }
      }
      if (running) stopSelf(); // 异常退出时把前台通知一起收干净
    }
  }

  private AudioRecord openMic(int bufBytes) {
    try {
      AudioRecord r =
          new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
              AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes);
      if (r.getState() != AudioRecord.STATE_INITIALIZED) {
        r.release();
        return null;
      }
      r.startRecording();
      return r;
    } catch (Throwable t) {
      android.util.Log.w(TAG, "开麦失败: " + t);
      return null;
    }
  }

  private static long rms(short[] buf, int n) {
    double sum = 0;
    for (int i = 0; i < n; i++) {
      double v = buf[i];
      sum += v * v;
    }
    return (long) Math.sqrt(sum / Math.max(1, n));
  }

  private static void append(ByteArrayOutputStream out, short[] buf, int n) {
    for (int i = 0; i < n; i++) {
      out.write(buf[i] & 0xFF);
      out.write((buf[i] >> 8) & 0xFF);
    }
  }

  /** 攒到的一段人声 → WAV → ASR → 是不是喊我。返回冷却截止时间。 */
  private long maybeWake(byte[] pcm) {
    if (pcm.length < SAMPLE_RATE * 2 * 35 / 100) return 0L; // <0.35s 太短，多半是磕碰声
    final byte[] bytes = pcm;
    io.execute(() -> {
      File wav = null;
      try {
        wav = writeWav(bytes);
        String text = AssistantVoiceInput.transcribe(cfg, wav);
        // D-10（2026-10-11 日志隐私）：原来这里打的是识别**正文**（"听到: " + text），
        // 等于把用户说的话写进 logcat。改成结构化指标 —— 仍然能回答排障最关心的问题
        // （"识别通了没""是不是命中唤醒词"），但不再留内容。
        // 排查"唤醒词识别成什么样"这类问题时，改用一次性的临时构建，不要长期留正文。
        boolean hot = isHotword(text);
        android.util.Log.i(TAG, LogFormat.speechRecognized(text, hot));
        if (hot) wake();
      } catch (Throwable t) {
        // 识别失败（断网/超时）静默跳过，别为一次失败刷屏
        android.util.Log.i(TAG, "唤醒判词失败: " + t);
      } finally {
        if (wav != null) try {
          wav.delete();
        } catch (Throwable ignored) {
        }
      }
    });
    return SystemClock.elapsedRealtime() + COOLDOWN_MS;
  }

  /** 唤醒动作：亮面板 + 文字回应，<b>默认静音</b>（SILENT 档下面板不朗读，正合用户要求）。 */
  private void wake() {
    ui.post(() -> {
      DshaAccessibilityService host = DshaAccessibilityService.instance();
      if (host == null) return;
      if (!AssistantPanel.visible()) {
        AssistantPanel.show(host);
        AssistantPanel.finishTask("在呢。要说什么？（默认不出声，只写字）");
      }
    });
  }

  // ---------------------------------------------------------------- WAV

  private File writeWav(byte[] pcm) throws IOException {
    File dir = new File(getCacheDir(), "wake");
    if (!dir.exists()) dir.mkdirs();
    File f = new File(dir, "wake_" + System.currentTimeMillis() + ".wav");
    int dataLen = pcm.length;
    int totalLen = dataLen + 36;
    byte[] h = new byte[44];
    putAscii(h, 0, "RIFF");
    putInt(h, 4, totalLen);
    putAscii(h, 8, "WAVE");
    putAscii(h, 12, "fmt ");
    putInt(h, 16, 16);
    putShort(h, 20, (short) 1); // PCM
    putShort(h, 22, (short) 1); // mono
    putInt(h, 24, SAMPLE_RATE);
    putInt(h, 28, SAMPLE_RATE * 2);
    putShort(h, 32, (short) 2);
    putShort(h, 34, (short) 16);
    putAscii(h, 36, "data");
    putInt(h, 40, dataLen);
    try (FileOutputStream out = new FileOutputStream(f)) {
      out.write(h);
      out.write(pcm);
    }
    return f;
  }

  private static void putAscii(byte[] b, int off, String s) {
    for (int i = 0; i < s.length(); i++) b[off + i] = (byte) s.charAt(i);
  }

  private static void putInt(byte[] b, int off, int v) {
    b[off] = (byte) v;
    b[off + 1] = (byte) (v >> 8);
    b[off + 2] = (byte) (v >> 16);
    b[off + 3] = (byte) (v >> 24);
  }

  private static void putShort(byte[] b, int off, short v) {
    b[off] = (byte) v;
    b[off + 1] = (byte) (v >> 8);
  }
}
