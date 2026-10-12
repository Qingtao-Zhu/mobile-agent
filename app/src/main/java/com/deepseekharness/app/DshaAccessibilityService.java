package com.deepseekharness.app;

import com.deepseekharness.app.util.SensitiveData;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 接收窗口事件的无障碍服务。事件处理仅在用户开启的短暂配对窗口扫描设置页，
 * 配对码读取后关闭窗口；读屏、截图由已授权的设备能力请求单独触发。
 * 清单不限定设置应用，不能宣称系统不投递其他应用事件。
 * 截图写入应用私有 Pictures/DSHA，返回受控内容 URI；它不是完全不落盘的能力。
 * 不将配对码或敏感屏幕内容写入公开日志；撤销和运行代次约束见对应能力实现。
 */
public class DshaAccessibilityService extends AccessibilityService {

  private static final String TAG = "DSHA";
  private static final String SETTINGS_PKG = "com.android.settings";
  /** 华为「智慧语音」（长按电源键唤起的就是它，框架硬编码，改不了目标）。 */
  /**
   * 要接管的"系统语音助手"包名。
   *
   * <p>这台 ROM 上长按电源键起的是 {@code com.huawei.vassistant}（HwVAssistant），
   * 框架只认这个目标，第三方 App 改不了；所以这里盯住它，它一亮就让 DSHA 面板盖上去，
   * 用户看到的、能说话的还是 DSHA。改这个值可以换盯别的语音助手（例如有些机器是
   * {@code com.huawei.vassistant} 之外的小爱/讯飞那类包名）。
   */
  private static final String HIVOICE_PKG = "com.huawei.vassistant";

  /** 与配对码本身的有效期对齐：Android 的配对弹窗约 2 分钟失效 */
  private static final long WATCH_MS = 120_000L;

  /** 无线调试配对码固定 6 位；前后加边界避免从长数字里截一段 */
  private static final Pattern CODE = Pattern.compile("(?<!\\d)(\\d{6})(?!\\d)");

  /** 弹窗里的「IP 地址和端口」，端口是随机高位 */
  private static final Pattern ADDR = Pattern.compile("(\\d{1,3}(?:\\.\\d{1,3}){3}):(\\d{2,5})");

  /** 读到配对信息后的回调（轻量版不带 ADB 无线调试配对，接口保留但不会被触发）。 */
  public interface PairInfoListener {
    void onPairInfo(String code, String ip, String port, String connectPort);
  }

  private static volatile long lastKeepAliveAt;
  private static final long KEEPALIVE_DEBOUNCE_MS = 30_000L;

  /** 三态：YES 确认已开 / NO 确认未开 / UNKNOWN 读不到设置（别当成未开）。
   *
   *  服务实例存在是最硬的证据 —— 系统能把它连起来，就一定是开着的。
   *  只在拿不到实例时才去解析 Settings 字符串，而那串在各家 ROM 上格式不一，
   *  解析失败时必须承认「不知道」，不能报「未开启」害用户反复去开。 */
  public static String enabledState(Context ctx) {
    if (instance != null) return "YES";
    try {
      String cls = DshaAccessibilityService.class.getName();
      String want = ctx.getPackageName() + "/" + cls;
      String on =
          Settings.Secure.getString(
              ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
      if (on == null) return "UNKNOWN"; // 读不到这一项，不代表用户没开
      if (on.isEmpty()) return "NO"; // 明确是空串 = 一个无障碍服务都没开
      for (String e0 : on.split(":")) {
        String e = e0.trim();
        if (e.equalsIgnoreCase(want)) return "YES";
        if (e.startsWith(ctx.getPackageName() + "/")
            && cls.endsWith(e.substring(e.indexOf('/') + 1))) {
          return "YES";
        }
      }
      return "NO";
    } catch (Throwable e) {
      return "UNKNOWN";
    }
  }

  /** 用户是否已在系统设置里开启本服务 */
  public static boolean enabled(Context ctx) {
    try {
      String cls = DshaAccessibilityService.class.getName();
      String want = ctx.getPackageName() + "/" + cls;
      String on =
          Settings.Secure.getString(
              ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
      if (on == null || on.isEmpty()) return false;
      for (String s : on.split(":")) {
        String e = s.trim();
        if (e.equalsIgnoreCase(want)) return true;
        // 部分机型存的是 包名/.类简名 的简写形式
        if (e.startsWith(ctx.getPackageName() + "/")
            && cls.endsWith(e.substring(e.indexOf('/') + 1))) {
          return true;
        }
      }
      return false;
    } catch (Throwable e) {
      return false;
    }
  }

  /** 轻量版不带 ADB 无线调试配对：窗口期跟踪整体移除，本服务不再读取设置页内容。 */
  public static void startWatch(PairInfoListener l) {}

  public static void stopWatch() {}

  public static boolean watching() {
    return false;
  }

  /**
   * Rebind only a service that the user already enabled in Android settings.
   * The ADB pairing flow grants WRITE_SECURE_SETTINGS for this app; the ADB
   * watchdog uses that grant after a verified probe to recover ROMs that
   * silently unbind the accessibility service. A user who turns the service
   * off removes the component, so this method never re-enables a manual off.
   */
  public static boolean rebindIfEnabled(Context context) {
    if (instance != null || context == null || !enabled(context)) return false;
    long now = android.os.SystemClock.elapsedRealtime();
    if (now - lastKeepAliveAt < KEEPALIVE_DEBOUNCE_MS) return false;
    lastKeepAliveAt = now;
    try {
      if (context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
          != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
      String configured =
          Settings.Secure.getString(
              context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
      if (configured == null || configured.isEmpty()) return false;
      String component = context.getPackageName() + "/" + DshaAccessibilityService.class.getName();
      java.util.ArrayList<String> entries = new java.util.ArrayList<>();
      boolean found = false;
      for (String raw : configured.split(":")) {
        String value = raw.trim();
        if (value.isEmpty()) continue;
        if (value.equalsIgnoreCase(component)
            || (value.startsWith(context.getPackageName() + "/")
                && DshaAccessibilityService.class
                    .getName()
                    .endsWith(value.substring(value.indexOf('/') + 1)))) {
          found = true;
          continue;
        }
        entries.add(value);
      }
      if (!found) return false;
      String disabled = android.text.TextUtils.join(":", entries);
      if (!Settings.Secure.putString(
          context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, disabled))
        return false;
      android.os.SystemClock.sleep(120L);
      if (!Settings.Secure.putString(
          context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, configured))
        return false;
      Log.i(TAG, "ADB watchdog requested an accessibility service rebind");
      return true;
    } catch (Throwable error) {
      Log.w(TAG, "Accessibility rebind unavailable: " + error.getClass().getSimpleName());
      return false;
    }
  }

  /**
   * 按键派发。这是判断"音量键能不能拿来唤助手"的唯一入口：
   * 系统一般会在 PhoneWindowManager 就把音量键截胡去调音量（本机音量下+电源是截屏），
   * 走不到这里；一旦某台机器真的把音量键派发下来，就能在这里接住、长按唤助手。
   */
  @Override
  public boolean onKeyEvent(KeyEvent event) {
    // 这是无障碍服务唯一能碰到物理键的地方（API 33 起标记为废弃，但还没被移除，
    // 也是判断"音量键能不能拿来唤助手"的唯一入口）：系统一般会在 PhoneWindowManager
    // 就把音量键截胡去调音量（本机音量下+电源是截屏），走不到这里。
    if (event != null) {
      Log.i(
          TAG,
          "key: code="
              + event.getKeyCode()
              + " action="
              + event.getAction()
              + " repeat="
              + event.getRepeatCount());
    }
    return super.onKeyEvent(event);
  }

  @Override
  public void onAccessibilityEvent(AccessibilityEvent event) {
    if (event == null) return;
    // ---------- 接管系统语音助手（长按电源键那条路） ----------
    // 这台 ROM 上「长按电源键 → 智慧语音」是框架里写死的：
    //   settings secure invoke_hivoice_keypress_type = power
    // 由 HwPhoneWindowManager 读（动静态都在 /system/framework 的 hwServices 里），
    // 它只决定"按哪个键"，**不决定按了以后起哪个 App**，第三方 App 改不了这个目标。
    // 所以退一步：让长按确实唤起系统助手没关系，DSHA 面板立刻盖上去把它接管掉 ——
    // 用户看到的、能说话的，仍然是 DSHA。
    CharSequence launcher = event.getPackageName();
    if (launcher != null && HIVOICE_PKG.contentEquals(launcher)
        && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
      try {
        // 面板已经亮着时这里会被 visible() 挡掉，但事件本身还是要留个痕，
        // 不然"长按电源键没反应"只能靠猜：日志会打在下面这行上。
        android.util.Log.i(
            TAG,
            "语音助手窗口事件到了，pkg="
                + launcher
                + " panelVisible="
                + com.deepseekharness.app.assistant.AssistantPanel.visible());
        if (!com.deepseekharness.app.assistant.AssistantPanel.visible()) {
          android.util.Log.i(TAG, "系统语音助手起来了，DSHA 面板接管");
          com.deepseekharness.app.assistant.AssistantPanel.show(this);
        }
      } catch (Throwable t) {
        android.util.Log.w(TAG, "接管系统语音助手失败：" + t);
      }
      return;
    }
    // 轻量版不做无线调试配对读屏：除「接管系统语音助手」外一律不读任何应用的窗口内容。
  }

  /** 收集节点树上的可见文本。限深度与总长，避免深树递归过深或把内存吃爆。 */
  private static void collectText(AccessibilityNodeInfo node, StringBuilder sb, int depth) {
    if (node == null || depth > 24 || sb.length() > 8000) return;
    if (node.isPassword()) return;
    CharSequence t = node.getText();
    if (!TextUtils.isEmpty(t)) sb.append(t).append('\n');
    CharSequence d = node.getContentDescription();
    if (!TextUtils.isEmpty(d)) sb.append(d).append('\n');
    int n = node.getChildCount();
    for (int i = 0; i < n; i++) {
      AccessibilityNodeInfo ch = node.getChild(i);
      if (ch == null) continue;
      try {
        collectText(ch, sb, depth + 1);
      } finally {
        try {
          ch.recycle();
        } catch (Throwable ignored) {
        }
      }
    }
  }

  // ==================== 给 agent 用的屏幕操作能力 ====================
  //
  // 这一层让 agent 不依赖 ADB / Shizuku 就能读屏、点按、输入、按键 —— 对多数
  // 用户来说 ADB 无线调试根本没开，Shizuku 也没装，而无障碍是一次授权永久可用。
  //
  // 隐私：这里全部是「按需拉取」——只有 agent 调用时才去 getRootInActiveWindow()，
  // 服务自身不做任何持续记录（onAccessibilityEvent 里除了配对窗口一律直接返回）。

  private static volatile DshaAccessibilityService instance;

  public static boolean connected() {
    return instance != null;
  }

  /**
   * 当前服务实例（可能为 null：force-stop 之后、onServiceConnected 之前实测 4 秒都是 null）。
   *
   * <p>给「文本直达」这类入口用——它们需要一个 {@code host} 才能把面板挂起来，
   * 但不能像内部代码那样直接摸 {@code instance} 字段（assistant 包和本包不同包）。
   * 调用方必须先判 null，别把 null 灌进面板构造里。
   */
  public static DshaAccessibilityService instance() {
    return instance;
  }

  /** 授权弹窗关闭的短暂窗口切换期间只重试读取窗口，不重放点击或输入。 */
  private static final class ActionAuthority {
    final com.deepseekharness.app.util.ScreenTarget target;
    final java.util.function.BooleanSupplier live;

    ActionAuthority(
        com.deepseekharness.app.util.ScreenTarget target, java.util.function.BooleanSupplier live) {
      this.target = target;
      this.live = live;
    }
  }

  private static final ThreadLocal<ActionAuthority> ACTION_AUTHORITY = new ThreadLocal<>();

  public static String runAuthorized(
      com.deepseekharness.app.util.ScreenTarget target,
      java.util.function.BooleanSupplier live,
      java.util.function.Supplier<String> action) {
    ActionAuthority previous = ACTION_AUTHORITY.get();
    ACTION_AUTHORITY.set(new ActionAuthority(target, live));
    try {
      return action.get();
    } finally {
      if (previous == null) ACTION_AUTHORITY.remove();
      else ACTION_AUTHORITY.set(previous);
    }
  }

  private static AccessibilityNodeInfo displayRoot(DshaAccessibilityService service, int display) {
    if (service == null) return null;
    if (android.os.Build.VERSION.SDK_INT < 30)
      return display == 0 ? service.getRootInActiveWindow() : null;
    var windows = service.getWindowsOnAllDisplays().get(display);
    if (windows == null) return null;
    AccessibilityNodeInfo result = null;
    try {
      for (var window : windows) {
        if (display > 0
            && window.getType()
                != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue;
        var root = window.getRoot();
        if (root == null) continue;
        if (result == null || window.isActive() || window.isFocused()) {
          if (result != null) result.recycle();
          result = root;
        } else root.recycle();
        if (window.isActive()) break;
      }
    } finally {
      for (var window : windows)
        try {
          window.recycle();
        } catch (Throwable ignored) {
        }
    }
    return result;
  }

  private static com.deepseekharness.app.util.ScreenTarget identity(
      int display, AccessibilityNodeInfo root) {
    return root == null
        ? null
        : new com.deepseekharness.app.util.ScreenTarget(
            display,
            root.getWindowId(),
            root.getPackageName() == null ? "" : root.getPackageName().toString());
  }

  public static com.deepseekharness.app.util.ScreenTarget observeTarget(int display) {
    AccessibilityNodeInfo root = displayRoot(instance, display);
    try {
      return identity(display, root);
    } finally {
      if (root != null) root.recycle();
    }
  }

  public static boolean matchesTarget(com.deepseekharness.app.util.ScreenTarget target) {
    return target != null && target.matches(observeTarget(target.displayId));
  }

  private static AccessibilityNodeInfo activeWindow(DshaAccessibilityService service) {
    ActionAuthority authority = ACTION_AUTHORITY.get();
    int display = authority == null ? 0 : authority.target.displayId;
    var root = displayRoot(service, display);
    if (authority != null
        && (!authority.live.getAsBoolean() || !authority.target.matches(identity(display, root)))) {
      if (root != null) root.recycle();
      throw new IllegalStateException("SCREEN_TARGET_CHANGED");
    }
    return root;
  }

  /**
   * 写操作前的唯一放行判定（fail-closed）。
   *
   * <p><b>2026-10-11 离线安全验证轮改造</b>：判定逻辑收敛到
   * {@link com.deepseekharness.app.assistant.ActionGate#decide}（纯函数、JVM 可测、
   * 测试能用替身直接断言"拒绝时没有派发"），本方法只负责"不是 ALLOW 就抛"。
   *
   * <p><b>行为收紧（有意为之）</b>：旧写法是
   * {@code if (authority != null && (...))} —— 没有授权上下文时**直接放行**。
   * 在 D-01 接入之前，那意味着这条检查永远不生效；接入之后，
   * 一个"忘了包 runAuthorized"的新调用点就会静默地裸奔。
   * 现在改成 fail-closed：没有授权上下文 → 拒绝。
   *
   * <p><b>只影响写操作</b>：本方法只被 {@code gesture()}（派发手势）和
   * {@code uiTapText()}（节点点击）调用。读屏路径（{@code activeWindow} /
   * {@code uiDump}）用的是另一处检查，面板读屏不受影响。
   */
  private static void verifyActionTarget() {
    ActionAuthority authority = ACTION_AUTHORITY.get();
    com.deepseekharness.app.assistant.ActionGate.Decision d =
        com.deepseekharness.app.assistant.ActionGate.decide(
            authority != null,
            authority == null || authority.live.getAsBoolean(),
            authority != null && matchesTarget(authority.target));
    if (com.deepseekharness.app.assistant.ActionGate.mustThrow(d)) {
      throw new IllegalStateException(
          com.deepseekharness.app.assistant.ActionGate.exceptionCode(d));
    }
  }

  /**
   * 宽松版校验：**只在有授权上下文时才检查**（给 {@link #uiKey} 用）。
   *
   * <p><b>为什么 uiKey 不适用 fail-closed</b>：{@code uiKey} 走的是
   * {@code performGlobalAction}（返回键 / Home / 通知栏 / 快捷设置），
   * 它**不针对屏幕上某个具体控件的位置** —— 不存在"点错到隔壁按钮"的风险。
   * 而且 {@code AssistantCommands} 的本地命令（用户说"返回"）直接调用它，
   * 那条路径本来就没有任务授权上下文；若也 fail-closed，
   * 会把这些本地命令一并打死（属于把安全检查做成了功能回归）。
   *
   * <p>换句话说：**fail-closed 的对象是"会落到某个坐标/控件上的动作"**（gesture），
   * 不是所有无障碍调用。这个边界写在这里，免得以后有人"顺手"给它也加上。
   */
  private static void verifyActionTargetIfAuthorized() {
    if (ACTION_AUTHORITY.get() == null) return; // 无上下文：全局动作放行
    verifyActionTarget(); // 有上下文：按严格规则校验
  }

  public static void validateActionRoot(int display, AccessibilityNodeInfo root) {
    ActionAuthority a = ACTION_AUTHORITY.get();
    if (a != null && (!a.live.getAsBoolean() || !a.target.matches(identity(display, root))))
      throw new IllegalStateException("SCREEN_TARGET_CHANGED");
  }

  @Override
  protected void onServiceConnected() {
    super.onServiceConnected();
    instance = this;
    lastKeepAliveAt = 0L;
    Log.i(TAG, "无障碍服务已连接");
    // 有人（助手键 / 磁贴 / DshaAssistActivity）在服务还没绑上的时候就把面板请求塞进来了：
    // 这里补亮，比让调用方赌 sleep 时序可靠（实测 force-stop 之后 4s 内 instance 还是 null，
    // 2026-10-07 19:34 日志原文：onCreate shown=false serviceInstance=false）。
    if (pendingAssistantPanel) {
      pendingAssistantPanel = false;
      Log.i(TAG, "补亮此前挂起的助手面板请求");
      showAssistantPanel();
    }
    // 服务一绑上就把通知栏那条「待命」挂回来（面板正亮着的话 refresh 自己会摘掉）。
    // 这是「不靠无障碍事件也能叫助手」的那条路：点通知上的按钮 → DshaWakeReceiver → 亮面板。
    com.deepseekharness.app.assistant.DshaWakeTile.refresh(this);
    // 把悬浮球也补挂上（2026-10-09 常驻加固）：进程被杀后系统重绑本服务，
    // 球跟着旧进程的窗口一起没了 —— 球是"正在用别的 App 时抬手就能碰到"的唯一入口，掉不得。
    // 面板正亮着的话 ensure 内部会避开（球不该和面板叠着）；被用户拖进删除区删掉的也不会挂。
    com.deepseekharness.app.assistant.DshaPebbleView.ensure(this);
    // 语音唤醒服务也补起（同样是被杀重启的自愈路径）：开着「你好大肥鱼」就继续听。
    com.deepseekharness.app.assistant.AssistantWakeService.startIfEnabled(this);
  }

  /**
   * 亮出助手面板（悬浮窗版，2026-10-07）。
   *
   * <p>面板 = {@code AssistantPanel}（TYPE_ACCESSIBILITY_OVERLAY，由本服务挂窗）。
   * 为什么不用 Activity：这台 EMUI 12 的 HWC 对 Activity 窗口里的透明像素一律合成成
   * 纯黑（transparentRegionHint 恒空，卡片圆角外全是黑边）；悬浮窗的透明像素实测
   * 能逐像素透出下层（红框对照实验 4/4 PASS），而且 NOT_FOCUSABLE + NOT_TOUCH_MODAL
   * 让面板显示时下面的应用保持可操作 —— 用户后续要把面板放到屏幕顶部同时操作
   * 底部页面，靠的就是这两个 flag。
   *
   * @return false = 服务没连接（面板读屏依赖无障碍），调用方自行兜底
   */
  /** 服务实例是否在（排障用：面板挂不上时先确认这条，别一上来就去读布局）。 */
  public static boolean hasInstance() {
    return instance != null;
  }

  /** 等着的助手面板请求（服务还没连上，等 onServiceConnected 补亮）。 */
  private static volatile boolean pendingAssistantPanel;

  /**
   * 请求亮面板，服务还没连上时排队补亮（推荐入口，别直接调 {@link #showAssistantPanel}）。
   *
   * <p>助手键/磁贴常常在服务刚被杀掉没多久就按下，此时 {@code instance == null}，
   * 直接调只会返回 false 让调用方兜底进主界面 —— 用户看到的是"助手失灵"而不是面板晚半秒出来。
   */
  public static void requestAssistantPanel() {
    if (instance != null) {
      showAssistantPanel();
    } else {
      pendingAssistantPanel = true;
      Log.i(TAG, "服务未连接，助手面板请求入队");
    }
  }

  public static boolean showAssistantPanel() {
    DshaAccessibilityService s = instance;
    if (s == null) return false;
    com.deepseekharness.app.assistant.AssistantPanel.show(s);
    return true;
  }

  /** 服务没开时统一的提示语：告诉 agent 该让用户做什么，而不是只丢一个错误码 */
  private static final String NOT_READY =
      "[ERR] 无障碍服务未开启。请让用户在 DSHA「设置 → 设备能力授权」点「设置屏幕操作」，" + "或到系统设置 → 无障碍 → DSHA 配对助手 打开。";

  /** 当前前台窗口的应用包名；取不到返回空串。授权闸门用它识别支付/银行类应用。 */
  public static String currentPackage() {
    DshaAccessibilityService s = instance;
    if (s == null) return "";
    AccessibilityNodeInfo root = null;
    try {
      root = activeWindow(s);
      if (root == null) return "";
      CharSequence p = root.getPackageName();
      return p == null ? "" : p.toString();
    } catch (Throwable t) {
      return "";
    } finally {
      if (root != null) {
        try {
          root.recycle();
        } catch (Throwable ignored) {
        }
      }
    }
  }

  public static boolean isConnected() {
    return instance != null;
  }


  /** 读当前屏幕：输出带序号、文本、可点击性与坐标的清单，供 agent 决定下一步点哪个。 */
  public static String uiDump() {
    DshaAccessibilityService s = instance;
    if (s == null) return com.deepseekharness.app.util.UiText.text(NOT_READY);
    try {
      AccessibilityNodeInfo root = activeWindow(s);
      if (root == null)
        return com.deepseekharness.app.util.UiText.text("[ERR] 取不到当前窗口（可能停在锁屏或系统弹窗上）");
      try {
        return dumpRoot(root);
      } finally {
        try {
          root.recycle();
        } catch (Throwable ignored) {
        }
      }
    } catch (Throwable t) {
      return com.deepseekharness.app.util.UiText.format(
          "[ERR] 读屏失败：%s", SensitiveData.redact(String.valueOf(t)));
    }
  }

  /**
   * 助手面板专用读屏（V2）。
   *
   * <p>面板自己是「抢焦点的半透明浮层」，所以 {@code getRootInActiveWindow()} 拿到的
   * 是**面板自己**而不是用户真正在看的那一屏。这里遍历窗口列表，跳过本包窗口，
   * 取下面那层真实应用窗口来读 —— 与 {@link #uiDump()} 输出同一套格式，只换了取根的入口。
   */
  public static String uiDumpUnderPanel() {
    DshaAccessibilityService s = instance;
    if (s == null) return com.deepseekharness.app.util.UiText.text(NOT_READY);
    try {
      AccessibilityNodeInfo root = underPanelRoot(s);
      if (root == null)
        return com.deepseekharness.app.util.UiText.text("[ERR] 取不到面板下面的窗口（可能停在锁屏或系统弹窗上）");
      try {
        return dumpRoot(root);
      } finally {
        try {
          root.recycle();
        } catch (Throwable ignored) {
        }
      }
    } catch (Throwable t) {
      return com.deepseekharness.app.util.UiText.format(
          "[ERR] 读屏失败：%s", SensitiveData.redact(String.valueOf(t)));
    }
  }

  /**
   * 取「面板下面那层真实应用窗口」的根节点（调用方负责 recycle）。
   * 找不到非本包窗口时退回系统认定的活动窗口，宁可读到面板自己也不返回空。
   */
  private static AccessibilityNodeInfo underPanelRoot(DshaAccessibilityService s) {
    if (android.os.Build.VERSION.SDK_INT < 30) return s.getRootInActiveWindow();
    java.util.List<android.view.accessibility.AccessibilityWindowInfo> windows = null;
    AccessibilityNodeInfo fallback = null;
    try {
      windows = s.getWindows();
      if (windows == null) return s.getRootInActiveWindow();
      String self = s.getPackageName();
      for (android.view.accessibility.AccessibilityWindowInfo window : windows) {
        if (window.getType()
            != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue;
        AccessibilityNodeInfo root = window.getRoot();
        if (root == null) continue;
        CharSequence pkg = root.getPackageName();
        if (pkg != null && self != null && self.contentEquals(pkg)) {
          root.recycle();
          continue;
        }
        if (window.isActive() || window.isFocused()) {
          if (fallback != null) fallback.recycle();
          return root;
        }
        if (fallback == null) fallback = root;
        else root.recycle();
      }
    } catch (Throwable ignored) {
    } finally {
      if (windows != null) {
        for (android.view.accessibility.AccessibilityWindowInfo window : windows) {
          try {
            window.recycle();
          } catch (Throwable ignored) {
          }
        }
      }
    }
    return fallback != null ? fallback : s.getRootInActiveWindow();
  }

  /** 一份屏幕文本的统一产出格式；{@link #uiDump()} 与 {@link #uiDumpUnderPanel()} 共用。 */
  private static String dumpRoot(AccessibilityNodeInfo root) {
    StringBuilder sb = new StringBuilder();
    CharSequence pkg = root.getPackageName();
    sb.append("窗口应用: ").append(pkg == null ? "未知" : pkg).append('\n');
    int[] n = {0};
    dumpNode(root, sb, 0, n);
    if (n[0] == 0) sb.append("（没有可读节点）\n");
    // 2026-10-11：把"这屏有多少节点、其中多少带 viewId / contentDescription"打到日志。
    //
    // 为什么只打统计不打全文：dump 全文里可能有用户的聊天记录、通知内容等隐私，
    // 落到 logcat 就等于泄到系统日志里了。统计数字足够验证「新字段确实在输出」，
    // 又不会把屏幕内容留在日志里。定位规则能不能写，看的正是这两个计数。
    try {
      int withId = 0, withCd = 0;
      for (String line : sb.toString().split("\n")) {
        if (line.contains(" id=")) withId++;
        if (line.contains(" cd=\"")) withCd++;
      }
      android.util.Log.i("DSHA/Dump", "节点 " + n[0] + " 个，带 viewId " + withId
          + " 个，带 contentDescription " + withCd + " 个（应用 " + pkg + "）");
    } catch (Throwable ignored) {
    }
    return sb.toString();
  }

  private static void dumpNode(
      AccessibilityNodeInfo node, StringBuilder sb, int depth, int[] count) {
    if (node == null || depth > 24 || count[0] >= 600 || sb.length() > 60000) return;
    CharSequence t = node.isPassword() ? null : node.getText();
    CharSequence d = node.isPassword() ? null : node.getContentDescription();
    boolean clickable = node.isClickable();
    boolean editable = node.isEditable();
    String label =
        node.isPassword()
            ? "[PASSWORD]"
            : !TextUtils.isEmpty(t) ? t.toString() : (!TextUtils.isEmpty(d) ? d.toString() : "");
    android.graphics.Rect r = new android.graphics.Rect();
    node.getBoundsInScreen(r);
    // 只输出「有文字」或「能点/能输入」的节点：全量节点树对 agent 是噪音
    if ((!label.isEmpty() || clickable || editable)
        && com.deepseekharness.app.util.AccessibilityDumpBounds.ordered(
            r.left, r.top, r.right, r.bottom)) {
      count[0]++;
      sb.append('[').append(count[0]).append("] ");
      if (!label.isEmpty()) sb.append('"').append(label.replace('\n', ' ')).append('"');
      if (clickable) sb.append(" 可点击");
      if (editable) sb.append(" 可输入");
      if (node.isChecked()) sb.append(" 已选中");
      if (!node.isEnabled()) sb.append(" 不可用");
      sb.append(" 中心=(")
          .append(r.centerX())
          .append(',')
          .append(r.centerY())
          .append(')')
          .append(" 区域=")
          .append(r.left)
          .append(',')
          .append(r.top)
          .append(',')
          .append(r.right)
          .append(',')
          .append(r.bottom);
      // 2026-10-11 大众化改造：把 viewId 与原始 contentDescription 也带上。
      //
      // 为什么必须加：定位规则要用 viewIdResourceName / contentDescription 认控件
      // （这是 Android 上最稳的两个特征，比文本可靠——文本会随语言和 App 版本变）。
      // 原来只输出一个合并后的 label（text 优先、没 text 才用 desc），
      // 于是"有 text 的按钮"的 contentDescription 在 dump 里根本看不见，规则写不了。
      //
      // 放在行尾（区域之后）是为了不打断既有解析：老代码按"中心=("、"区域="抓取，
      // 新增字段挂在末尾不影响它们。格式：
      //   id=<viewId短名>（有才输出）
      //   cd=<原始contentDescription>（有、且与label不同才输出）
      try {
        String vid = node.getViewIdResourceName();
        if (vid != null && !vid.isEmpty()) {
          int slash = vid.lastIndexOf('/');
          sb.append(" id=").append(slash >= 0 ? vid.substring(slash + 1) : vid);
        }
      } catch (Throwable ignored) {
      }
      try {
        if (!node.isPassword() && !TextUtils.isEmpty(d)) {
          String desc = d.toString().replace('\n', ' ');
          if (!desc.equals(label)) sb.append(" cd=\"").append(desc).append('"');
        }
      } catch (Throwable ignored) {
      }
      sb.append('\n');
    }
    if (node.isPassword()) return;
    int cn = node.getChildCount();
    for (int i = 0; i < cn; i++) {
      AccessibilityNodeInfo ch = node.getChild(i);
      if (ch == null) continue;
      try {
        dumpNode(ch, sb, depth + 1, count);
      } finally {
        try {
          ch.recycle();
        } catch (Throwable ignored) {
        }
      }
    }
  }

  /**
   * 按坐标点按。坐标从 uiDump 的「中心=」里取。
   *
   * <p><b>2026-10-11 加固（审计 D-03）</b>：加坐标范围校验。
   *
   * <p>原来只校验了系统版本和服务可用性，**没有校验坐标本身**。
   * 而调用方 `AssistantTaskRunner` 的 `case "tap_xy"` 用的是
   * {@code optInt("x", -1)} —— 模型漏给 x/y 时会传进来 (-1,-1)，
   * 直接派发到屏幕外。虽然系统多半忽略屏幕外坐标，但这属于
   * "不该发出去的请求就不发" 的底线问题：万一某代 ROM 把它当 (0,0) 处理，
   * 就是一次莫名其妙的左上角点击。
   */
  public static String uiTap(int x, int y) {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) {
      return "手势点按需 Android 7+（当前系统过旧）";
    }
    DshaAccessibilityService s = instance;
    if (s == null) return com.deepseekharness.app.util.UiText.text(NOT_READY);
    // 坐标范围校验：越界一律拒绝，不派发
    String bad = validateTapCoords(s, x, y);
    if (bad != null) return bad;
    return s.gesture(
        buildTap(x, y), com.deepseekharness.app.util.UiText.format("点按 (%d,%d)", x, y));
  }

  /**
   * 纯校验：坐标是否可安全派发。返回 null = 合法；返回非 null = 拒绝原因。
   *
   * <p>抽成独立函数是为了能被<b>只读自检</b>调用 —— 测试入口需要验证
   * "越界坐标会被拒绝"，但**不能真的发手势**（用户明确要求定位测试阶段
   * 只允许 dump / 匹配 / 记录，禁止自动点击任何目标）。
   * {@link #uiTap} 和自检入口共用同一份校验逻辑，避免"测的和用的不是一套"。
   */
  public static String validateTapCoords(DshaAccessibilityService s, int x, int y) {
    if (s == null) return com.deepseekharness.app.util.UiText.text(NOT_READY);
    try {
      android.util.DisplayMetrics dm = s.getResources().getDisplayMetrics();
      if (dm != null && dm.widthPixels > 0 && dm.heightPixels > 0) {
        if (x < 0 || y < 0 || x >= dm.widthPixels || y >= dm.heightPixels) {
          return com.deepseekharness.app.util.UiText.format(
              "[ERR] 坐标 (%d,%d) 超出屏幕范围 %dx%d，已拒绝执行",
              x, y, dm.widthPixels, dm.heightPixels);
        }
      }
    } catch (Throwable ignored) {
      // 取不到屏幕尺寸时不阻断（宁可少校验也不让正常点击失效）
    }
    return null;
  }

  /** 按文字点按：优先走节点自身的 ACTION_CLICK，比盲点坐标稳得多
   *  （控件位置会随滚动、折叠、动画变化，文字不会）。 */
  public static String uiTapText(String text) {
    DshaAccessibilityService s = instance;
    if (s == null) return com.deepseekharness.app.util.UiText.text(NOT_READY);
    if (text == null || text.isEmpty())
      return com.deepseekharness.app.util.UiText.text("[ERR] 要点的文字不能为空");
    AccessibilityNodeInfo root = null;
    try {
      root = activeWindow(s);
      if (root == null) return com.deepseekharness.app.util.UiText.text("[ERR] 取不到当前窗口");
      AccessibilityNodeInfo hit = findClickableByText(root, text, 0);
      if (hit == null)
        return com.deepseekharness.app.util.UiText.format(
            "[ERR] 屏幕上找不到可点击的「%s」（先用 dump 看看实际文字）", text);
      boolean ok;
      try {
        ok = hit.performAction(AccessibilityNodeInfo.ACTION_CLICK);
      } finally {
        try {
          hit.recycle();
        } catch (Throwable ignored) {
        }
      }
      return ok
          ? com.deepseekharness.app.util.UiText.format("OK 已点击「%s」", text)
          : com.deepseekharness.app.util.UiText.text("[ERR] 点击被系统拒绝（控件可能不可用）");
    } catch (Throwable t) {
      return com.deepseekharness.app.util.UiText.format(
          "[ERR] 点击失败：%s", SensitiveData.redact(String.valueOf(t)));
    } finally {
      if (root != null) {
        try {
          root.recycle();
        } catch (Throwable ignored) {
        }
      }
    }
  }

  /** 找到含指定文字、且自身或祖先可点击的节点（返回的节点由调用方 recycle） */
  private static AccessibilityNodeInfo findClickableByText(
      AccessibilityNodeInfo node, String text, int depth) {
    if (node == null || depth > 24) return null;
    CharSequence t = node.isPassword() ? null : node.getText();
    CharSequence d = node.isPassword() ? null : node.getContentDescription();
    boolean match =
        (t != null && t.toString().contains(text)) || (d != null && d.toString().contains(text));
    if (match) {
      // 文字节点常常自己不可点击，真正的按钮是它的某级父节点
      AccessibilityNodeInfo cur = node;
      for (int up = 0; up < 6 && cur != null; up++) {
        if (cur.isClickable() && cur.isEnabled()) {
          return AccessibilityNodeInfo.obtain(cur);
        }
        cur = cur.getParent();
      }
    }
    int cn = node.getChildCount();
    for (int i = 0; i < cn; i++) {
      AccessibilityNodeInfo ch = node.getChild(i);
      if (ch == null) continue;
      AccessibilityNodeInfo r = findClickableByText(ch, text, depth + 1);
      try {
        ch.recycle();
      } catch (Throwable ignored) {
      }
      if (r != null) return r;
    }
    return null;
  }

  /** 往当前焦点输入框填文字（没有焦点时要求先明确点选输入框） */
  public static String uiInput(String text) {
    DshaAccessibilityService s = instance;
    if (s == null) return com.deepseekharness.app.util.UiText.text(NOT_READY);
    if (text == null) return com.deepseekharness.app.util.UiText.text("[ERR] 文本不能为空");
    AccessibilityNodeInfo root = null;
    try {
      root = activeWindow(s);
      if (root == null) return com.deepseekharness.app.util.UiText.text("[ERR] 取不到当前窗口");
      AccessibilityNodeInfo target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);

      if (target == null || !target.isEditable() || !target.isEnabled())
        return com.deepseekharness.app.util.UiText.text("[ERR] 屏幕上没有输入框（先点一下要输入的位置）");
      android.os.Bundle args = new android.os.Bundle();
      args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
      boolean ok;
      try {
        ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
      } finally {
        try {
          target.recycle();
        } catch (Throwable ignored) {
        }
      }
      return ok
          ? com.deepseekharness.app.util.UiText.format("OK 已输入 %s 个字符", text.length())
          : com.deepseekharness.app.util.UiText.text("[ERR] 输入被系统拒绝");
    } catch (Throwable t) {
      return com.deepseekharness.app.util.UiText.format(
          "[ERR] 输入失败：%s", SensitiveData.redact(String.valueOf(t)));
    } finally {
      if (root != null) {
        try {
          root.recycle();
        } catch (Throwable ignored) {
        }
      }
    }
  }

  private static AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node, int depth) {
    if (node == null || depth > 24) return null;
    if (node.isEditable() && node.isEnabled()) return AccessibilityNodeInfo.obtain(node);
    int cn = node.getChildCount();
    for (int i = 0; i < cn; i++) {
      AccessibilityNodeInfo ch = node.getChild(i);
      if (ch == null) continue;
      AccessibilityNodeInfo r = findEditable(ch, depth + 1);
      try {
        ch.recycle();
      } catch (Throwable ignored) {
      }
      if (r != null) return r;
    }
    return null;
  }

  /** 全局按键：back / home / recents / notifications / quicksettings / lock */
  public static String uiKey(String name) {
    DshaAccessibilityService s = instance;
    if (s == null) return com.deepseekharness.app.util.UiText.text(NOT_READY);
    String k = name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
    int action;
    switch (k) {
      case "back":
        action = GLOBAL_ACTION_BACK;
        break;
      case "home":
        action = GLOBAL_ACTION_HOME;
        break;
      case "recents":
      case "recent":
        action = GLOBAL_ACTION_RECENTS;
        break;
      case "notifications":
      case "notification":
        action = GLOBAL_ACTION_NOTIFICATIONS;
        break;
      case "quicksettings":
      case "quick":
        action = GLOBAL_ACTION_QUICK_SETTINGS;
        break;
      case "lock":
        if (android.os.Build.VERSION.SDK_INT < 28)
          return com.deepseekharness.app.util.UiText.text("[ERR] 锁屏需要 Android 9+");
        action = GLOBAL_ACTION_LOCK_SCREEN;
        break;
      default:
        return com.deepseekharness.app.util.UiText.format(
            "[ERR] 不认识的按键「%s」（可用：back/home/recents/notifications/quicksettings/lock）", name);
    }
    try {
      verifyActionTargetIfAuthorized(); // 全局动作：有上下文才校验（见方法注释）
      return s.performGlobalAction(action)
          ? com.deepseekharness.app.util.UiText.format("OK 已发送 %s", k)
          : com.deepseekharness.app.util.UiText.format("[ERR] 系统拒绝了 %s", k);
    } catch (Throwable t) {
      return com.deepseekharness.app.util.UiText.format(
          "[ERR] 按键失败：%s", SensitiveData.redact(String.valueOf(t)));
    }
  }

  /** 滑动：翻页、下拉刷新、侧滑都靠它。durationMs 太短系统会当成甩动。 */
  public static String uiSwipe(int x1, int y1, int x2, int y2, int durationMs) {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) {
      return "手势滑动需 Android 7+（当前系统过旧）";
    }
    DshaAccessibilityService s = instance;
    if (s == null) return com.deepseekharness.app.util.UiText.text(NOT_READY);
    int dur = durationMs <= 0 ? 300 : Math.min(durationMs, 5000);
    android.graphics.Path path = new android.graphics.Path();
    path.moveTo(x1, y1);
    path.lineTo(x2, y2);
    android.accessibilityservice.GestureDescription.StrokeDescription stroke =
        new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, dur);
    return s.gesture(
        new android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build(),
        com.deepseekharness.app.util.UiText.format("滑动 (%d,%d)→(%d,%d)", x1, y1, x2, y2));
  }

  @androidx.annotation.RequiresApi(24)
  private static android.accessibilityservice.GestureDescription buildTap(int x, int y) {
    android.graphics.Path p = new android.graphics.Path();
    p.moveTo(x, y);
    return new android.accessibilityservice.GestureDescription.Builder()
        .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(p, 0, 50))
        .build();
  }

  /** 派发手势并等结果：dispatchGesture 是异步回调，agent 那边要的是同步答复 */
  @androidx.annotation.RequiresApi(24)
  private String gesture(android.accessibilityservice.GestureDescription gd, String what) {
    final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
    final boolean[] ok = {false};
    try {
      verifyActionTarget();
      boolean accepted =
          dispatchGesture(
              gd,
              new GestureResultCallback() {
                @Override
                public void onCompleted(android.accessibilityservice.GestureDescription d) {
                  ok[0] = true;
                  latch.countDown();
                }

                @Override
                public void onCancelled(android.accessibilityservice.GestureDescription d) {
                  latch.countDown();
                }
              },
              null);
      if (!accepted) return com.deepseekharness.app.util.UiText.format("[ERR] 手势未被接受（%s）", what);
      if (!latch.await(6, java.util.concurrent.TimeUnit.SECONDS)) {
        return com.deepseekharness.app.util.UiText.format("[ERR] 手势超时（%s）", what);
      }
      return ok[0]
          ? com.deepseekharness.app.util.UiText.format("OK 已%s", what)
          : com.deepseekharness.app.util.UiText.format("[ERR] 手势被取消（%s，可能被其它手势打断）", what);
    } catch (Throwable t) {
      return com.deepseekharness.app.util.UiText.format(
          "[ERR] 手势失败：%s", SensitiveData.redact(String.valueOf(t)));
    }
  }

  /** 截屏（Android 11+）。普通调用返回路径；MCP 返回同一次截图的图片块，
   *  不让 guest 再读取未挂载的 Android 多用户外部路径。 */
  @android.annotation.TargetApi(30)
  public static String uiScreenshot() {
    return uiScreenshot(() -> true, false);
  }

  @android.annotation.TargetApi(30)
  public static String uiScreenshot(java.util.function.BooleanSupplier validRun, boolean mcp) {
    DshaAccessibilityService s = instance;
    if (s == null) return com.deepseekharness.app.util.UiText.text(NOT_READY);
    if (!validRun.getAsBoolean()) return "[ERR] SCREENSHOT_RUN_CHANGED";
    if (android.os.Build.VERSION.SDK_INT < 30) {
      return com.deepseekharness.app.util.UiText.format(
          "[ERR] 截屏需要 Android 11 及以上（当前 API %s）", android.os.Build.VERSION.SDK_INT);
    }
    final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
    final String[] out = {com.deepseekharness.app.util.UiText.text("[ERR] 截屏无结果")};
    final java.util.concurrent.atomic.AtomicBoolean closed =
        new java.util.concurrent.atomic.AtomicBoolean();
    final java.util.concurrent.ExecutorService executor =
        java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      s.takeScreenshot(
          android.view.Display.DEFAULT_DISPLAY,
          executor,
          new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult result) {
              android.graphics.Bitmap hardware = null, bmp = null;
              try {
                if (closed.get() || instance != s || !validRun.getAsBoolean()) {
                  out[0] = "[ERR] SCREENSHOT_RUN_CHANGED";
                  return;
                }
                hardware =
                    android.graphics.Bitmap.wrapHardwareBuffer(
                        result.getHardwareBuffer(), result.getColorSpace());
                // Encode an owned software bitmap, then release both the
                // temporary copy and the framework HardwareBuffer.
                bmp =
                    hardware == null
                        ? null
                        : hardware.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                if (bmp == null) {
                  out[0] = com.deepseekharness.app.util.UiText.text("[ERR] 截屏数据无法解析");
                } else {
                  if (closed.get() || !validRun.getAsBoolean())
                    out[0] = "[ERR] SCREENSHOT_RUN_CHANGED";
                  else out[0] = saveShot(bmp, mcp);
                }
              } catch (Throwable t) {
                out[0] =
                    com.deepseekharness.app.util.UiText.format(
                        "[ERR] 保存截屏失败：%s", SensitiveData.redact(String.valueOf(t)));
              } finally {
                if (bmp != null) bmp.recycle();
                if (hardware != null) hardware.recycle();
                try {
                  result.getHardwareBuffer().close();
                } catch (Throwable ignored) {
                }
                latch.countDown();
              }
            }

            @Override
            public void onFailure(int errorCode) {
              out[0] =
                  com.deepseekharness.app.util.UiText.format(
                      errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
                          ? "[ERR] 截屏被系统拒绝（错误码 %d，太频繁了，隔一秒再试）"
                          : "[ERR] 截屏被系统拒绝（错误码 %d）",
                      errorCode);
              latch.countDown();
            }
          });
      if (!latch.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
        return com.deepseekharness.app.util.UiText.text("[ERR] 截屏超时");
      }
      return instance == s && validRun.getAsBoolean() ? out[0] : "[ERR] SCREENSHOT_RUN_CHANGED";
    } catch (Throwable t) {
      return com.deepseekharness.app.util.UiText.format(
          "[ERR] 截屏失败：%s", SensitiveData.redact(String.valueOf(t)));
    } finally {
      closed.set(true);
      executor.shutdownNow();
    }
  }

  /**
   * 截屏并**直接把像素交给调用方**（不落盘、不编码 PNG）。
   *
   * <p>给助手面板的「真玻璃」背景用：面板需要在自己还没画上屏幕的那一刻拿到下层画面，
   * 糊掉当卡片背景。走 {@link #uiScreenshot} 会先压 PNG 再 base64，白白多两次全图拷贝。
   *
   * @param timeoutMs 最多等多少毫秒；超时返回 null
   * @return 软件位图（ARGB_8888），调用方负责 {@code recycle()}；失败返回 null
   */
  @android.annotation.TargetApi(30)
  public static android.graphics.Bitmap uiScreenshotBitmap(long timeoutMs) {
    DshaAccessibilityService s = instance;
    if (s == null) return null;
    if (android.os.Build.VERSION.SDK_INT < 30) return null;
    final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
    final android.graphics.Bitmap[] out = new android.graphics.Bitmap[1];
    final java.util.concurrent.atomic.AtomicBoolean closed =
        new java.util.concurrent.atomic.AtomicBoolean();
    final java.util.concurrent.ExecutorService executor =
        java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      s.takeScreenshot(
          android.view.Display.DEFAULT_DISPLAY,
          executor,
          new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult result) {
              android.graphics.Bitmap hardware = null;
              try {
                if (closed.get() || instance != s) return;
                hardware =
                    android.graphics.Bitmap.wrapHardwareBuffer(
                        result.getHardwareBuffer(), result.getColorSpace());
                if (hardware != null) {
                  // 必须拷成软件位图：HardwareBuffer 关掉后硬件位图会失效。
                  android.graphics.Bitmap copy =
                      hardware.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                  if (copy != null) out[0] = copy;
                }
              } catch (Throwable ignored) {
              } finally {
                if (hardware != null)
                  try {
                    hardware.recycle();
                  } catch (Throwable ignored) {
                  }
                try {
                  result.getHardwareBuffer().close();
                } catch (Throwable ignored) {
                }
                latch.countDown();
              }
            }

            @Override
            public void onFailure(int errorCode) {
              latch.countDown();
            }
          });
      if (!latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) return null;
      return instance == s ? out[0] : null;
    } catch (Throwable t) {
      return null;
    } finally {
      closed.set(true);
      executor.shutdownNow();
    }
  }

  /** 保存到本应用外部私有目录，无需“所有文件访问”；MCP 通过原生响应传送图片。 */
  private static String saveShot(android.graphics.Bitmap bmp, boolean mcp) {
    try {
      DshaAccessibilityService service = instance;
      if (service == null) return com.deepseekharness.app.util.UiText.text(NOT_READY);
      if (mcp) {
        java.io.ByteArrayOutputStream encoded = new java.io.ByteArrayOutputStream();
        if (!bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, encoded))
          throw new java.io.IOException("PNG_ENCODING_FAILED");
        if (encoded.size() > 16 * 1024 * 1024) return "[ERR] SCREENSHOT_TOO_LARGE";
        return new org.json.JSONObject()
            .put("kind", "dsha-screenshot-v1")
            .put("mimeType", "image/png")
            .put(
                "data",
                android.util.Base64.encodeToString(
                    encoded.toByteArray(), android.util.Base64.NO_WRAP))
            .toString();
      }
      java.io.File base = service.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES);
      if (base == null)
        return com.deepseekharness.app.util.UiText.choose(
            "[ERR] 截屏存储暂不可用，请检查设备存储。",
            "[ERR] Screenshot storage is unavailable. Check device storage.");
      java.io.File dir = new java.io.File(base, "DSHA");
      if (!dir.isDirectory() && !dir.mkdirs()) {
        return com.deepseekharness.app.util.UiText.format("[ERR] 建不了目录 %s", dir);
      }
      java.io.File f =
          new java.io.File(
              dir,
              "screen-"
                  + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT)
                      .format(new java.util.Date())
                  + "-"
                  + java.util.UUID.randomUUID().toString().substring(0, 8)
                  + ".png");
      try (java.io.FileOutputStream fo = new java.io.FileOutputStream(f)) {
        if (!bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, fo))
          throw new java.io.IOException("PNG_ENCODING_FAILED");
      }
      return com.deepseekharness.app.util.UiText.format(
          "OK 截屏已保存：%s（%sx%s）", f.getAbsolutePath(), bmp.getWidth(), bmp.getHeight());
    } catch (Throwable t) {
      return com.deepseekharness.app.util.UiText.format(
          "[ERR] 写截屏文件失败：%s", SensitiveData.redact(String.valueOf(t)));
    }
  }

  @Override
  public void onInterrupt() {
    // 无需处理：本服务不提供持续反馈
  }

  @Override
  public boolean onUnbind(android.content.Intent intent) {
    // Some ROMs unbind without destroying the service. Do not report a
    // stale connected instance or keep a screen grant across that gap.
    com.deepseekharness.app.assistant.AssistantPanel.teardown();
    instance = null;
    stopWatch();
    com.deepseekharness.app.assistant.DshaWakeTile.clear(this);
    return super.onUnbind(intent);
  }

  @Override
  public void onDestroy() {
    com.deepseekharness.app.assistant.AssistantPanel.teardown();
    instance = null;
    stopWatch();
    com.deepseekharness.app.assistant.DshaWakeTile.clear(this);
    super.onDestroy();
  }
}
