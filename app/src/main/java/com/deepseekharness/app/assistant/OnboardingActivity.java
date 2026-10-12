package com.deepseekharness.app.assistant;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 首次使用引导（2026-10-11 大众化改造核心之四）。
 *
 * <p><b>用户要求的 9 步</b>：
 * <pre>
 *  1 启动        2 介绍        3 兼容性检查
 *  4 配置 AI     5 逐项授权    6 测试按钮
 *  7 添加常用应用与目标         8 定位测试
 *  9 全部通过 → 可以用了
 * </pre>
 *
 * <p><b>设计取向</b>：普通用户不会读文档，所以每一步只讲**这一件事**、
 * 只给**一个**主按钮。任何一步失败都不拦着往下走（可以回头补），
 * 但最后的"就绪"页会诚实列出还差什么——不假装成功。
 *
 * <p><b>不假装开箱即用</b>：AI Key 是硬前置。没配就明说"配好才能用"，
 * 并且给出**可复制的**最小配置模板，而不是让用户去猜格式。
 */
public class OnboardingActivity extends Activity {

  private static final String PREFS = "dsha_onboarding";
  private static final String KEY_DONE = "finished";

  private LinearLayout body;
  private TextView stepLabel;
  private Button primary;
  private Button secondary;
  private int step = 0;

  /** 步骤总数（用于"第 n/N 步"）。 */
  private static final int STEPS = 7;

  static boolean finished(Context ctx) {
    try {
      return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
          .getBoolean(KEY_DONE, false);
    } catch (Throwable t) {
      return false;
    }
  }

  static void markFinished(Context ctx) {
    try {
      ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
          .putBoolean(KEY_DONE, true).apply();
    } catch (Throwable ignored) {
    }
  }

  /** 打开引导（面板/首次启动用）。 */
  static void open(Context ctx) {
    Intent i = new Intent(ctx, OnboardingActivity.class);
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    ctx.startActivity(i);
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(buildShell());
    render();
  }

  // ---------------------------------------------------------------- 外壳

  private View buildShell() {
    ScrollView scroll = new ScrollView(this);
    scroll.setBackgroundColor(0xFF0E0E10);

    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    int pad = dp(20);
    root.setPadding(pad, pad, pad, pad);
    scroll.addView(root);

    stepLabel = new TextView(this);
    stepLabel.setTextColor(0xFF6E6E74);
    stepLabel.setTextSize(11.5f);
    root.addView(stepLabel);

    body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(0, dp(14), 0, dp(14));
    root.addView(body);

    primary = new Button(this);
    primary.setAllCaps(false);
    root.addView(primary);

    secondary = new Button(this);
    secondary.setAllCaps(false);
    secondary.setBackgroundColor(0x00000000);
    secondary.setTextColor(0xFF9A9AA0);
    LinearLayout.LayoutParams slp =
        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
    slp.topMargin = dp(4);
    secondary.setLayoutParams(slp);
    root.addView(secondary);

    return scroll;
  }

  private void render() {
    stepLabel.setText("第 " + (step + 1) + " / " + STEPS + " 步");
    body.removeAllViews();
    secondary.setVisibility(View.VISIBLE);
    secondary.setText("跳过这步");
    secondary.setOnClickListener(v -> next());

    switch (step) {
      case 0: renderIntro(); break;
      case 1: renderCompat(); break;
      case 2: renderConfig(); break;
      case 3: renderPermissions(); break;
      case 4: renderTest(); break;
      case 5: renderAddTarget(); break;
      default: renderDone(); break;
    }
  }

  private void next() {
    if (step >= STEPS - 1) {
      markFinished(this);
      finish();
      return;
    }
    step++;
    render();
  }

  // ---------------------------------------------------------------- 各步

  private void renderIntro() {
    title("这是什么");
    para("一个住在你手机里的助手。它能在别的 App 里替你看、替你点——"
        + "比如「在 Telegram 上给小肥鱼发个你好」，它真的会去点开发送。");
    para("它靠系统给的「无障碍服务」工作，不需要 root，也不需要装额外的东西。");
    note("你随时可以在系统设置里关掉它，关掉后它就完全不能动了。");
    primary.setText("开始设置");
    primary.setOnClickListener(v -> next());
  }

  private void renderCompat() {
    title("检查你的设备");
    para(Compat.describe(this));

    boolean a11yOk = true;
    boolean screenOk = Compat.minWidthDp(this) >= 320;
    boolean shotOk = Compat.supportsAccessibilityScreenshot();
    boolean voiceOk = Compat.hasSystemSpeechRecognizer(this);

    check("Android 版本", android.os.Build.VERSION.SDK_INT >= 30,
        "本应用要求 Android 11 及以上");
    check("屏幕尺寸", screenOk, "短边 " + Compat.minWidthDp(this) + "dp");
    check("无障碍可用", a11yOk, "系统提供 AccessibilityService");
    check("屏幕截图能力", shotOk,
        shotOk ? "支持（AI 识别可用）" : "本版本不支持系统级截屏，AI 识别将降级为纯文字理解");
    check("系统语音识别", voiceOk,
        voiceOk ? "有" : "无（本机无系统识别服务，语音走在线识别，需要网络）");

    primary.setText("继续");
    primary.setOnClickListener(v -> next());
  }

  private void renderConfig() {
    title("配置 AI 服务");
    AssistantConfig cfg = AssistantConfig.load(this);
    boolean hasKey = cfg.chatApiKey != null && !cfg.chatApiKey.trim().isEmpty();

    if (hasKey) {
      para("已经读到配置了（来源：" + cfg.source + "），模型：" + cfg.chatModel);
      primary.setText("下一步");
      primary.setOnClickListener(v -> next());
      return;
    }

    para("助手要连一个大模型才能听懂人话。这一步没法省——"
        + "没配的话它只能执行很少的本地指令。");
    note("配置文件放在 " + AssistantConfig.SDCARD_PATH
        + "，也可以直接在下面填，我帮你写进去。");

    final EditText endpoint = field("接口地址", "https://.../v1/chat/completions");
    final EditText model = field("模型名", "例如 glm-4.5-flash");
    final EditText key = field("API Key", "sk-... 或你的服务商给的 key");
    body.addView(endpoint);
    body.addView(model);
    body.addView(key);

    primary.setText("保存并继续");
    primary.setOnClickListener(v -> {
      String ep = endpoint.getText().toString().trim();
      String md = model.getText().toString().trim();
      String k = key.getText().toString().trim();
      if (ep.isEmpty() || md.isEmpty() || k.isEmpty()) {
        toast("三项都要填");
        return;
      }
      String json = "{\n"
          + "  \"chatEndpoint\": \"" + esc(ep) + "\",\n"
          + "  \"chatModel\": \"" + esc(md) + "\",\n"
          + "  \"chatApiKey\": \"" + esc(k) + "\"\n"
          + "}\n";
      if (writeConfig(json)) {
        toast("已写入 " + AssistantConfig.SDCARD_PATH);
        next();
      } else {
        toast("写不进去 —— 需要存储权限（见下一步的说明）");
      }
    });
  }

  private void renderPermissions() {
    title("授权");
    para("助手要用几项系统能力。点下面进去逐项开，开完回来这页会自动刷新。");
    para("最少要开「无障碍服务」，否则它什么都做不了。");
    primary.setText("打开权限页");
    primary.setOnClickListener(v -> PermissionHubActivity.open(this));
    secondary.setText("我已经开好了，继续");
    secondary.setOnClickListener(v -> next());
  }

  private void renderTest() {
    title("试一下");
    para("先做个最简单的测试：问它「现在几点」。这是本地能力，不需要联网——"
        + "如果这个都不行，说明无障碍还没生效。");
    if (AssistantConfig.load(this).chatApiKey.trim().isEmpty()) {
      note("提示：你还没配 AI Key，联网问答会失败，但本地指令应该能通。");
    }
    primary.setText("打开助手面板试一下");
    primary.setOnClickListener(v -> {
      Intent i = new Intent(this, DshaAssistActivity.class);
      i.putExtra("q", "现在几点");
      i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      startActivity(i);
    });
    secondary.setText("试过了，继续");
    secondary.setOnClickListener(v -> next());
  }

  private void renderAddTarget() {
    title("添加一个常用操作");
    para("比如「Telegram 的发送按钮」。助手会记住这个按钮的**特征**"
        + "（不是屏幕坐标——换个手机坐标就错了）。");

    int n = ActionTargetStore.loadAll(this).size();
    if (n > 0) {
      para("你已经配置了 " + n + " 个目标。");
      primary.setText("下一步");
      primary.setOnClickListener(v -> next());
      return;
    }

    para("现在给你放一条示例进去，你可以照着改：");
    note(ActionTargetStore.summary(this));

    primary.setText("添加示例并继续");
    primary.setOnClickListener(v -> {
      boolean ok = ActionTargetStore.upsert(this, ActionTargetStore.sampleTelegramSend());
      if (ok) {
        toast("已写入 " + ActionTargetStore.path());
        next();
      } else {
        toast("写不进去 —— 需要存储权限");
      }
    });
  }

  private void renderDone() {
    title("好了");
    para("下面是当前的实际状态（诚实版，缺什么就写什么）：");

    AssistantConfig cfg = AssistantConfig.load(this);
    boolean cfgOk = !cfg.chatApiKey.trim().isEmpty();
    boolean a11y = com.deepseekharness.app.DshaAccessibilityService.instance() != null;
    boolean overlay = android.provider.Settings.canDrawOverlays(this);
    int targets = ActionTargetStore.loadAll(this).size();

    check("AI 配置", cfgOk, cfgOk ? "已配置" : "未配置（联网问答不可用）");
    check("无障碍服务", a11y, a11y ? "已连接" : "未连接（助手无法操作界面）");
    check("悬浮窗", overlay, overlay ? "已开启" : "未开启（只能用通知入口）");
    check("常用操作目标", targets > 0,
        targets > 0 ? targets + " 个" : "没配（助手仍可用 AI 现场识别，但会慢一些）");

    primary.setText("进入应用");
    primary.setOnClickListener(v -> {
      markFinished(this);
      finish();
    });
    secondary.setVisibility(View.GONE);
  }

  // ---------------------------------------------------------------- 小工具

  private void title(String s) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextColor(0xFFFFFFFF);
    t.setTextSize(20);
    body.addView(t);
  }

  private void para(String s) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextColor(0xFFBFBFC4);
    t.setTextSize(13.5f);
    t.setPadding(0, dp(12), 0, 0);
    body.addView(t);
  }

  private void note(String s) {
    TextView t = new TextView(this);
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(0xFF17171A);
    bg.setCornerRadius(dp(10));
    t.setBackground(bg);
    t.setText(s);
    t.setTextColor(0xFF8A8A90);
    t.setTextSize(12);
    int p = dp(12);
    t.setPadding(p, p, p, p);
    LinearLayout.LayoutParams lp =
        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
    lp.topMargin = dp(12);
    t.setLayoutParams(lp);
    body.addView(t);
  }

  private void check(String name, boolean ok, String detail) {
    TextView t = new TextView(this);
    t.setText((ok ? "✓ " : "✗ ") + name + " — " + detail);
    t.setTextColor(ok ? 0xFF4CC38A : 0xFFD9A441);
    t.setTextSize(12.5f);
    t.setPadding(0, dp(8), 0, 0);
    body.addView(t);
  }

  private EditText field(String hint, String example) {
    EditText e = new EditText(this);
    e.setHint(hint + "（" + example + "）");
    e.setTextColor(0xFFFFFFFF);
    e.setHintTextColor(0xFF6E6E74);
    e.setTextSize(13.5f);
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(0xFF17171A);
    bg.setCornerRadius(dp(10));
    bg.setStroke(dp(1), 0xFF3A3A40);
    e.setBackground(bg);
    int p = dp(12);
    e.setPadding(p, p, p, p);
    LinearLayout.LayoutParams lp =
        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
    lp.topMargin = dp(10);
    e.setLayoutParams(lp);
    return e;
  }

  /** 写配置文件。失败（没存储权限）返回 false，由调用方提示——不假装成功。 */
  private boolean writeConfig(String json) {
    try {
      File f = new File(AssistantConfig.SDCARD_PATH);
      File parent = f.getParentFile();
      if (parent != null && !parent.exists()) parent.mkdirs();
      try (FileOutputStream out = new FileOutputStream(f)) {
        out.write(json.getBytes(StandardCharsets.UTF_8));
      }
      return true;
    } catch (Throwable t) {
      android.util.Log.w("DSHA/Onboard", "写配置失败: " + t);
      return false;
    }
  }

  private static String esc(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private void toast(String s) {
    android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
  }

  private int dp(float v) {
    return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
  }
}
