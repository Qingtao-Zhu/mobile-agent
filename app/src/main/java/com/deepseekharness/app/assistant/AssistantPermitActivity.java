package com.deepseekharness.app.assistant;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 「系统不给我弹权限框，只能带你去设置」那几件事的**卡片**（2026-10-07 参考 Siri 的授权 alert 做的）。
 *
 * <p><b>和 {@link AssistantMicActivity} 的分工</b>（这套分野是看了 Siri 的权限形态定的）：
 * <ul>
 *   <li><b>能交给系统的，一律交给系统</b> —— 麦克风、相机走
 *       {@code requestPermissions}，弹的就是 iOS 上 Siri 那种原生 alert。
 *       自己画卡片在观感上永远差一档，不画。</li>
 *   <li><b>系统不给弹的才画卡片</b> —— 通知使用权（BIND_NOTIFICATION_LISTENER_SERVICE）
 *       和电池优化白名单都是**系统级绑定权限**，第三方 App 调不出原生框，
 *       只能用这种卡片把你送到设置页。iOS 上「允许 Siri」也是这一步（跳 prefs:root=Siri），
 *       所以卡片形态本身并不掉价，是必需的那一环。</li>
 * </ul>
 *
 * <p><b>卡片按 iOS alert 的样子做</b>：居中、白色、大圆角、标题居中一行、
 * 正文居中、底部两个等分按钮 + 发丝分隔线；点暗底**不**关闭（iOS alert 也不许点外面关），
 * 只能二选一 —— 免得手一抖授权弹窗就没了，回头又得再问一遍。
 *
 * <p>它出现在悬浮窗面板之上，所以看起来就是"助手在问你要不要给它这个权限"。
 */
public class AssistantPermitActivity extends Activity {

  private static final String TAG = "DSHA/Permit";

  static final String EXTRA_WHAT = "what";
  static final String EXTRA_REASON = "reason";

  /**
   * 三个设置页 action 写成常量字符串，不是偷懒：这台工程编出来的 classpath 里
   * {@code Settings.ACTION_ACCESS_NOTIFICATION_LISTENER_SETTINGS} /
   * {@code ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS} / {@code ACTION_QUICK_SETTINGS}
   * 编译报「找不到符号」（javac 原文见本轮构建日志），常量名不存在就用字面值，
   * 这三个值是公开的、稳定的，写死比让编译挂掉划算。
   */
  static final String ACT_NOTIFY_DETAIL =
      "android.settings.ACCESS_NOTIFICATION_LISTENER_DETAIL_SETTINGS";
  static final String ACT_NOTIFICATION_LISTENER = "android.settings.ACCESS_NOTIFICATION_LISTENER_SETTINGS";
  static final String ACT_BATTERY_WHITELIST = "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS";
  static final String ACT_QUICK_SETTINGS = "android.settings.QUICK_SETTINGS";

  /** 标题 / 正文 / 主按钮字 / 次按钮字。加新权限加一行就够。 */
  private static final String[][] INFO = {
      {
          AssistantPermit.WHAT_NOTIFY, "想读通知栏",
          "读你收到的通知，才能答「谁在找我、有什么待办」。\n"
              + "银行、支付类应用的通知按隐私规则跳过，不会发给模型。",
          "notify", "去开通知使用权", "先不用"
      },
      {
          AssistantPermit.WHAT_BATTERY, "想一直在后台待着",
          "助手在锁屏后也能接得住唤醒，不然你叫它它不一定在。",
          "battery", "去加白名单", "先不用"
      },
      {
          AssistantPermit.WHAT_AIRPLANE, "开关飞行模式",
          "这台手机（EMUI）不给第三方 App 动飞行模式 —— 但你这话我听见了，"
              + "我把快捷设置拉出来，你点一下就行。",
          "quicksettings", "拉出快捷设置", "不用了"
      },
  };

  private boolean done;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    try {
      Intent in = getIntent();
      String what = in == null ? "" : in.getStringExtra(EXTRA_WHAT);
      String reason = in == null ? "" : in.getStringExtra(EXTRA_REASON);
      String[] info = find(what);
      if (info == null) {
        finish();
        return;
      }
      setContentView(alert(info, reason));
    } catch (Throwable t) {
      android.util.Log.w("DSHA/Permit", "授权卡片没画出来: " + t);
      finish();
    }
  }

  private String[] find(String w) {
    for (String[] row : INFO) if (row[0].equals(w)) return row;
    return null;
  }

  // ---------------------------------------------------------------- iOS alert

  private View alert(final String[] info, String reason) {
    int w = getResources().getDisplayMetrics().widthPixels;
    int cardW = Math.min(dp(300), (int) (w * 0.86f));

    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(0x99000000);
    root.setClickable(true); // 吃掉暗底的点击：iOS alert 点外面也不关

    LinearLayout card = new LinearLayout(this);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setBackgroundColor(0xFFFFFFFF);
    card.setClickable(true);
    LinearLayout.LayoutParams clp =
        new LinearLayout.LayoutParams(cardW, LinearLayout.LayoutParams.WRAP_CONTENT);
    clp.gravity = Gravity.CENTER;
    card.setLayoutParams(clp);

    LinearLayout body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setGravity(Gravity.CENTER_HORIZONTAL);
    int pad = dp(20);
    body.setPadding(pad, dp(20), pad, dp(14));

    TextView title = new TextView(this);
    title.setText(info[1]);
    title.setGravity(Gravity.CENTER_HORIZONTAL);
    title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
    title.setTextColor(0xFF1C1C1E);
    body.addView(title);

    TextView msg = new TextView(this);
    msg.setText(info[2]);
    msg.setGravity(Gravity.CENTER_HORIZONTAL);
    msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
    msg.setTextColor(0xFF3C3C43);
    msg.setLineSpacing(0, 1.25f);
    int tp = dp(6);
    msg.setPadding(0, tp, 0, dp(4));
    body.addView(msg);

    if (reason != null && !reason.isEmpty()) {
      TextView r = new TextView(this);
      r.setText(reason);
      r.setGravity(Gravity.CENTER_HORIZONTAL);
      r.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
      r.setTextColor(0xFF8A8F8E);
      r.setPadding(0, dp(2), 0, 0);
      body.addView(r);
    }
    card.addView(body);

    View hairTop = new View(this);
    hairTop.setBackgroundColor(0xFFD1D1D6);
    hairTop.setLayoutParams(new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, dp(0.5f)));
    card.addView(hairTop);

    LinearLayout btns = new LinearLayout(this);
    btns.setOrientation(LinearLayout.HORIZONTAL);
    btns.setLayoutParams(new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

    Button cancel = flat(info[5], 0xFF8A8F8E);
    cancel.setOnClickListener(v -> finish());
    LinearLayout.LayoutParams cbp =
        new LinearLayout.LayoutParams(0, dp(50), 1f);
    cancel.setLayoutParams(cbp);
    btns.addView(cancel);

    View hairMid = new View(this);
    hairMid.setBackgroundColor(0xFFD1D1D6);
    hairMid.setLayoutParams(new LinearLayout.LayoutParams(dp(0.5f), LinearLayout.LayoutParams.MATCH_PARENT));
    btns.addView(hairMid);

    Button go = flat(info[4], 0xFF007AFF);
    go.setOnClickListener(v -> {
      if (done) return;
      done = true;
      try {
        String target = info[3];
        // 先把本卡片收掉再跳设置页：不然两张白窗口叠着，看着像卡住了
        finish();
        if ("battery".equals(target)) {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
              startActivity(new Intent(AssistantPermitActivity.ACT_BATTERY_WHITELIST,
                  Uri.parse("package:" + getPackageName()))
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable t) {
              android.util.Log.w(TAG, "电池白名单页打不开: " + t);
            }
          }
        } else if ("quicksettings".equals(target)) {
          openSettings(AssistantPermitActivity.ACT_QUICK_SETTINGS);
        } else {
          // 通知使用权这张最娇气：这台机（EMUI）上标准的
          // android.settings.ACTION_ACCESS_NOTIFICATION_LISTENER_SETTINGS **解析不到任何页面**
          // （`am start -a ...` 原话：Error: Activity not started, unable to resolve Intent），
          // 只有 ACCESS_NOTIFICATION_LISTENER_DETAIL_SETTINGS 能开，所以按"能开哪个开哪个"往下退。
          openSettings(
              AssistantPermitActivity.ACT_NOTIFY_DETAIL,
              AssistantPermitActivity.ACT_NOTIFICATION_LISTENER,
              AssistantPermitActivity.ACT_QUICK_SETTINGS);
        }
      } catch (Throwable t) {
        android.util.Log.w(TAG, "跳设置失败: " + t);
      }
    });
    LinearLayout.LayoutParams gbp = new LinearLayout.LayoutParams(0, dp(50), 1f);
    go.setLayoutParams(gbp);
    btns.addView(go);

    card.addView(btns);
    root.addView(card);
    return root;
  }

  /** 依次试几个设置页，谁打得开就用谁（通知使用权那页各 ROM 长得不一样）。 */
  private void openSettings(String... actions) {
    for (String a : actions) {
      try {
        Intent i = new Intent(a);
        i.putExtra("android.intent.extra.PACKAGE_NAME", getPackageName());
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        return;
      } catch (Throwable t) {
        android.util.Log.w(TAG, "设置页打不开，换下一个: " + a + " (" + t + ")");
      }
    }
  }

  private Button flat(String text, int color) {
    Button b = new Button(this);
    b.setText(text);
    b.setAllCaps(false);
    b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
    b.setTextColor(color);
    b.setBackgroundColor(0x00000000);
    return b;
  }

  private int dp(float v) {
    return (int) TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
  }
}
