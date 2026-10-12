package com.deepseekharness.app.assistant;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ViewOutlineProvider;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.CredentialRead;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * V2 助手面板 —— 悬浮窗版（2026-10-07 重构）。
 *
 * <p><b>为什么放弃 Activity 改悬浮窗</b>（本轮实测证据，写给下一个改面板的人）：
 * 这台 EMUI 12 上，Activity 窗口里所有没画到的像素一律被 HWC 合成成纯黑
 * （SurfaceFlinger 里 transparentRegionHint 恒为空）——卡片圆角外、四周留缝全是黑边，
 * 用户反馈"像从黑边界里硬裁出来的"。给窗口 setFormat(TRANSLUCENT)、用截图垫背景都是死路：
 * 前者无效，后者是"定格的假背景"（用户点名否决：背景必须是活的，下面的页面还要能操作）。
 * 而用红框窗口做的对照实验证明：<b>TYPE_ACCESSIBILITY_OVERLAY 的透明像素能逐像素透出下层</b>
 * （check-overlay.py，4/4 PASS）。所以面板搬进无障碍悬浮窗。
 *
 * <p><b>悬浮窗带来三个 Activity 给不了的能力</b>：
 * <ol>
 *   <li>背景是活的：卡片外透出的就是实时屏幕，圆角是真圆角；
 *   <li>下面的应用保持焦点、保持可操作（FLAG_NOT_FOCUSABLE + FLAG_NOT_TOUCH_MODAL，
 *       面板显示时用户照样点得到面板以外的界面 —— 用户后续要把面板放到屏幕顶部，就是这个前提）；
 *   <li>读屏更干净：uiDumpUnderPanel 按"跳过非应用窗口"过滤，悬浮窗天然被跳过，
 *       不再像 Activity 面板那样需要按包名把自己排除。
 * </ol>
 *
 * <p><b>玻璃材质</b>：半透明实色 + 顶部高光 + 1px 亮边（bg_glass_card），
 * <b>不模糊</b> —— 本机 Android 11（API 30），系统背景模糊要 API 31 的
 * setBackgroundBlurRadius，实时模糊做不了；截图糊一片又违背"背景必须活"。
 * 等以后换 API 31+ 的机器，再把 blurBehind 接回来（AssistantBackdrop/FastBlur 留着备用）。
 *
 * <p>四条链路与旧 Activity 版一一对应：读屏 uiDumpUnderPanel → 提问上下文；
 * 本地指令 AssistantCommands 秒回；问答 AssistantApiClient（多轮 history）；
 * 语音 AssistantVoiceInput / AssistantSpeech。
 */
public final class AssistantPanel {

  private static final String TAG = "DSHA/AssistantPanel";

  private static final String MAIN_ACTIVITY_CLASS = "com.deepseekharness.app.ui.MainActivity";

  /**
   * 上下文尺寸现在跟着 {@link AssistantConfig} 走（外部 JSON 里的 {@code historyTurns} /
   * {@code screenChars}），默认值在 AssistantConfig：20 轮 + 12000 字。
   * 2026-10-07 从 4 轮 / 6000 字提上来 —— 用户挂的 DeepSeek 是百万上下文，
   * 4 轮短到"你上句问的啥它不记得了"，长页面也会读到半句话就被截断。
   */
  private static final int HISTORY_MAX = 200;
  private static final long VOICE_MAX_MS = 30_000L;

  /** 单例：面板同时只有一张。 */
  private static AssistantPanel current;

  /**
   * 「文本直达」待办问题：由 {@link #ask(String)} 写入，{@code attach()} 时消费。
   *
   * <p>存在的理由：语音是主路径，但语音会失败（噪声、口音、没开麦克风权限、ASR 抽风），
   * 而"打开某某应用"这类本机指令根本不需要嘴。给一条手也能触发的路，
   * 桌面快捷方式、宏 App（Tasker 之类）、ADB 调试全都能复用同一条链路。
   */
  private static volatile String pendingText;

  /**
   * 本次 ask 之后面板有没有亮过。给 {@code DshaAssistActivity} 的 4 秒兜底当判据用。
   *
   * <p><b>为什么必须加（2026-10-09 真机踩到）</b>：文本直达「把悬浮球弄回来」这类本机指令
   * 答完 900ms 就自动收面板；4 秒后兜底一看 visible()==false，把面板又强行弹回来，
   * 刚收干净挂上的悬浮球也被顶掉。兜底的本意只是"排队路径没走到 show"，
   * 所以必须区分"没亮过"和"亮过又正常收了"。
   */
  private static volatile boolean shownSinceAsk;

  /** {@code ask()} 之后面板是否亮过（亮过又收起也算亮过）。 */
  public static boolean shownSinceAsk() {
    return shownSinceAsk;
  }

  /** 入口：助手键 / 磁贴都走这里。已在屏上时相当于"再读一次屏幕"。 */
  public static void show(DshaAccessibilityService host) {
    final AssistantPanel p;
    if (current == null) current = new AssistantPanel(host);
    p = current;
    shownSinceAsk = true;
    // 排障用：面板"点了没反应"时先对这两行——能说明是压根没 show、还是 attach 提前退出了。
    android.util.Log.i(
        TAG, "show: host=" + (host != null) + " attached=" + p.attached + " leaving=" + p.leaving);
    // 面板一亮就把通知栏那条「待命」撤掉，别和面板同时占着用户的通知栏
    DshaWakeTile.clear(host);
    p.ui.post(p::attach);
  }

  /**
   * 带一段文本唤起面板：亮起后把这段文本当问题直接作答（不录音、先读屏这一步也跳过）。
   *
   * <pre>
   * adb shell am start -n com.dsh.client/com.deepseekharness.app.assistant.DshaAssistActivity \
   *     -e q "打开微信"
   * </pre>
   */
  public static void ask(DshaAccessibilityService host, String text) {
    if (text == null || text.trim().isEmpty()) return;
    // 新的一问：把"亮过没有"的计分清零，4 秒兜底靠它判断要不要补亮
    shownSinceAsk = false;
    pendingText = text.trim();
    // host 为 null 说明服务还没绑上（force-stop 后 4s 内实测就是这样）。
    // 这时候别自己 show —— 由 requestAssistantPanel 那条"排队补亮"路径统一出口，
    // 等 onServiceConnected 把面板挂起来时，attach() 会消费掉 pendingText。
    if (host == null) return;
    show(host);
  }

  /**
   * 面板当前是否已贴在屏上。public 是因为 {@code DshaAccessibilityService} 要拿它判断
   * 「系统语音助手刚起来了、要不要让 DSHA 面板接管」，而两个类不在同一个包里。
   */
  public static boolean visible() {
    return current != null && current.attached;
  }

  /**
   * 从别处（权限弹窗被拒、手电筒开失败等）往面板上塞一句提示。
   * 面板已经收了就静默吞掉 —— 弹窗自己会 finish，不该因为一句话又把面板拽回来。
   */
  static void note(String text) {
    final AssistantPanel p = current;
    if (p == null || text == null) return;
    p.ui.post(
        () -> {
          if (!p.attached) return;
          p.status.setVisibility(View.VISIBLE);
          p.status.setText(text);
          p.status.setTextColor(p.host.getColor(R.color.assistant_text_secondary));
        });
  }

  /**
   * 任务执行器（{@link AssistantTaskRunner}）的结果出口：把最终结果写进回答区。
   * 和 {@link #note} 的区别：note 是过程中的小字进度，这里是能朗读、能复制、能分享的正式回答。
   * 面板已经被收走就静默吞掉 —— 用户都不看了，别为一句"办好了"把面板再拽出来。
   */
  static void finishTask(String text) {
    final AssistantPanel p = current;
    if (p == null || text == null) return;
    p.ui.post(
        () -> {
          if (!p.attached) return;
          p.showAnswer(text);
        });
  }

  /** 麦克风权限授回来后由 AssistantMicActivity / 权限弹窗调，接着录音流程。 */
  static void onMicPermissionGranted() {
    final AssistantPanel p = current;
    if (p != null) p.ui.post(p::startVoice);
  }

  /**
   * 任务执行模式：给面板挂 {@code FLAG_NOT_TOUCHABLE}，让面板占的那块屏幕对触摸"透明"。
   *
   * <p>为什么要这个（2026-10-09）：任务跑起来时面板亮着、压住屏幕顶部，执行器按 dump 里的
   * 坐标做手势点按 —— 点到面板覆盖区就被面板吞掉，下面的按钮明明看得见就是点不着。
   * 设成不可触摸后手势直穿面板落到下层应用上；代价是任务期间用户拖不动面板（也收不了），
   * 但任务有步数上限、十几秒量级就出结果，这点代价总比执行器半瞎强。
   * 任务收尾（无论成败）必须调 {@code setTaskMode(false)} 还原，见 {@code AssistantTaskRunner.finish}。
   */
  static void setTaskMode(boolean on) {
    final AssistantPanel p = current;
    if (p == null) return;
    p.ui.post(
        () -> {
          if (p.root == null || p.wm == null || !p.attached) return;
          try {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) p.root.getLayoutParams();
            boolean now = (lp.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0;
            if (now == on) return;
            if (on) lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            else lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            p.wm.updateViewLayout(p.root, lp);
            android.util.Log.i(TAG, "taskMode -> " + on + "（面板对触摸透明）");
          } catch (Throwable ignored) {
          }
        });
  }

  /** 服务断开时立刻摘窗，别留一个没人管的悬浮窗。 */
  public static void teardown() {
    // 服务都没了，跑着的任务必须停 —— 不然执行器拿着过期读屏继续点用户的手机。
    AssistantTaskRunner.cancelAll();
    final AssistantPanel p = current;
    if (p == null) return;
    current = null;
    p.ui.post(
        () -> {
          p.leaving = true;
          p.stopSpeechAndVoice();
          p.removeViewNow();
          // 服务要走了，通知栏那条「待命」跟着摘掉（否则面板没了、按钮还挂着）
          DshaWakeTile.clear(p.host);
          // 悬浮球也不能留：服务一走它就成了没人管的悬浮窗
          DshaPebbleView.dismiss();
        });
  }

  // ===================== 实例状态 =====================

  private final DshaAccessibilityService host;
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private final Handler ui = new Handler(Looper.getMainLooper());

  private WindowManager wm;
  private View root;
  private View card;
  private View handle;
  private SiriOrbView orb;
  private TextView summary;
  private ImageButton refresh;
  private LinearLayout chips;
  private TextView status;
  private View resultScroll;
  private TextView result;
  private View resultActions;
  private TextView copy;
  private TextView speak;
  private TextView share;
  private TextView workbench;
  private EditText input;
  private TextView mic;
  private TextView send;

  private AssistantConfig config;
  private String screenText = "";
  private String screenApp = "";
  /** 读屏 0 项时的截屏兜底（微信等反自动化的应用），裸 base64。 */
  private volatile String screenShotB64;
  /** 上一次成功读到屏幕文本的时刻（「每次发问都重认一次屏幕」用它判新鲜）。 */
  private volatile long screenReadAt;
  private boolean attached;
  private boolean busy;
  private boolean reading;
  private boolean asking;
  private boolean leaving;

  private final List<AssistantApiClient.Message> history = new ArrayList<>();
  private AssistantSpeech speech;

  private final AssistantVoiceInput.Recorder recorder = new AssistantVoiceInput.Recorder();
  private final Runnable voiceTimeout = this::stopVoice;
  private final Runnable amplitudePoll =
      new Runnable() {
        @Override
        public void run() {
          if (!recorder.isRecording()) return;
          int amp = recorder.amplitude();
          orb.setLevel(Math.min(1f, amp / 9000f));
          ui.postDelayed(this, 120L);
        }
      };

  private AssistantPanel(DshaAccessibilityService host) {
    this.host = host;
  }

  // ===================== 挂窗 / 摘窗 =====================

  private void attach() {
    android.util.Log.i(TAG, "attach: enter attached=" + attached + " leaving=" + leaving);
    if (leaving) return;
    // 先把待办文本摘下来：下面两条分支都要用，不能只在"首次挂窗"那条路上读。
    final String pending = pendingText;
    pendingText = null;
    if (attached) {
      // 面板已经在屏上：助手键再按一次 = 重读屏幕（对应旧版 onNewIntent）。
      // 但带了文本（ask()）就是"再答一遍这句话"，不能把文本吞掉
      // —— 2026-10-07 实测：连着问两次，第二次毫无反应（文本一直挂在静态字段上没人消费）。
      if (pending != null && !pending.equals(input.getText() == null ? "" : input.getText().toString().trim())) {
        resetConversationState();
        // 必须重放入场动画：hide() 把 card 的 alpha 动画到 0 就没再动过，
        // 不重放的话面板"挂上了但整块透明"，看着跟没亮一样（2026-10-07 真机踩到）。
        card.setAlpha(0f);
        ui.postDelayed(this::playEnter, 50L);
        setHint(pending);
        ui.postDelayed(() -> runText(pending), 320L);
      } else {
        readScreen();
      }
      return;
    }
    if (root == null) inflate();
    resetConversationState();
    try {
      wm.addView(root, windowParams());
      android.util.Log.i(TAG, "attach: addView OK attached=" + attached + " pending=" + pending);
    } catch (Throwable t) {
      // 挂不上（服务正在解绑 / 悬浮窗权限）就记一笔再退出，别静默吞掉让人瞎猜
      android.util.Log.e(TAG, "attach: addView failed: " + t);
      return;
    }
    attached = true;
    // 面板一在屏上，那颗悬浮球就让位（两层浮层叠着要挡两层触摸区域）
    DshaPebbleView.dismiss();
    // 预热门 TTS：第一次点「朗读」不空等引擎连接（旧 Activity 版也是打开就 init）
    if (speech == null) {
      speech = new AssistantSpeech((speaking, problem) -> ui.post(() -> onSpeechState(speaking, problem)));
      speech.init(host, config);
    }
    // 从零入场：卡片隐身滑上来，内容错开淡入
    card.setAlpha(0f);
    setHint(host.getString(R.string.assistant_reading_screen));
    ui.postDelayed(this::playEnter, 50L);
    // 文本直达（ask()）：跳过读屏，直接把文本当问题答；答完自动收起。
    // 晚 320ms 是为了让入场动画先走完，不然文字会在卡片还在淡入时就顶上去，看着像闪一下。
    if (pending != null) {
      setHint(pending);
      ui.postDelayed(() -> runText(pending), 320L);
    } else {
      readScreen();
    }
  }

  /** 文本直达答问：本机指令优先，剩下的交给模型；本地能答的面板随即收起。 */
  private void runText(String question) {
    if (!attached || asking) return;
    AssistantCommands.Outcome o = localOutcome(question);
    android.util.Log.i(TAG, "runText: " + question + " -> handled=" + o.handled
        + " dismiss=" + o.dismiss + " reply=" + o.reply);
    remember(question, o.handled);
    if (o.handled) {
      showAnswer(o.reply == null ? "" : o.reply);
      orb.setMode(SiriOrbView.Mode.IDLE);
      // 本地指令（多半是"打开XX"）会自己把目标界面顶上来，面板留着反而挡视线。
      // 但任务型回答（后续操作跑起来了）必须留着 —— 用户正要看执行进度，
      // 900ms 自动收面板还会顺带把刚起的任务取消掉（2026-10-09 真机踩到）。
      if (!o.keepOpen) ui.postDelayed(this::hide, 900L);
      return;
    }
    askModelAndShow(question);
  }

  private void resetConversationState() {
    history.clear();
    screenText = "";
    screenApp = "";
    screenShotB64 = null;
    busy = reading = asking = false;
    input.setText("");
    resultScroll.setVisibility(View.GONE);
    resultActions.setVisibility(View.GONE);
    status.setVisibility(View.GONE);
    orb.setMode(SiriOrbView.Mode.IDLE);
    orb.setLevel(0.35f);
  }

  private WindowManager.LayoutParams windowParams() {
    WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
    lp.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
    lp.format = PixelFormat.TRANSLUCENT;
    // NOT_FOCUSABLE：面板不抢焦点，下面的应用保持可操作（用户点面板外的界面照常生效）。
    // NOT_TOUCH_MODAL：窗口（面板那一小条）以外的触摸全部穿透给下面的应用。
    // 点输入框时临时摘掉 NOT_FOCUSABLE 拉键盘，见 setFocusable()。
    lp.flags =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
    lp.width = WindowManager.LayoutParams.MATCH_PARENT;
    lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
    // 2026-10-07 起面板从底部搬到顶部：用户要"面板在顶端显示时还能操作底部的手机页面"。
    // 机制上不用改别的 —— NOT_FOCUSABLE + NOT_TOUCH_MODAL 两个 flag 跟位置无关，
    // 换 gravity 之后下面那层照样能点、照样能滑。
    lp.gravity = Gravity.TOP;
    // 悬浮窗不吃系统 insets，贴到屏幕最顶会盖住状态栏（时间/信号那一条）。
    // 自己往下让一位，别把电量信号糊住。
    lp.y = dp(24);
    lp.softInputMode =
        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
    return lp;
  }

  private void removeViewNow() {
    if (!attached) {
      if (root != null && wm != null) {
        try {
          wm.removeView(root);
        } catch (Throwable ignored) {
        }
      }
      return;
    }
    try {
      wm.removeView(root);
    } catch (Throwable ignored) {
    }
    attached = false;
  }

  /** 收起面板（带动画）。所有「关面板」入口都走它。 */
  private void hide() {
    if (!attached || leaving) return;
    leaving = true;
    stopSpeechAndVoice();
    setFocusable(false);
    // 面板一收，正在跑的"后续操作"立即停手 —— 用户都不看了，执行器不许再碰他的手机。
    AssistantTaskRunner.cancelAll();
    // 收起动画：一律从"此刻卡片停在哪儿"接着往下走，绝不回头。
    //
    // 历史（三次改动，别退回去）：
    // · 最早是一段 180ms accelerate 把 alpha 直接推到 0 —— 观感"啪一下没了"；
    // · 2026-10-07 改成两段式（先拉下一点褪到半透明、再彻底淡出）解决生硬；
    // · 但那版不管你是"点关闭"还是"拖把手松手"，目标点都硬写死成 h*0.14 和 dp(96)。
    //   拖松手时卡片已经在 dy≈120 了，第一段却把它**往上拽回** 34 —— 屏幕上就变成
    //   「手指那一段 + 自己往回缩一段 + 淡出一段」，慢放能明显看出是接起来的。
    //   2026-10-08 按起点分岔：拖过的只播一段（接着走），没拖过的才走两段缓冲。
    final int h = card.getHeight();
    final float from = card.getTranslationY();
    card.animate().cancel();
    final Runnable settle =
        () -> {
          removeViewNow();
          leaving = false;
          // 复位入场状态：translateY/scale 也要归零。
          // 不复位的话，下次挂窗时卡片停在"被压下去 45%"和 alpha≈0 的位置，
          // 面板会挂上但看着像没亮（下一次 show 靠 playEnter 重放，见 attach）。
          card.setAlpha(1f);
          card.setTranslationY(0f);
          card.setScaleX(1f);
          card.setScaleY(1f);
          // 收干净了 → 通知栏那条「待命」挂回来，下次随手一点就能叫助手
          DshaWakeTile.post(host);
          // 也把悬浮球挂回来（面板不在屏上时它才是入口）
          DshaPebbleView.ensure(host);
        };
    if (from > dp(12)) {
      // 拖着关：手指已经把它带下去了，接着往下沉 —— 只有一段，跟手不断。
      card.animate()
          .alpha(0f)
          .translationY(from + dp(64))
          .setDuration(180L)
          .setInterpolator(new android.view.animation.AccelerateInterpolator(1.4f))
          .withEndAction(settle)
          .start();
      return;
    }
    // 点关闭 / 到点自动收：起点在原位，两段缓冲，观感是"卸一口气再走"。
    final float far = dp(96);
    card.animate()
        .translationY(h > 0 ? h * 0.14f : dp(26))
        .alpha(0.5f)
        .setDuration(170L)
        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.7f))
        .withEndAction(
            () ->
                card.animate()
                    .alpha(0f)
                    .translationY(far)
                    .setDuration(200L)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator(1.5f))
                    .withEndAction(settle)
                    .start())
        .start();
  }

  /** 停朗读、停录音（摘窗/收起时都要）。 */
  private void stopSpeechAndVoice() {
    ui.removeCallbacks(voiceTimeout);
    ui.removeCallbacks(amplitudePoll);
    if (recorder.isRecording()) recorder.stop();
    if (speech != null && speech.isSpeaking()) speech.stop();
    if (speech == null) return;
    speech.shutdown();
    speech = null;
  }

  // ===================== 视图与手势 =====================

  private void inflate() {
    wm = (WindowManager) host.getSystemService(Context.WINDOW_SERVICE);
    config = AssistantConfig.load(host);
    root = LayoutInflater.from(host).inflate(R.layout.activity_dsha_assistant, null);
    card = root.findViewById(R.id.assistant_card);
    handle = root.findViewById(R.id.assistant_handle);
    orb = root.findViewById(R.id.assistant_orb);
    summary = root.findViewById(R.id.assistant_screen_summary);
    refresh = root.findViewById(R.id.assistant_refresh_screen);
    chips = root.findViewById(R.id.assistant_chips);
    status = root.findViewById(R.id.assistant_status);
    resultScroll = root.findViewById(R.id.assistant_result_scroll);
    result = root.findViewById(R.id.assistant_result);
    resultActions = root.findViewById(R.id.assistant_result_actions);
    copy = root.findViewById(R.id.assistant_copy);
    speak = root.findViewById(R.id.assistant_speak);
    share = root.findViewById(R.id.assistant_share);
    workbench = root.findViewById(R.id.assistant_workbench);
    input = root.findViewById(R.id.assistant_input);
    mic = root.findViewById(R.id.assistant_mic);
    send = root.findViewById(R.id.assistant_send);

    // 卡片抬起：90% 实玻璃得有投影才像"浮在活背景上"，而不是贴在屏幕上。
    // 圆角走 ViewOutlineProvider（API 21+，不需要 setClipToOutline 的 view 树裁剪，
    // 也不会像 round clip 那样把卡片外的像素切黑）。
    card.setOutlineProvider(new ViewOutlineProvider() {
      @Override public void getOutline(View view, Outline out) {
        out.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(32f));
      }
    });
    card.setClipToOutline(true);
    card.setElevation(dp(16f));
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      card.setOutlineAmbientShadowColor(0x66000000);
      card.setOutlineSpotShadowColor(0x4D000000);
    }

    // 留缝区（根布局兜底触摸）点一下收面板
    root.setOnClickListener(v -> hide());
    refresh.setOnClickListener(v -> readScreen());
    send.setOnClickListener(v -> sendQuestion());
    mic.setOnClickListener(v -> toggleVoice());
    copy.setOnClickListener(v -> copyAnswer());
    speak.setOnClickListener(v -> toggleSpeak());
    share.setOnClickListener(v -> shareAnswer());
    workbench.setOnClickListener(v -> openWorkbench());
    // NOT_FOCUSABLE 下键盘要手动请：点输入框 → 摘掉 NOT_FOCUSABLE → 拉键盘
    input.setOnClickListener(
        v -> {
          setFocusable(true);
          input.post(
              () -> {
                input.requestFocus();
                input.setSelection(input.getText() == null ? 0 : input.getText().length());
                InputMethodManager imm =
                    (InputMethodManager) host.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
              });
        });
    input.setOnEditorActionListener(
        (v, actionId, event) -> {
          boolean isSend =
              event == null
                  ? true
                  : event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                      && event.getAction() == KeyEvent.ACTION_DOWN;
          if (isSend) {
            sendQuestion();
            return true;
          }
          return false;
        });
    // 拿到焦点期间用返回键收面板/收键盘
    root.setOnKeyListener(
        (v, keyCode, event) -> {
          if (event == null || event.getAction() != KeyEvent.ACTION_DOWN) return false;
          if (keyCode == KeyEvent.KEYCODE_BACK) {
            InputMethodManager imm =
                (InputMethodManager) host.getSystemService(Context.INPUT_METHOD_SERVICE);
            boolean imeShown = false;
            try {
              imeShown = imm != null && imm.isAcceptingText();
            } catch (Throwable ignored) {
            }
            if (imeShown) {
              hideKeyboard();
              setFocusable(false);
            } else {
              hide();
            }
            return true;
          }
          return false;
        });

    bouncy(send);
    bouncy(mic);
    bouncy(refresh);
    bouncy(copy);
    bouncy(speak);
    bouncy(share);
    bouncy(workbench);
    setupSwipeToDismiss();
  }

  /** 焦点开关：NOT_FOCUSABLE ↔ 可聚焦。拉键盘要焦点，平时不抢下面应用的焦点。 */
  private void setFocusable(boolean focusable) {
    if (root == null || wm == null || !attached) return;
    try {
      WindowManager.LayoutParams lp = (WindowManager.LayoutParams) root.getLayoutParams();
      boolean now = (lp.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0;
      if (now == focusable) return;
      if (focusable) lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
      else lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
      wm.updateViewLayout(root, lp);
    } catch (Throwable ignored) {
    }
  }

  private void playEnter() {
    if (!attached || leaving) return;
    int h = card.getHeight() > 0 ? card.getHeight() : dp(240);
    card.setTranslationY(h * 0.6f);
    card.setScaleX(0.98f);
    card.setScaleY(0.98f);
    card.animate()
        .alpha(1f)
        .translationY(0f)
        .scaleX(1f)
        .scaleY(1f)
        .setDuration(340L)
        .setInterpolator(new OvershootInterpolator(0.8f))
        .start();
    float start = dp(10);
    for (int i = 0; i < ((LinearLayout) card).getChildCount(); i++) {
      View child = ((LinearLayout) card).getChildAt(i);
      child.setAlpha(0f);
      child.setTranslationY(start);
      child.animate()
          .alpha(1f)
          .translationY(0f)
          .setStartDelay(60L + 35L * i)
          .setDuration(260L)
          .start();
    }
  }

  /** 顶部把手：往下拖一拖就收起。只认向下，向上不动（否则和输入行抢事件）。 */
  private void setupSwipeToDismiss() {
    if (handle == null) return;
    final float[] startY = {0f};
    handle.setOnTouchListener(
        (v, e) -> {
          switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
              startY[0] = e.getRawY();
              return true;
            case MotionEvent.ACTION_MOVE:
              float dy = e.getRawY() - startY[0];
              if (dy > 0) card.setTranslationY(dy);
              return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
              float moved = e.getRawY() - startY[0];
              if (moved > dp(70)) {
                hide();
              } else {
                // 没拽够就弹回原位：用减速曲线，跟收起那两段是一个手感
                card.animate()
                    .translationY(0f)
                    .setDuration(190L)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.7f))
                    .start();
              }
              return true;
            default:
              return true;
          }
        });
  }

  /** 按下缩一下、松手弹回来：手机上的「手感」。返回 false 保证 click 还能触发。 */
  private void bouncy(View v) {
    if (v == null) return;
    v.setOnTouchListener(
        (view, e) -> {
          switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
              view.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90L).start();
              break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
              view.animate().scaleX(1f).scaleY(1f).setDuration(150L).start();
              break;
            default:
              break;
          }
          return false;
        });
  }

  // ===================== 读屏（R2） =====================

  private void setHint(String text) {
    summary.setText(text);
  }

  /** 主动拉一次当前屏幕文字；结果只留在内存里。 */
  private void readScreen() {
    if (reading || !attached) return;
    reading = true;
    setHint(host.getString(R.string.assistant_reading_screen));
    summary.setTextColor(host.getColor(R.color.assistant_text_secondary));
    io.execute(
        () -> {
          final String dump;
          try {
            dump = DshaAccessibilityService.uiDumpUnderPanel();
          } catch (Throwable t) {
            ui.post(() -> applyScreen("", "", "读屏失败：" + t));
            return;
          }
          final String app = firstLineValue(dump, "窗口应用:");
          final int count = countEntries(dump);
          final boolean blocked = dump != null && dump.startsWith("[ERR]");
          // 敏感应用防护（用户 2026-10-07 立规）：支付宝/银行/密码类应用属于敏感目录，
          // 面板不读取、不操作 —— 屏幕文字一行都不进内存、不给模型，摘要只显示保护提示。
          // 微信在白名单里（用户拍板：要能读能操作），allowedPackages 还能从
          // /sdcard/dsh/assistant-voice.json 追加，不用重编 APK。
          final boolean guarded = AssistantGuard.isProtected(app, config.allowedPackages);
          ui.post(
              () -> {
                reading = false;
                if (!attached) return;
                if (guarded) {
                  screenText = "";
                  screenApp = app;
                  setHint(AssistantGuard.notice(host, app));
                  summary.setTextColor(host.getColor(R.color.assistant_text_secondary));
                  buildChips();
                  return;
                }
                if (blocked) {
                  screenText = "";
                  screenApp = "";
                  setHint(dump);
                  summary.setTextColor(host.getColor(R.color.assistant_text_secondary));
                  buildChips();
                  return;
                }
                screenApp = app;
                String cut = dump == null ? "" : dump;
                if (cut.length() > screenMaxChars()) cut = cut.substring(0, screenMaxChars());
                screenText = cut;
                screenShotB64 = null;
                applyScreen(app, screenText, null);
                if (count == 0) {
                  screenText = "";
                  // 读屏 0 项：微信等应用在 app 层屏蔽了无障碍节点（2026-10-07 实测，
                  // 连 shell 特权 uiautomator 拿到的都是空树；同名单的 QQ 却可读）。
                  // 兜底 = 无障碍截屏 → 视觉模型看图回答。
                  // 注意：悬浮窗版的截屏会带上面板自己的卡片，提问的提示词里已交代模型忽略。
                  captureScreenFallback(app);
                }
                buildChips();
              });
        });
  }

  /**
   * 把用户原话记进「习惯本子」（{@link AssistantMemory}）。
   *
   * <p>这是「自我优化」里唯一不靠模型的一环：写出文件的是用户自己敲过的字，
   * 攒够了面板就把出现次数最多的那几句顶到推荐位最前。断网生效、不会编。
   *
   * <p>走 io 线程：一次记录会顺手 trim（要先把全文读一遍再截尾），
   * 不能堵在主线程上把输入行卡一下。
   */
  private void remember(String question, boolean local) {
    if (question == null || question.trim().isEmpty()) return;
    final String q = question.trim();
    final boolean isLocal = local;
    // screenApp 在主线程上取下来再丢给 io，别让 io 线程读一个主线程写的普通字段
    final String app = screenApp;
    io.execute(
        () -> {
          try {
            AssistantMemory.record(host, q, isLocal, app);
          } catch (Throwable ignored) {
          }
        });
  }

  /** 读屏 0 项时的「看图识别」兜底：截一张屏存 base64，摘要行告诉用户提问即可。 */
  private void captureScreenFallback(String app) {
    final String appName = label(app);
    setHint("当前屏幕：" + appName + " · 该应用限制了读字，正在截屏…");
    io.execute(
        () -> {
          // 截屏前先把面板自己摘掉（2026-10-08 用户原话：面板自己那层不要出现在给模型看的图里）。
          // 悬浮窗是浮在下层之上的，摘掉不黑屏 —— 背景照旧透着下面的应用，
          // 所以这几百毫秒只是"面板抽走再回来"，用户基本无感，但模型看到的就是干净的下层。
          // validRun 也不能再判 attached：这时候我们正是把 attached 打掉的那一方。
          final boolean wasAttached = attached;
          if (wasAttached) detachForShot();
          String shot;
          try {
            shot = DshaAccessibilityService.uiScreenshot(() -> true, true);
          } finally {
            if (wasAttached) attachAfterShot();
          }
          String b64 = null;
          try {
            b64 = new org.json.JSONObject(shot).optString("data", null);
          } catch (Throwable ignored) {
          }
          if (b64 == null || b64.isEmpty()) b64 = null;
          final String found = b64;
          ui.post(
              () -> {
                if (!attached) return;
                screenShotB64 = found;
                setHint(
                    found == null
                        ? "当前屏幕：" + appName + " · 该应用限制了读字，截屏也没成功，只能盲问了"
                        : "当前屏幕："
                            + appName
                            + " · 该应用限制了读字，已截屏——直接提问，助手看图回答");
                buildChips();
              });
        });
  }

  private void applyScreen(String pkg, String text, String problem) {
    reading = false;
    if (problem == null) screenReadAt = System.currentTimeMillis();
    if (problem != null) {
      setHint(problem);
      summary.setTextColor(host.getColor(R.color.assistant_text_secondary));
      return;
    }
    int count = countEntries(text);
    setHint("当前屏幕：" + label(pkg) + " · " + count + " 项可读内容");
    summary.setTextColor(host.getColor(R.color.assistant_text_secondary));
  }

  private static int countEntries(String dump) {
    if (dump == null) return 0;
    int n = 0;
    for (String line : dump.split("\n")) {
      if (line.startsWith("[")) n++;
    }
    return n;
  }

  private static String firstLineValue(String dump, String prefix) {
    if (dump == null) return "";
    for (String line : dump.split("\n")) {
      if (line.startsWith(prefix)) {
        return line.substring(prefix.length()).trim();
      }
    }
    return "";
  }

  /**
   * 截屏前摘窗，并且**等它真摘干净了才返回**（2026-10-08 修的一个竞态）。
   *
   * <p>原来只写 {@code ui.post(() -> removeViewNow())} 就马上去截屏 —— 但 {@code ui.post} 只是把一段
   * 代码排进主线程队列，而截屏那边是 {@code latch} 异步回来的，两边谁先谁后没有保证。
   * 摘窗排在截屏之后时，截出来的图里面板还在，等于"忽略自身界面"这个需求白做了，
   * 而且这种 bug 只在慢机上复现，看日志完全看不出来。
   *
   * <p>现在确定顺序：主线程上摘窗 → 等 latch 确认摘完 → 再睡 60ms → 才返回。
   * 那 60ms 不是拍脑袋加的：摘窗是改窗口栈，要等下一帧合成，那层才真的从屏幕上消失。
   * 这段总开销 ~80ms，比面板在这几百毫秒里被拍进图里划算得多。
   */
  private void detachForShot() {
    try {
      java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
      ui.post(
          () -> {
            try {
              if (attached) removeViewNow();
            } finally {
              done.countDown();
            }
          });
      if (!done.await(1500L, java.util.concurrent.TimeUnit.MILLISECONDS)) return;
      Thread.sleep(60L);
    } catch (Throwable ignored) {
    }
  }

  /** 截完屏把面板挂回来（回屏内容都还在 ui 上，只是窗暂时摘了）。 */
  private void attachAfterShot() {
    try {
      java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
      ui.post(
          () -> {
            try {
              if (!leaving) reattachAfterShot();
            } finally {
              done.countDown();
            }
          });
      done.await(1500L, java.util.concurrent.TimeUnit.MILLISECONDS);
    } catch (Throwable ignored) {
    }
  }

  private void reattachAfterShot() {
    try {
      if (attached || root == null || wm == null) return;
      wm.addView(root, windowParams());
      attached = true;
      android.util.Log.i(TAG, "reattachAfterShot: 截屏后挂回");
      card.setAlpha(0f);
      ui.postDelayed(this::playEnter, 40L);
    } catch (Throwable t) {
      // 摘窗期间正好被收起来了：别硬挂，让它自然收
      android.util.Log.w(TAG, "reattachAfterShot 没挂上: " + t);
    }
  }

  private String label(String pkg) {
    if (TextUtils.isEmpty(pkg) || "未知".equals(pkg)) return "当前界面";
    try {
      PackageManager pm = host.getPackageManager();
      ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
      CharSequence name = pm.getApplicationLabel(info);
      return name == null || name.length() == 0 ? pkg : name.toString();
    } catch (Throwable t) {
      return pkg;
    }
  }

  // ===================== 建议 chips =====================

  private void buildChips() {
    if (chips == null) return;
    chips.removeAllViews();
    boolean hasScreen = screenText != null && !screenText.isEmpty();
    boolean hasVision = screenShotB64 != null && !screenShotB64.isEmpty();
    // 把屏幕文本一并给进去：推荐里有一层是「照着这页现想」出来的（AssistantSuggestions.fromScreen）
    List<String> items =
        AssistantSuggestions.build(host, screenApp, label(screenApp), hasScreen, hasVision,
            screenText);
    // 通知使用权开了而且真有通知：补一颗「谁在找我」。
    // 放在最后是因为 chips 是横排的、一屏露得出 2 颗 —— 越靠前权重越高，
    // 这颗只在"通知真的在那儿"时才出现，顶掉一个"按页现想"更贴当下场景。
    if (items.size() < 6 && DshaNotificationListener.isConnected()
        && DshaNotificationListener.hasAny()) {
      items.add("这几条谁在找我");
    }
    for (String item : items) {
      TextView t = new TextView(host);
      t.setText(item);
      t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
      t.setTextColor(host.getColor(R.color.assistant_text_secondary));
      t.setBackgroundResource(R.drawable.bg_glass_chip);
      int padV = dp(7);
      int padH = dp(13);
      t.setPadding(padH, padV, padH, padV);
      LinearLayout.LayoutParams lp =
          new LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      lp.setMarginEnd(dp(8));
      t.setLayoutParams(lp);
      t.setOnClickListener(
          v -> {
            input.setText(item);
            input.setSelection(item.length());
            sendQuestion();
          });
      bouncy(t);
      chips.addView(t);
    }
  }

  // ===================== 问答 =====================

  private void sendQuestion() {
    if (asking || !attached) return;
    String question = input.getText() == null ? "" : input.getText().toString().trim();
    if (question.isEmpty()) question = "用一两句话说明当前屏幕上最主要的内容。";
    final String asked = question;

    // ① 先看是不是本机就能秒回的指令（时间/计算/开应用/闹钟/打电话…）
    AssistantCommands.Outcome o = localOutcome(asked);
    // 「记下你的说法」：本机答的和问模型的都记一笔，
    // 攒够了下次唤醒，它自己就把你最常用的那句顶到推荐位最前面。
    remember(asked, o.handled);
    if (o.handled) {
      showAnswer(o.reply == null ? "" : o.reply);
      orb.setMode(SiriOrbView.Mode.IDLE);
      // 本地指令常常是"打开XX"这种，面板自己就该让位（旧版延迟 900ms 收起）；
      // 任务型回答（后续操作跑起来了）留着，用户要看进度，也免得把任务顺手取消。
      if (!o.keepOpen && o.dismiss) ui.postDelayed(this::hide, 900L);
      return;
    }

    // ② 否则走模型
    askModelAndShow(asked);
  }

  /** 本机指令分支（时间/计算/开应用/应用列表/闹钟/打电话…）。纯函数，返回是否接住了。 */
  private AssistantCommands.Outcome localOutcome(String asked) {
    // 走 runGuarded：本地指令抛异常不能把整个 app 进程（连带无障碍服务）带走。
    // 详见 AssistantCommands.runGuarded 上面那段真机事故记录。
    return AssistantCommands.runGuarded(host, asked);
  }

  /**
   * 屏幕文本是不是过期了：没读过 / 超过 {@link #SCREEN_STALE_MS} / 之前压根没读到东西。
   *
   * <p>2026-10-08 加「每次发问都重认一次屏幕」：之前只在面板**首次挂窗**时读一次，
   * 之后连着问几轮，喂给模型的还是那页旧字（用户原话：每次我发起提问，能不能自动识别一次）。
   */
  private static final long SCREEN_STALE_MS = 4000L;

  private boolean needFreshScreen() {
    if (screenReadAt <= 0) return true;
    return System.currentTimeMillis() - screenReadAt > SCREEN_STALE_MS;
  }

  /** 模型问答分支：置思考态 → 后台问 → 主线程把答案推上去。 */
  private void askModelAndShow(String asked) {
    hideKeyboard();
    setFocusable(false);
    // 先补一次读屏再发问。本机指令那条路不插（见 sendQuestion 的 ①），
    // 所以这里的延迟只在"真的问模型"时才付一次，几百毫秒。
    if (needFreshScreen()) {
      if (!reading) readScreen();
      status.setVisibility(View.VISIBLE);
      status.setText("正在重新识别当前屏幕…");
      status.setTextColor(host.getColor(R.color.assistant_accent));
      ui.postDelayed(() -> runModel(asked), 300L);
      return;
    }
    runModel(asked);
  }

  private void runModel(String asked) {
    asking = true;
    busy = true;
    resultActions.setVisibility(View.GONE);
    resultScroll.setVisibility(View.GONE);
    status.setVisibility(View.VISIBLE);
    status.setText(R.string.assistant_thinking);
    status.setTextColor(host.getColor(R.color.assistant_accent));
    orb.setMode(SiriOrbView.Mode.THINKING);

    io.execute(
        () -> {
          String answer;
          try {
            answer = askModel(asked);
          } catch (Throwable t) {
            answer = "⚠ " + AssistantApiClient.friendlyError(t);
          }
          final String text = answer;
          ui.post(
              () -> {
                asking = false;
                busy = false;
                if (!attached) return;
                pushHistory(asked, text);
                status.setVisibility(View.GONE);
                resultScroll.setVisibility(View.VISIBLE);
                result.setText(text);
                result.setAlpha(0f);
                result.setTranslationY(dp(6));
                result.animate().alpha(1f).translationY(0f).setDuration(220L).start();
                resultActions.setVisibility(View.VISIBLE);
                orb.setMode(SiriOrbView.Mode.IDLE);
              });
        });
  }

  /** 多轮上下文留几轮（外部可配，缺省 AssistantConfig 的默认值）。 */
  private int historyTurns() {
    return config == null
        ? AssistantConfig.HISTORY_TURNS_DEFAULT
        : Math.max(1, Math.min(HISTORY_MAX, config.historyTurns));
  }

  /** 这句话是不是在问通知栏（跟 AssistantCommands.wantsNotify 一个口径，问模型时再判一次）。 */
  private static boolean mentionsNotify(String question) {
    if (question == null) return false;
    String l = question.toLowerCase(java.util.Locale.ROOT);
    if (!l.contains("通知") && !l.contains("找我") && !l.contains("消息")) return false;
    return l.contains("找") || l.contains("消息") || l.contains("有啥") || l.contains("什么")
        || l.contains("哪些") || l.contains("几条");
  }

  /** 一次喂模型的屏幕文本上限（外部可配，缺省 AssistantConfig 的默认值）。 */
  private int screenMaxChars() {
    return config == null
        ? AssistantConfig.SCREEN_CHARS_DEFAULT
        : Math.max(500, Math.min(24000, config.screenChars));
  }

  /** 组装「屏幕上下文 + 用户问题」发给模型。 */
  private String askModel(String question) throws Exception {
    String key;
    if (config.chatApiKey == null || config.chatApiKey.trim().isEmpty()) {
      CredentialRead read = new ConfigStore(host).readApiKey();
      if (read.state == CredentialRead.State.AVAILABLE) key = read.requireValue();
      else if (read.state == CredentialRead.State.NOT_CONFIGURED)
        throw new IllegalStateException("DSHA 还没配置 API Key（设置 → 接入），或改 " + AssistantConfig.SDCARD_PATH);
      else throw new IllegalStateException(ConfigStore.credentialMessage(read));
    } else {
      key = config.chatApiKey;
    }

    StringBuilder user = new StringBuilder();
    String shot = screenShotB64;
    boolean useVision = shot != null && !shot.isEmpty() && (screenText == null || screenText.isEmpty());
    if (screenText != null && !screenText.isEmpty()) {
      user.append("【当前屏幕：").append(label(screenApp)).append("】\n");
      user.append(screenText).append('\n');
    } else if (useVision) {
      user.append("【当前屏幕：").append(label(screenApp)).append("】");
      user.append("（这个应用不让助手读取文字，所以给你了一张屏幕截图）\n");
      user.append("注意：截图底部深色的卡片是助手面板自己的界面，忽略它。\n");
    } else {
      user.append("（这次没有带屏幕内容）\n");
    }
    // 通知栏（2026-10-07 起）：用户问"谁在找我 / 通知有啥"时，把最近的几条一起给模型，
    // 免得它凭空猜。没开通知使用权 / 问的是别的事就不带 —— 通知里可能有验证码和人名，
    // 不追问就不读，跟读屏那套隐私口径一个样。
    if (mentionsNotify(question) && DshaNotificationListener.isConnected()) {
      String digest = DshaNotificationListener.digest(5);
      if (!digest.isEmpty()) {
        user.append("\n【最近的通知（应用 标题：正文）】").append(digest).append('\n');
      }
    }
    user.append("\n【我的问题】").append(question);

    String system =
        "你是跑在用户安卓手机里的语音助手，说话简短、直接、口语化，通常两三句话说完。"
            + "用户消息里会带上他当前手机屏幕上的内容（文字或截图）；"
            + "问题跟屏幕有关就以屏幕内容为准，屏幕里没有的信息别编，直说不知道。"
            + "不要用 Markdown 标题和长列表，手机上看着累。";

    // 命中用户自己攒的技能（AssistantSkills）→ 把那个技能的**要求**并进去。
    // 这就是「自我修正」的落点：技能不是一段话术，是下次回答这个话题时的行为约束。
    // 放在 try 里：读技能是文件 IO，读不到就当没这回事，不能把问答干崩。
    try {
      AssistantSkills.Skill hit = AssistantSkills.match(host, question);
      if (hit != null && !hit.style.isEmpty()) {
        android.util.Log.i(TAG, "skill hit: name=" + hit.name);
        system += " 另外这个问题命中一个叫「" + hit.name + "」的技能，回答时照它的要求来："
            + hit.style + "。";
      }
    } catch (Throwable ignored) {
    }

    if (useVision) {
      // 微信等反自动化的应用：截屏走视觉模型（glm-4v-flash 免费档，可换）
      return AssistantApiClient.chatVision(
          config.chatEndpoint,
          config.chatVisionModel,
          key,
          system,
          user.toString(),
          shot,
          AssistantApiClient.MAX_TOKENS);
    }

    List<AssistantApiClient.Message> messages = new ArrayList<>();
    messages.add(new AssistantApiClient.Message("system", system));
    int keep = historyTurns() * 2;
    int from = Math.max(0, history.size() - keep);
    messages.addAll(new ArrayList<>(history.subList(from, history.size())));
    messages.add(new AssistantApiClient.Message("user", user.toString()));

    return AssistantApiClient.chat(
        config.chatEndpoint, config.chatModel, key, messages, AssistantApiClient.MAX_TOKENS);
  }

  private void pushHistory(String question, String answer) {
    history.add(new AssistantApiClient.Message("user", question));
    history.add(new AssistantApiClient.Message("assistant", answer));
    while (history.size() > historyTurns() * 2) history.remove(0);
  }

  private void showAnswer(String text) {
    status.setVisibility(View.GONE);
    resultScroll.setVisibility(View.VISIBLE);
    result.setAlpha(0f);
    result.setTranslationY(dp(6));
    result.animate().alpha(1f).translationY(0f).setDuration(220L).start();
    result.setText(text);
    resultActions.setVisibility(View.VISIBLE);
    autoSpeakIfWanted(text);
  }

  /**
   * 「主动发声」开着就把回答念出来（2026-10-09 用户需求）。
   *
   * <p><b>默认必须静音</b>：用户原话"唤醒时默认是不出声的，只是我出声，它单单听着我就行"。
   * 所以这里先读 {@link AssistantVoiceMode}，SILENT 直接返回 —— 现状（只有手点喇叭才念）不变。
   * 说一句「跟我说话 / 把声音调到最大跟我说话」升档后，这里才会自动开口。
   *
   * <p>报错（"⚠" 开头）不念：麦克风没权限、网络挂了这类提示念出来既尴尬又吵。
   */
  private void autoSpeakIfWanted(String text) {
    if (text == null || text.isEmpty()) return;
    if (text.startsWith("⚠")) return;
    if (AssistantVoiceMode.get(host) == AssistantVoiceMode.SILENT) return;
    if (speech == null) {
      speech = new AssistantSpeech((speaking, problem) -> ui.post(() -> onSpeechState(speaking, problem)));
      speech.init(host, config);
    }
    if (speech.isReady()) speech.speak(text);
  }

  private void copyAnswer() {
    CharSequence text = result.getText();
    if (text == null || text.length() == 0) return;
    try {
      ClipboardManager cm =
          (ClipboardManager) host.getSystemService(Context.CLIPBOARD_SERVICE);
      cm.setPrimaryClip(ClipData.newPlainText("DSHA 助手回答", text));
      copy.setText(R.string.assistant_copied);
      copy.postDelayed(() -> copy.setText(R.string.assistant_copy), 1200L);
    } catch (Throwable ignored) {
    }
  }

  private void shareAnswer() {
    CharSequence text = result.getText();
    if (text == null || text.length() == 0) return;
    try {
      Intent i = new Intent(Intent.ACTION_SEND);
      i.setType("text/plain");
      i.putExtra(Intent.EXTRA_TEXT, text.toString());
      i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      host.startActivity(Intent.createChooser(i, null));
    } catch (Throwable ignored) {
    }
  }

  private void toggleSpeak() {
    if (speech == null) {
      speech = new AssistantSpeech((speaking, problem) -> ui.post(() -> onSpeechState(speaking, problem)));
      speech.init(host, config);
    }
    if (speech.isSpeaking()) {
      speech.stop();
      return;
    }
    CharSequence text = result.getText();
    if (text == null || text.length() == 0) return;
    if (!speech.isReady()) {
      showAnswer(host.getString(R.string.assistant_no_tts));
      return;
    }
    speech.speak(text.toString());
  }

  private void onSpeechState(boolean speaking, String problem) {
    speak.setText(speaking ? R.string.assistant_speaking : R.string.assistant_speak);
    if (problem != null && !problem.isEmpty() && attached) {
      status.setVisibility(View.VISIBLE);
      status.setText(problem);
      status.setTextColor(host.getColor(R.color.assistant_text_secondary));
    }
  }

  private void openWorkbench() {
    hide();
    try {
      Intent intent = new Intent();
      intent.setComponent(new ComponentName(host.getPackageName(), MAIN_ACTIVITY_CLASS));
      intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
      intent.putExtra("open_launch", true);
      host.startActivity(intent);
    } catch (Throwable ignored) {
    }
  }

  // ===================== 语音 =====================

  private void toggleVoice() {
    if (busy && !recorder.isRecording()) return;
    if (recorder.isRecording()) {
      stopVoice();
      return;
    }
    if (host.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      // Service 没法弹权限框：转交给一个透明小 Activity 去请求，授完自动接着录音
      requestMicPermission();
      return;
    }
    startVoice();
  }

  /** 麦克风权限：一律走 {@link AssistantPermit} 闸门（同一个入口管 mic/flash/通知/电池/飞行）。 */
  private void requestMicPermission() {
    setHint("需要麦克风权限，请在弹窗里允许");
    if (!AssistantPermit.ask(host, AssistantPermit.WHAT_MIC, "你说要说话，我需要麦克风")) {
      showAnswer("⚠ 没有麦克风权限，语音用不了。去系统设置给 DSHA 开「麦克风」。");
    }
  }

  private void startVoice() {
    if (!attached) return;
    try {
      recorder.start(host);
    } catch (Throwable t) {
      showAnswer("⚠ 录音启动失败：" + t);
      return;
    }
    mic.setText(R.string.assistant_mic_stop);
    mic.setTextColor(host.getColor(R.color.assistant_accent));
    resultActions.setVisibility(View.GONE);
    resultScroll.setVisibility(View.GONE);
    status.setVisibility(View.VISIBLE);
    status.setText(R.string.assistant_listening);
    status.setTextColor(host.getColor(R.color.assistant_accent));
    orb.setMode(SiriOrbView.Mode.LISTENING);
    ui.postDelayed(amplitudePoll, 120L);
    ui.postDelayed(voiceTimeout, VOICE_MAX_MS);
  }

  private void stopVoice() {
    ui.removeCallbacks(voiceTimeout);
    ui.removeCallbacks(amplitudePoll);
    mic.setText(R.string.assistant_mic);
    mic.setTextColor(host.getColor(R.color.assistant_text_secondary));
    orb.setLevel(0.35f);
    if (!recorder.isRecording()) return;
    final java.io.File file = recorder.stop();
    orb.setMode(SiriOrbView.Mode.THINKING);
    if (file == null) {
      status.setVisibility(View.GONE);
      showAnswer("没录到声音，再试一次？");
      orb.setMode(SiriOrbView.Mode.IDLE);
      return;
    }
    status.setVisibility(View.VISIBLE);
    status.setText("正在识别…");
    io.execute(
        () -> {
          String text;
          try {
            text = AssistantVoiceInput.transcribe(config, file);
          } catch (Throwable t) {
            final String msg = "⚠ " + AssistantVoiceInput.friendlyError(t);
            ui.post(
                () -> {
                  if (!attached) return;
                  status.setVisibility(View.GONE);
                  orb.setMode(SiriOrbView.Mode.IDLE);
                  showAnswer(msg);
                });
            return;
          }
          final String recognized = text;
          ui.post(
              () -> {
                if (!attached) return;
                input.setText(recognized);
                input.setSelection(recognized.length());
                sendQuestion();
              });
        });
  }

  // ===================== 小工具 =====================

  private void hideKeyboard() {
    try {
      InputMethodManager imm =
          (InputMethodManager) host.getSystemService(Context.INPUT_METHOD_SERVICE);
      if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
    } catch (Throwable ignored) {
    }
  }

  private int dp(float v) {
    return (int) TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, host.getResources().getDisplayMetrics());
  }
}
