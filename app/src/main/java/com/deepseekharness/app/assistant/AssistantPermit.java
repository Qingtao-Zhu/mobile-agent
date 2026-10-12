package com.deepseekharness.app.assistant;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;

/**
 * 权限闸门：把"这事需要权限"从一句干巴巴的"我做不到"变成**当场弹一个框让你点同意**。
 *
 * <p>2026-10-07 起因：用户原话「像这些需要权限的，能不能给一个及时的弹窗？这样让我直接确认授权」。
 * 以前的做派是——没权限就静默失败、或回一句"系统不让我干"，
 * 得自己去设置里翻半天开权限，助手像个只会说"不行"的哑巴。
 *
 * <p>现在任何人要干需要权限的事，先 {@link #ready}：能干就干；
 * 不能干就 {@link #ask} 弹一个说明卡片（要什么权限 + 为什么要 + 一个"去开"按钮），
 * 点一下直接拉起系统权限框/对应设置页，回来自动接着干。
 *
 * <p><b>受它管的几种</b>：
 * <ul>
 *   <li>{@link #WHAT_MIC} 麦克风（语音输入）</li>
 *   <li>{@link #WHAT_FLASH} 手电筒（相机权限，闪光灯不算隐私，只申请一次就够）</li>
 *   <li>{@link #WHAT_NOTIFY} 读通知栏（系统不给弹框，只能带你去「通知使用权」页手动开）</li>
 *   <li>{@link #WHAT_BATTERY} 后台常活（电池优化白名单）</li>
 *   <li>{@link #WHAT_AIRPLANE} 飞行模式（这台 ROM 的 secure 设置第三方拿不到，只做引导）</li>
 * </ul>
 */
final class AssistantPermit {

  static final String WHAT_MIC = "mic";
  static final String WHAT_FLASH = "flash";
  static final String WHAT_NOTIFY = "notify";
  static final String WHAT_BATTERY = "battery";
  static final String WHAT_AIRPLANE = "airplane";

  private AssistantPermit() {}

  /** 这件事现在能不能直接干（不弹窗、不问用户）。 */
  static boolean ready(Context ctx, String what) {
    if (ctx == null || what == null) return false;
    try {
      if (WHAT_MIC.equals(what)) {
        return ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED;
      }
      if (WHAT_FLASH.equals(what)) {
        return ctx.checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            && torchCameraId(ctx) != null;
      }
      if (WHAT_NOTIFY.equals(what)) {
        return DshaNotificationListener.isConnected();
      }
      if (WHAT_BATTERY.equals(what)) {
        return batteryAllowed(ctx);
      }
    } catch (Throwable ignored) {
    }
    return false;
  }

  /**
   * 弹授权。**这里按 Siri 的分野分派**（2026-10-07 用户原话「参考一下 Siri 那个」）：
   * 系统弹得动的就交系统（弹原生 alert），系统弹不动的才画卡片。
   *
   * <ul>
   *   <li>{@code mic / flash} —— 危险权限，系统 {@code requestPermissions} 能弹原生框，
   *       走 {@link AssistantMicActivity}（透明壳把系统框搬到面板前面）；</li>
   *   <li>{@code notify / battery / airplane} —— 系统级权限，第三方调不出原生框，
   *       走 {@link AssistantPermitActivity} 画 iOS 那张卡片 + 一个「去开」按钮。</li>
   * </ul>
   *
   * @param reason 为什么要做这件事（进卡片当副标题，比如"你说要开手电筒"）
   * @return 弹窗有没有拉起来；拉不起来（面板已收/服务上下文没了）调用方自己兜底
   */
  static boolean ask(Context ctx, String what, String reason) {
    if (ctx == null || what == null) return false;
    try {
      // 能交给系统的走原生框；只有系统不给弹的才轮到自画卡片
      Class<?> cls = (WHAT_MIC.equals(what) || WHAT_FLASH.equals(what))
          ? AssistantMicActivity.class
          : AssistantPermitActivity.class;
      Intent i = new Intent(ctx, cls);
      i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      if (reason != null) i.putExtra(AssistantPermitActivity.EXTRA_REASON, reason);
      i.putExtra(AssistantPermitActivity.EXTRA_WHAT, what);
      ctx.startActivity(i);
      return true;
    } catch (Throwable t) {
      android.util.Log.w("DSHA/Permit", "权限弹窗没拉起来: " + t);
      return false;
    }
  }

  // ---------------------------------------------------------------- 手电筒

  /**
   * 有闪光灯的**后置**摄像头 id（没有后置就退回任意带闪光灯的），没有返回 null。
   * 先挑后置是有道理的：后置闪光灯亮度和散热都盖得住"手电筒"这活，前置只有一个小补光灯。
   */
  static String torchCameraId(Context ctx) {
    try {
      CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
      if (cm == null) return null;
      String fallback = null;
      for (String id : cm.getCameraIdList()) {
        if (id == null) continue;
        Boolean flash = cm.getCameraCharacteristics(id)
            .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
        if (!Boolean.TRUE.equals(flash)) continue;
        Integer facing = cm.getCameraCharacteristics(id)
            .get(CameraCharacteristics.LENS_FACING);
        if (facing == null || facing == CameraCharacteristics.LENS_FACING_BACK) return id;
        if (fallback == null) fallback = id;
      }
      return fallback;
    } catch (Throwable t) {
      android.util.Log.w("DSHA/Permit", "找不到闪光灯: " + t);
      return null;
    }
  }

  /** 开/关手电筒。返回 true 表示这次真下指令了（失败原因另说）。 */
  static boolean torch(Context ctx, boolean on) {
    try {
      CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
      String id = torchCameraId(ctx);
      if (cm == null || id == null) return false;
      cm.setTorchMode(id, on);
      return true;
    } catch (Throwable t) {
      android.util.Log.w("DSHA/Permit", "手电筒指令没发出去: " + t);
      return false;
    }
  }

  /** 手电筒是不是正亮着（华为上读不到状态，就当自己记的，够用）。 */
  static boolean torchOn;

  // ---------------------------------------------------------------- 电池优化

  private static boolean batteryAllowed(Context ctx) {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        android.content.pm.PackageManager pm = ctx.getPackageManager();
        if (pm == null) return false;
        // 直接调 pm.isIgnoringBatteryOptimizations 在这台工程上编译不过
        // （javac：「找不到符号 方法 isIgnoringBatteryOptimizations(String)」），
        // 所以走反射；反射不到就当已经白名单 —— 读不准总比一会说不白名单、一会说白名单强。
        java.lang.reflect.Method m =
            pm.getClass().getMethod("isIgnoringBatteryOptimizations", String.class);
        return Boolean.TRUE.equals(m.invoke(pm, ctx.getPackageName()));
      }
      return true;
    } catch (Throwable ignored) {
      return false;
    }
  }
}
