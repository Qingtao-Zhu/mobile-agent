package com.deepseekharness.app.assistant;

import android.content.Context;
import android.media.AudioManager;

/**
 * 「主动发声」开关（2026-10-09 用户需求）。
 *
 * <p><b>用户原话</b>：唤醒时默认是不出声的，只是我出声，它单单听着我就行，仅做文字方面的输出；
 * 如果中间我想让它打开声音跟我说话，我可以对它说"请你跟我直接说话"，或者是
 * "你把声音调到最大跟我说话"，这样它才能跟我说话。
 *
 * <p>所以：<b>默认 {@link #SILENT}</b>（跟现状一致，回答只写在面板上）；
 * 说一句"跟我说话"升到 {@link #SPEAK}；说"声音调到最大"升到 {@link #LOUD}（顺手把媒体音量拉满）；
 * 说"别出声 / 闭嘴"降回 {@link #SILENT}。落盘持久，重启不改。
 */
final class AssistantVoiceMode {

  private static final String PREF = "dsha_panel";
  private static final String KEY = "speak_mode";

  /** 只写字，不出声（默认）。 */
  static final int SILENT = 0;
  /** 回答自动念出来。 */
  static final int SPEAK = 1;
  /** 念出来 + 媒体音量拉满。 */
  static final int LOUD = 2;

  private AssistantVoiceMode() {}

  static int get(Context ctx) {
    try {
      return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY, SILENT);
    } catch (Throwable ignored) {
      return SILENT;
    }
  }

  static void set(Context ctx, int mode) {
    try {
      ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt(KEY, mode).apply();
    } catch (Throwable ignored) {
    }
  }

  /** 媒体音量拉满（LOUD 档顺手做的动作，失败了不影响开关本身）。 */
  static void maxVolume(Context ctx) {
    try {
      AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
      if (am == null) return;
      am.setStreamVolume(AudioManager.STREAM_MUSIC,
          am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);
    } catch (Throwable ignored) {
    }
  }
}
