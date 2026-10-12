package com.deepseekharness.app.assistant;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * 辅助应用入口（V1 修正件 → V2 拉起助手面板 → 悬浮窗版转发）。
 *
 * <p>Android 11 起系统用 RoleManager 管默认数字助理，其角色资格检查（logcat 原文）是：
 * <pre>
 * AssistantRoleBehavior:Package &lt;pkg&gt; not qualified for android.app.role.ASSISTANT
 *     due to unqualified service and missing activity
 * </pre>
 * 「missing activity」指的是应用没有任何处理 {@code android.intent.action.ASSIST} 的活动，
 * 结果应用**根本不出现在系统的「辅助应用」选择器里**。本类就是这个资格活动。
 *
 * <p>2026-10-07 起面板本体是 {@link AssistantPanel}（TYPE_ACCESSIBILITY_OVERLAY 悬浮窗，
 * 背景"活"、下面的应用可操作）。悬浮窗必须由无障碍服务挂，所以这里只做一件事：
 * 让服务亮面板，然后立刻 finish —— 助手键按下到面板出现之间不再有 Activity 转场。
 *
 * <p><b>为什么主题是 Theme.NoDisplay</b>：本类只是"借个壳让系统把 Intent 送到应用"，
 * 真面板是悬浮窗。早先用 {@code Theme.Translucent.NoTitleBar}，半透明窗口出现/消失时
 * EMUI 12 的 HWC 会对整屏重排一次合成，实测把桌面内容区搞出 46% 的像素漂移
 * （噪声对照 10 秒 0.00%），看着像"面板把背景弄脏了"，其实是转场副作用。
 * NoDisplay 不建窗口，助手键按下到面板出现之间没有这一步。
 *
 * <p><b>为什么不是直接 showAssistantPanel() 看返回值</b>：服务可能还没连上
 * （force-stop 后实测 4s 内 instance 仍是 null，日志：onCreate shown=false
 * serviceInstance=false）。直接看返回值会当成"服务没开"而误兜底进主界面，
 * 于是走 {@link com.deepseekharness.app.DshaAccessibilityService#requestAssistantPanel()}
 * 排队补亮，只有在 3 秒后面板仍未出现时才判定为服务没开、才兜底。
 */
public class DshaAssistActivity extends Activity {

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    // 「坐标校验只读自检」：带 extra tapCheck="x,y" 时，**只跑校验逻辑，不派发手势**。
    //   adb shell am start -n <pkg>/<本类> -e tapCheck "-1,-1"
    // 用途：验证 D-03 修复（越界坐标必须被拒绝）。刻意不调用 uiTap，
    // 因为用户明确要求"只允许 dump、匹配、记录候选结果和高亮展示，不得自动点击任何目标"。
    if (getIntent() != null && getIntent().hasExtra("tapCheck")) {
      String spec = getIntent().getStringExtra("tapCheck");
      try {
        String[] p = (spec == null ? "" : spec).split(",");
        int x = Integer.parseInt(p[0].trim());
        int y = Integer.parseInt(p[1].trim());
        com.deepseekharness.app.DshaAccessibilityService host =
            com.deepseekharness.app.DshaAccessibilityService.instance();
        String bad = com.deepseekharness.app.DshaAccessibilityService.validateTapCoords(host, x, y);
        android.util.Log.i("DSHA/TapCheck", "坐标 (" + x + "," + y + ") 校验结果="
            + (bad == null ? "通过（合法坐标，真实场景会派发手势）" : bad)
            + " · 服务=" + (host != null) + "（本次未派发任何手势）");
      } catch (Throwable e) {
        android.util.Log.e("DSHA/TapCheck", "自检解析失败: " + e);
      }
      finish();
      return;
    }

    // 「定位只读自检」：带 extra locate=<目标id> 时，只做「读屏 → 定位 → 打日志」，
    // **绝不点击任何东西**。
    //
    // 2026-10-11 加这个入口的原因：用户要求"定位器测试阶段只允许高亮或展示候选目标，
    // 禁止自动点击任何真实界面目标"。而 ActionTargetLocator 原本没有独立的调用入口
    // （它还没接进 AssistantTaskRunner 的主流程），设备上无从验证。
    // 这个入口只输出诊断信息，不派发任何手势，天然满足"不点"的要求。
    //   adb shell am start -n <pkg>/<本类> -e locate <targetId>
    if (getIntent() != null && getIntent().hasExtra("locate")) {
      String targetId = getIntent().getStringExtra("locate");
      android.util.Log.i("DSHA/LocateTest", "只读定位自检开始，目标=" + targetId + "（不会点击任何东西）");
      try {
        ActionTargetStore.Target t = ActionTargetStore.findById(this, targetId);
        if (t == null) {
          android.util.Log.w("DSHA/LocateTest", "找不到目标 " + targetId
              + "；已配置的有: " + ActionTargetStore.summary(this));
          finish();
          return;
        }
        String dump = com.deepseekharness.app.DshaAccessibilityService.uiDump();
        String pkg = null;
        if (dump != null) {
          int i = dump.indexOf("窗口应用: ");
          if (i >= 0) {
            int j = dump.indexOf('\n', i);
            pkg = dump.substring(i + "窗口应用: ".length(), j > 0 ? j : dump.length()).trim();
          }
        }
        ActionTargetLocator.Result r = ActionTargetLocator.locate(dump, t, pkg);
        android.util.Log.i("DSHA/LocateTest", "目标=" + t.name
            + " 界面=" + pkg
            + " 结果=" + (r.found() ? "命中" : "未命中")
            + " 坐标=(" + r.cx + "," + r.cy + ")"
            + " 置信度=" + r.confidence
            + " 依据=" + r.via
            + " 需AI=" + r.needsAi
            + " 原因=" + (r.reason == null ? "-" : r.reason)
            + " 试过=" + r.tried);
        android.util.Log.i("DSHA/LocateTest", "自检结束（未执行任何点击）");
      } catch (Throwable e) {
        android.util.Log.e("DSHA/LocateTest", "自检异常: " + e);
      }
      finish();
      return;
    }

    // 「文本直达」：调用方带了 extra q（桌面快捷方式、宏 App、ADB 调试都能带），
    // 就别走读屏那条路了，把这句话当问题直接答。
    //   adb shell am start -n <pkg>/<本类> -e q "打开微信"
    // 这条通道同时是"语音挂了"的兜底：嘴不好使的时候手还能使。
    String text = null;
    if (getIntent() != null) text = getIntent().getStringExtra("q");
    final boolean direct = text != null && !text.trim().isEmpty();

    try {
      if (direct) {
        // 文本直达：ask 内部会把 pendingText 存下来，服务在线就立刻亮，不在线就等补亮
        AssistantPanel.ask(
            com.deepseekharness.app.DshaAccessibilityService.instance(),
            text == null ? null : text.trim());
        // D-10：原来打的是用户输入的**正文**，改成结构化长度（排障只关心"有没有传进来"）
        android.util.Log.i("DSHA/Assist", LogFormat.directAsk(text));
      } else {
        com.deepseekharness.app.DshaAccessibilityService.requestAssistantPanel();
      }
    } catch (Throwable t) {
      android.util.Log.e("DSHA/Assist", "requestAssistantPanel threw: " + t);
      noServiceNotice();
      finish();
      return;
    }
    android.util.Log.i("DSHA/Assist", "onCreate -> request panel");
    // 服务不在时**只发一条通知，绝不弹主界面**（见下面 noServiceNotice 的说明）。
    new Handler(Looper.getMainLooper())
        .postDelayed(
            () -> {
              if (!com.deepseekharness.app.DshaAccessibilityService.hasInstance()) {
                noServiceNotice();
                return;
              }
              // 文本直达时服务已连上、面板却没亮（排队路径没走到 show），补一次。
              // 不补的话用户点完快捷方式什么反应都没有，只能再点一次才知道是 bug。
              // 但只补"从来没亮过"的：本机指令答完 900ms 就正常收面板了，
              // 那种"亮过又收起"的再补一次，面板就会在用户已经走开 4 秒后凭空弹回来
              // （2026-10-09 真机踩到：它还把刚挂上的悬浮球顶掉了）。
              if (direct && !AssistantPanel.visible() && !AssistantPanel.shownSinceAsk()) {
                AssistantPanel.show(com.deepseekharness.app.DshaAccessibilityService.instance());
              }
            },
            4000L);
    finish();
  }

  /**
   * 没亮面板时给一条通知，而不是把主界面弹起来。
   *
   * <p><b>2026-10-07 用户反馈</b>：「每次我从任务栏打开 DSHA 助手，它会自动弹到应用界面，
   * 能不能不要弹到应用里，保留在桌面上？」—— 原来这里兜底 {@code fallback() → MainActivity}，
   * 结果从桌面快捷方式 / 磁贴 / 助手键进助手，4 秒后一定被拽进 App 的主界面，桌面被盖掉。
   *
   * <p>现在改成：<b>面板是助手的门面，主界面不是。</b>
   * 唤助手只该发生"面板亮起"这一件事；真想用 App（看 Web 端、配模型）应该从桌面图标自己点进去。
   * 所以没连上无障碍服务时，用一条可点的通知告诉用户原因，并给一个直达无障碍设置的按钮。
   */
  private void noServiceNotice() {
    try {
      android.util.Log.w("DSHA/Assist", "服务未连接，只发通知不弹主界面");
      android.app.NotificationManager nm =
          (android.app.NotificationManager) getSystemService(android.content.Context.NOTIFICATION_SERVICE);
      if (nm == null) return;
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        android.app.NotificationChannel ch =
            new android.app.NotificationChannel(
                "dsha_assist",
                "DSHA 助手",
                android.app.NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("助手面板起不来时在这里告诉你原因");
        nm.createNotificationChannel(ch);
      }
      Intent openA11y = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
      openA11y.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      android.app.PendingIntent pi =
          android.app.PendingIntent.getActivity(
              this,
              4909,
              openA11y,
              android.app.PendingIntent.FLAG_UPDATE_CURRENT
                  | (android.os.Build.VERSION.SDK_INT >= 23
                      ? android.app.PendingIntent.FLAG_IMMUTABLE
                      : 0));
      android.app.Notification.Builder b;
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        b = new android.app.Notification.Builder(this, "dsha_assist");
      } else {
        b = new android.app.Notification.Builder(this);
      }
      b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
          .setContentTitle("DSHA 助手面板没亮起")
          .setContentText("无障碍服务还没连上，助手起不来。点这里去把它打开。")
          .setAutoCancel(true)
          .setContentIntent(pi);
      nm.notify(4909, b.build());
    } catch (Throwable t) {
      android.util.Log.e("DSHA/Assist", "通知没发出来: " + t);
    }
  }
}
