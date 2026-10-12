package com.deepseekharness.app.assistant;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一的权限管理页（2026-10-11 大众化改造核心之三）。
 *
 * <p><b>为什么要单独一页</b>：用户要求「在应用内增加清晰、统一的权限管理页面，
 * 每项权限应有独立的状态和操作按钮」。原来的做法是**用到才弹卡片**
 * （{@link AssistantPermit}），对开发者够用，对普通用户不行——
 * 他不知道这 App 一共要什么、还差什么、为什么差。
 *
 * <p><b>每项展示四态</b>（用户指定）：
 * <ul>
 *   <li>未授权 → 「去授权」按钮，点了先解释用途再跳系统页
 *   <li>已授权 → 「已开启」（灰掉，不可点）
 *   <li>被拒绝/不支持 → 说清原因 + 给替代方案
 * </ul>
 *
 * <p><b>最小权限原则</b>：本页只列"实际会用到的"。用不到的权限不申请、
 * 也不在这里出现——用户明确要求「如果某项功能不需要某种权限，就不要为了方便而要求它」。
 *
 * <p><b>版本差异</b>：全部经 {@link Compat} 判断，不在本类里写 {@code SDK_INT >= 33} 这种散判断。
 */
public class PermissionHubActivity extends Activity {

  private LinearLayout list;
  private TextView summary;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(buildUi());
  }

  @Override
  protected void onResume() {
    super.onResume();
    // 用户从系统设置回来 → 状态可能变了，重刷（用户要求"应用返回前台时重新检查状态"）
    refresh();
  }

  // ---------------------------------------------------------------- UI

  private View buildUi() {
    ScrollView scroll = new ScrollView(this);
    scroll.setBackgroundColor(0xFF0E0E10);

    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    int pad = dp(18);
    root.setPadding(pad, pad, pad, pad);
    scroll.addView(root);

    TextView title = new TextView(this);
    title.setText("权限与授权");
    title.setTextColor(0xFFFFFFFF);
    title.setTextSize(21);
    root.addView(title);

    TextView sub = new TextView(this);
    sub.setText("助手要用的所有系统能力都在这里。缺哪项、为什么缺、点了去哪，逐条写清楚。");
    sub.setTextColor(0xFF9A9AA0);
    sub.setTextSize(12.5f);
    sub.setPadding(0, dp(6), 0, dp(4));
    root.addView(sub);

    summary = new TextView(this);
    summary.setTextColor(0xFF9A9AA0);
    summary.setTextSize(12);
    summary.setPadding(0, 0, 0, dp(10));
    root.addView(summary);

    TextView env = new TextView(this);
    env.setText("当前环境：" + Compat.describe(this));
    env.setTextColor(0xFF6E6E74);
    env.setTextSize(11);
    env.setPadding(0, 0, 0, dp(14));
    root.addView(env);

    list = new LinearLayout(this);
    list.setOrientation(LinearLayout.VERTICAL);
    root.addView(list);

    TextView foot = new TextView(this);
    foot.setText("说明：助手只在此页列出的能力范围内工作；"
        + "写文件等本地操作不联网，只有你让它「问 AI」时才会把你的话发给模型服务。");
    foot.setTextColor(0xFF6E6E74);
    foot.setTextSize(11);
    foot.setPadding(0, dp(16), 0, dp(8));
    root.addView(foot);

    return scroll;
  }

  /** 一屏权限项的定义。 */
  private interface Item {
    String title();

    /** 用途说明（要给普通用户看懂的）。 */
    String why();

    /** 当前状态：见下面 STATE_*。 */
    int state();

    /** 点了做什么。 */
    void act();
  }

  private static final int STATE_OK = 0;
  private static final int STATE_MISSING = 1;
  private static final int STATE_UNSUPPORTED = 2;
  private static final int STATE_ADB = 3;

  private final List<Item> items = new ArrayList<>();

  private void refresh() {
    items.clear();
    buildItems();
    list.removeAllViews();
    int ok = 0;
    for (Item it : items) {
      if (it.state() == STATE_OK) ok++;
      list.addView(buildRow(it));
    }
    summary.setText("已就绪 " + ok + " / " + items.size() + " 项");
  }

  private void buildItems() {
    // 1) 无障碍服务 —— 助手的地基，没它什么都干不了
    items.add(new Item() {
      public String title() { return "无障碍服务（必需）"; }
      public String why() {
        return "读屏幕上的文字、替你点按钮。没有它，助手只能陪聊，不能替你在别的 App 里做事。";
      }
      public int state() {
        return com.deepseekharness.app.DshaAccessibilityService.instance() != null
            ? STATE_OK : STATE_MISSING;
      }
      public void act() { Compat.openAccessibilitySettings(PermissionHubActivity.this); }
    });

    // 2) 悬浮窗 —— 悬浮球与面板
    items.add(new Item() {
      public String title() { return "悬浮窗（建议开启）"; }
      public String why() {
        return "让悬浮球和助手面板浮在别的应用上面。不开的话只能用通知栏入口，切来切去很麻烦。";
      }
      public int state() {
        return Settings.canDrawOverlays(PermissionHubActivity.this) ? STATE_OK : STATE_MISSING;
      }
      public void act() { Compat.openOverlaySettings(PermissionHubActivity.this); }
    });

    // 3) 通知（Android 13+ 才有这个运行时权限；低版本不显示，避免"要求了并不存在的权限"）
    if (Compat.atLeastT()) {
      items.add(new Item() {
        public String title() { return "通知"; }
        public String why() {
          return "语音唤醒要靠一条常驻通知保活；助手出问题时也靠它告诉你原因。";
        }
        public int state() {
          return checkSelfPermission("android.permission.POST_NOTIFICATIONS")
              == PackageManager.PERMISSION_GRANTED ? STATE_OK : STATE_MISSING;
        }
        public void act() {
          if (Compat.atLeastT()) {
            requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 1001);
          }
        }
      });
    }

    // 4) 麦克风 —— 语音输入/唤醒
    items.add(new Item() {
      public String title() { return "麦克风（语音功能需要）"; }
      public String why() {
        return "语音输入和「你好大肥鱼」唤醒要用。不授权也能用，只是得手动打字。";
      }
      public int state() {
        return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED ? STATE_OK : STATE_MISSING;
      }
      public void act() {
        requestPermissions(new String[] {android.Manifest.permission.RECORD_AUDIO}, 1002);
      }
    });

    // 5) 通知使用权 —— 读通知（可选功能）
    items.add(new Item() {
      public String title() { return "通知使用权（可选）"; }
      public String why() {
        return "让助手能读到你收到的通知（比如帮你汇总未读消息）。是系统级绑定权限，只能去设置页手动开。";
      }
      public int state() {
        return notificationListenerEnabled() ? STATE_OK : STATE_MISSING;
      }
      public void act() { Compat.openNotificationListenerSettings(PermissionHubActivity.this); }
    });

    // 6) 电池优化白名单 —— 后台常驻
    items.add(new Item() {
      public String title() { return "后台常驻（建议开启）"; }
      public String why() {
        return "不开的话系统会在后台静默杀掉助手，「你好大肥鱼」就会时灵时不灵。";
      }
      public int state() {
        return ignoringBatteryOptimizations() ? STATE_OK : STATE_MISSING;
      }
      public void act() { Compat.openBatteryOptimizationSettings(PermissionHubActivity.this); }
    });

    // 7) 存储 —— 读配置文件（Android 11+ 需 MANAGE_EXTERNAL_STORAGE，只能 ADB 授）
    items.add(new Item() {
      public String title() { return "存储访问（配置读取）"; }
      public String why() {
        return "助手从 /sdcard/dsh/assistant-voice.json 读模型配置。"
            + (Compat.atLeastR()
                ? "Android 11 起这个权限只能由 ADB 授予（见下方说明）。"
                : "旧版 Android 走普通存储权限。");
      }
      public int state() {
        if (!Compat.atLeastR()) {
          return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
              == PackageManager.PERMISSION_GRANTED ? STATE_OK : STATE_MISSING;
        }
        // MANAGE_EXTERNAL_STORAGE 没有 api 可直接查，用"能否列出目录"实测
        try {
          return Environment.isExternalStorageManager() ? STATE_OK : STATE_ADB;
        } catch (Throwable t) {
          return STATE_ADB;
        }
      }
      public void act() {
        if (Compat.atLeastR()) {
          // 系统不给普通 App 弹这个框，只能引导到应用详情页 + 提示用 ADB
          android.widget.Toast.makeText(PermissionHubActivity.this,
              "Android 11+ 需要电脑执行：adb shell appops set "
                  + getPackageName() + " MANAGE_EXTERNAL_STORAGE allow",
              android.widget.Toast.LENGTH_LONG).show();
          Compat.openAppDetails(PermissionHubActivity.this);
        } else if (Build.VERSION.SDK_INT >= 23) {
          requestPermissions(
              new String[] {android.Manifest.permission.READ_EXTERNAL_STORAGE}, 1003);
        }
      }
    });
  }

  // ---------------------------------------------------------------- 单项 UI

  private View buildRow(Item it) {
    int st = it.state();

    LinearLayout card = new LinearLayout(this);
    card.setOrientation(LinearLayout.VERTICAL);
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(0xFF17171A);
    bg.setCornerRadius(dp(14));
    bg.setStroke(dp(1), st == STATE_OK ? 0xFF2E5E3A : 0xFF3A3A40);
    card.setBackground(bg);
    int p = dp(14);
    card.setPadding(p, p, p, p);
    LinearLayout.LayoutParams lp =
        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
    lp.bottomMargin = dp(10);
    card.setLayoutParams(lp);

    TextView t = new TextView(this);
    t.setText(it.title());
    t.setTextColor(0xFFFFFFFF);
    t.setTextSize(15);
    card.addView(t);

    TextView w = new TextView(this);
    w.setText(it.why());
    w.setTextColor(0xFF9A9AA0);
    w.setTextSize(12);
    w.setPadding(0, dp(6), 0, dp(10));
    card.addView(w);

    TextView state = new TextView(this);
    state.setTextSize(12.5f);
    if (st == STATE_OK) {
      state.setText("● 已开启");
      state.setTextColor(0xFF4CC38A);
    } else if (st == STATE_UNSUPPORTED) {
      state.setText("● 当前系统不支持");
      state.setTextColor(0xFF8A8A90);
    } else if (st == STATE_ADB) {
      state.setText("● 需要电脑 ADB 授权（下方有命令）");
      state.setTextColor(0xFFD9A441);
    } else {
      state.setText("● 未授权");
      state.setTextColor(0xFFD96A6A);
    }
    card.addView(state);

    if (st != STATE_OK) {
      Button b = new Button(this);
      b.setText(st == STATE_UNSUPPORTED ? "了解详情" : "去授权");
      b.setAllCaps(false);
      b.setOnClickListener(v -> {
        // 用户要求：先解释用途，再跳转。卡片上已经写了用途，这里再确认一次（Toast 轻提示）
        android.widget.Toast.makeText(this, shortWhy(it), android.widget.Toast.LENGTH_SHORT)
            .show();
        try {
          it.act();
        } catch (Throwable e) {
          android.util.Log.w("DSHA/Hub", "跳转失败: " + e);
          Compat.openAppDetails(this);
        }
      });
      LinearLayout.LayoutParams blp =
          new LinearLayout.LayoutParams(dp(112), ViewGroup.LayoutParams.WRAP_CONTENT);
      blp.topMargin = dp(8);
      b.setLayoutParams(blp);
      card.addView(b);
    }

    return card;
  }

  private String shortWhy(Item it) {
    return it.why().length() > 60 ? it.why().substring(0, 60) + "…" : it.why();
  }

  // ---------------------------------------------------------------- 状态判定

  private boolean notificationListenerEnabled() {
    try {
      String flat = Settings.Secure.getString(getContentResolver(),
          "enabled_notification_listeners");
      return flat != null && flat.contains(getPackageName());
    } catch (Throwable t) {
      return false;
    }
  }

  private boolean ignoringBatteryOptimizations() {
    try {
      if (Build.VERSION.SDK_INT < 23) return true; // 旧版本没这个概念，当已满足
      android.os.PowerManager pm =
          (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
      return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    } catch (Throwable t) {
      return false;
    }
  }

  private int dp(float v) {
    return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
  }

  /** 供外部（Onboarding / 面板）直接打开本页。 */
  static void open(Context ctx) {
    Intent i = new Intent(ctx, PermissionHubActivity.class);
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    ctx.startActivity(i);
  }
}
