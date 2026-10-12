package com.deepseekharness.app.assistant;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;

/**
 * 系统原生权限框的**中转站**（Siri 式授权，2026-10-07 按用户「参考 Siri 那个」的要求改）。
 *
 * <p><b>为什么是系统弹窗而不是自己画</b>：iOS 上 Siri 要麦克风，是系统弹一个原生 alert
 * （只有「好/不允许」），App 自己画的卡片在观感上永远差一档。Android 上「系统原生权限框」=
 * {@code Activity.requestPermissions}，而**面板是无障碍悬浮窗、Service 没有 requestPermissions
 * 的资格**，系统权限框只有 Activity 拉得动 —— 所以这个透明壳存在的唯一理由就是：
 * 把系统权限框搬到面板前面弹。
 *
 * <p><b>支持两种（走同一个壳，用 {@code extra what} 区分）</b>：
 * <ul>
 *   <li>{@code what=mic} —— 麦克风，录音转文字用（默认，老路径不变）</li>
 *   <li>{@code what=flash} —— 相机权限，用来开闪光灯（手电筒）。只申请一次 CAMERA 权限，
 *       不会开摄像头拍照，也不存画面。</li>
 * </ul>
 *
 * <p>授权结果由调用方接：麦克风走 {@link AssistantPanel#onMicPermissionGranted()}（接着录音）；
 * 相机走下面 {@code finishWith()} 里的手电筒那一段（授权完当场把灯点亮，别让用户再喊一遍）。
 *
 * <p>透明 + 无历史 + 不进最近任务：用户感知到的仍然是"面板弹了个权限框"，而不是"跳走了"。
 */
public class AssistantMicActivity extends Activity {

  static final String EXTRA_WHAT = "what";
  static final String WHAT_MIC = AssistantPermit.WHAT_MIC;
  static final String WHAT_FLASH = AssistantPermit.WHAT_FLASH;

  private String what = WHAT_MIC;
  private boolean done;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    try {
      Intent in = getIntent();
      if (in != null) {
        String w = in.getStringExtra(EXTRA_WHAT);
        if (WHAT_FLASH.equals(w)) what = WHAT_FLASH;
      }
    } catch (Throwable ignored) {
    }
    if (granted()) {
      finishWith(true);
      return;
    }
    requestPermissions(new String[] {perm()}, 1);
  }

  private boolean granted() {
    return checkSelfPermission(perm()) == PackageManager.PERMISSION_GRANTED;
  }

  private String perm() {
    return WHAT_FLASH.equals(what) ? Manifest.permission.CAMERA : Manifest.permission.RECORD_AUDIO;
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(requestCode, permissions, results);
    boolean ok = results != null && results.length > 0
        && results[0] == PackageManager.PERMISSION_GRANTED;
    finishWith(ok);
  }

  private void finishWith(boolean ok) {
    if (done) return;
    done = true;
    try {
      if (ok && WHAT_MIC.equals(what)) AssistantPanel.onMicPermissionGranted();
      if (ok && WHAT_FLASH.equals(what)) {
        // 相机权限到手，当场把闪光灯点亮：用户说的是"开手电筒"，不是"给我个相机权限"
        boolean lit = AssistantPermit.torch(this, true);
        AssistantPermit.torchOn = lit;
        AssistantPanel.note(
            lit ? "手电筒开了。" : "闪光灯没亮，这台手机可能没有闪光灯，或者正被相机占着。");
      }
      if (!ok) {
        AssistantPanel.note(
            WHAT_MIC.equals(what)
                ? "麦克风没开，语音用不了。"
                : "相机权限没给，手电筒开不了。");
      }
    } catch (Throwable ignored) {
    }
    try {
      finish();
      overridePendingTransition(0, 0);
    } catch (Throwable ignored) {
    }
  }
}
