package com.deepseekharness.app.assistant;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.util.Locale;
import java.util.Set;

/**
 * 把回答念出来 —— Siri 的另一半是「它会说话」。
 *
 * <p>2026-10-07 按用户反馈重写（"回答的语音人机感很重"）。机器味重通常三个原因，
 * 这里各下了一刀：
 * <ol>
 *   <li><b>音色差</b> —— 以前直接用引擎默认音色。现在初始化后扫一遍本机音色表
 *       {@code tts.getVoices()}，按「质量分 + 已下载 + 本地（断网可用）+ 中文」自动挑
 *       最好的一支；本机只有讯飞引擎时，通常能挑到它的 hd/主播档音色。
 *       也可以在 {@code /sdcard/dsh/assistant-voice.json} 里写 {@code ttsVoice} 指定音色名。
 *   <li><b>念法差</b> —— 模型回答里全是 Markdown 符号和 emoji，TTS 会把星号井号
 *       一个个念出来。现在朗读前先清洗：去 Markdown、去 emoji、去链接，
 *       百分号/温度/破折号换成口语（"50%" → "百分之50"）。
 *   <li><b>腔调差</b> —— 默认语速 1.0 + 默认音调听着像导航。默认压到 0.98/0.96
 *       （Siri 那种稍慢、稍沉的说话感），json 里 ttsRate/ttsPitch 可调（0.5~2.0）。
 * </ol>
 *
 * <p>用系统 TTS，不联网、不额外装包。引擎没就绪时 {@link #speak} 直接返回 false，
 * 面板把「朗读」按钮置灰，不报错。
 */
final class AssistantSpeech {

  interface Callback {
    /** 开始念 / 念完 / 出错，面板用它把按钮在「朗读」「停止」之间切。 */
    void onState(boolean speaking, String problem);
  }

  private TextToSpeech tts;
  private boolean ready;
  private boolean speaking;
  /** 实际选中的音色名（给摘要行/日志用，用户排查"为什么还是这个声音"）。 */
  private String chosenVoice;
  private final Callback cb;

  AssistantSpeech(Callback cb) {
    this.cb = cb;
  }

  void init(Context ctx, AssistantConfig cfg) {
    try {
      tts =
          new TextToSpeech(
              ctx.getApplicationContext(),
              status -> {
                if (status == TextToSpeech.SUCCESS && tts != null) {
                  int r = tts.setLanguage(Locale.CHINA);
                  if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    r = tts.setLanguage(Locale.getDefault());
                  }
                  ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
                  pickVoice(cfg);
                  try {
                    tts.setSpeechRate(cfg == null ? 1.0f : cfg.ttsRate);
                    tts.setPitch(cfg == null ? 1.0f : cfg.ttsPitch);
                  } catch (Throwable ignored) {
                  }
                  if (Build.VERSION.SDK_INT >= 15) {
                    tts.setOnUtteranceProgressListener(
                        new UtteranceProgressListener() {
                          @Override
                          public void onStart(String utteranceId) {}

                          @Override
                          public void onDone(String utteranceId) {
                            speaking = false;
                            notifyState(null);
                          }

                          @Override
                          @SuppressWarnings("deprecation")
                          public void onError(String utteranceId) {
                            speaking = false;
                            notifyState("朗读失败");
                          }
                        });
                  }
                }
              });
    } catch (Throwable t) {
      ready = false;
    }
  }

  boolean isReady() {
    return ready && tts != null;
  }

  boolean isSpeaking() {
    return speaking;
  }

  /** 这次实际用的音色名；挑不出来就是 null（引擎默认）。 */
  String chosenVoice() {
    return chosenVoice;
  }

  /**
   * 自动挑本机最好的中文音色。评分规则（分数高者胜）：
   * 质量 HIGH/VERY_HIGH 吃满 > NORMAL > 其他；已下载 +500；本地（断网可用）+200；
   * 讯飞那几个主播名 +30。json 里写了 ttsVoice 的按名字优先，找不到再回到自动。
   */
  private void pickVoice(AssistantConfig cfg) {
    try {
      if (Build.VERSION.SDK_INT < 21 || tts == null) return;
      Set<Voice> voices = tts.getVoices();
      if (voices == null || voices.isEmpty()) return;
      String want = cfg == null ? "" : cfg.ttsVoice;
      Voice best = null;
      int bestScore = Integer.MIN_VALUE;
      for (Voice v : voices) {
        if (v == null || v.getName() == null) continue;
        Locale loc = v.getLocale();
        String lang = loc == null ? "" : loc.getLanguage();
        if (lang == null || !lang.toLowerCase(Locale.US).startsWith("zh")) continue;

        Set<String> f = v.getFeatures();
        boolean notInstalled = f != null && f.contains("notInstalled");
        boolean needsNetwork =
            f != null
                && (f.contains(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS)
                    || f.contains("networkStarted")
                    || f.contains("networkTimeoutMs"));

        int score = 0;
        int q = v.getQuality();
        if (q >= 300) score += q; // QUALITY_HIGH(300) / QUALITY_VERY_HIGH(400)
        else if (q == 100) score += 100; // QUALITY_NORMAL
        else score += 40; // 其他（含 QUALITY_LOW）压到最后
        if (want != null && !want.isEmpty()) {
          score += v.getName().contains(want) ? 10000 : -1;
        }
        if (!notInstalled) score += 500;
        if (!needsNetwork) score += 200;
        String n = v.getName().toLowerCase(Locale.US);
        if (n.contains("xiaoyan")
            || n.contains("xiaofeng")
            || n.contains("yunxi")
            || n.contains("xiaoyuan")) {
          score += 30;
        }
        if (score > bestScore) {
          bestScore = score;
          best = v;
        }
      }
      if (best != null && tts.setVoice(best) == TextToSpeech.SUCCESS) {
        chosenVoice = best.getName();
      }
    } catch (Throwable ignored) {
    }
  }

  boolean speak(String text) {
    if (!isReady() || text == null) return false;
    String clean = cleanForSpeech(text);
    if (clean.isEmpty()) return false;
    try {
      Bundle params = new Bundle();
      if (Build.VERSION.SDK_INT >= 21) {
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC);
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
      }
      int r = tts.speak(clean, TextToSpeech.QUEUE_FLUSH, params, "dsha-assistant");
      speaking = r == TextToSpeech.SUCCESS;
      notifyState(speaking ? null : "朗读失败");
      return speaking;
    } catch (Throwable t) {
      notifyState("朗读失败");
      return false;
    }
  }

  void stop() {
    try {
      if (tts != null) tts.stop();
    } catch (Throwable ignored) {
    }
    speaking = false;
    notifyState(null);
  }

  void shutdown() {
    try {
      if (tts != null) {
        tts.stop();
        tts.shutdown();
      }
    } catch (Throwable ignored) {
    }
    tts = null;
    ready = false;
    speaking = false;
  }

  private void notifyState(String problem) {
    if (cb != null) cb.onState(speaking, problem);
  }

  /**
   * 朗读前的文本清洗：屏幕上好看的、念出来难听的，都在这里处理掉。
   * 顺序有讲究 —— 先去结构符号（Markdown/链接），再做口语替换，最后统一空白。
   */
  static String cleanForSpeech(String raw) {
    if (raw == null) return "";
    String s = raw;
    // 1) 代码与 Markdown 结构
    s = s.replace("```", " ").replace("`", " ");
    s = s.replaceAll("(?m)^#{1,6}\\s*", ""); // 标题井号
    s = s.replaceAll("\\*{1,3}([^*\\n]*)\\*{1,3}", "$1"); // **粗** *斜*
    s = s.replaceAll("(?m)^[ \\t]*[-•·][ \\t]+", ""); // 列表破折号/圆点
    s = s.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1"); // [文字](链接) → 文字
    s = s.replaceAll("https?://\\S+", "，链接见屏幕，"); // 裸链接念出来没法听
    s = s.replaceAll("[|*_#>]+", " "); // 表格竖线等残留
    // 2) emoji / 装饰符号（含代理对、变体选择符、零宽连接符、警告勾叉等 BMP 符号区）
    s = s.replaceAll("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF]", "");
    s = s.replaceAll("[\\u2300-\\u23FF\\u2600-\\u27BF\\u2B00-\\u2BFF\\uFE0F\\u200D]", "");
    // 3) 口语替换（先带数字的精准替换，再做兜底替换）
    s = s.replaceAll("(\\d+(?:\\.\\d+)?)\\s*%", "百分之$1"); // 50% → 百分之50
    s = s.replace("℃", "度").replace("°C", "度").replace("°", "度");
    s = s.replace("～", "到").replace("~", "到").replace("→", "到");
    s = s.replace("&", "和");
    s = s.replace("——", "，").replace("—", "，").replace("·", "，");
    // 4) 空白收敛：TTS 不需要换行缩进，全压成单空格
    s = s.replaceAll("\\s+", " ").trim();
    // 5) 开头若只剩标点，去掉（念出来是"顿、顿、句号"）
    s = s.replaceAll("^[，、。！？；：,.!?;:\\s]+", "");
    return s;
  }
}
