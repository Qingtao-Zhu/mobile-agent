package com.deepseekharness.app.assistant;

import android.content.Context;

import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.CredentialRead;

import org.json.JSONObject;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「后续操作」执行器（2026-10-09 用户需求）。
 *
 * <p><b>用户原话</b>：目前的功能只是限制在打开应用的层级上，并不能进行后续操作。
 * 比如我想在 Telegram 上给自己的机器人"小飞鱼"发一个"你好"，它只是给我打开了 Telegram，
 * 并没有去进行后面的操作。我的想法是，在语音助手后面接上 DSHA：
 * (a) 如果有网，就用 DSHA 执行后续操作；(b) 如果没网，那就单单做一个打开应用等简单的操作。
 *
 * <p><b>实现思路（正好踩在已有地基上）</b>：{@link DshaAccessibilityService} 早就把动作原语
 * 写齐了 —— {@code uiDumpUnderPanel()}（读当前界面）、{@code uiTapText}（按文字点）、
 * {@code uiTap}（按坐标点）、{@code uiInput}（写输入框）、{@code uiKey}（返回键）、
 * {@code uiSwipe}（滑）。缺的只是"决定下一步点哪儿"的大脑。本类补的就是这个大脑：
 * <ol>
 *   <li>离线正则把话拆成任务（在哪个应用、给谁、干什么、内容是什么）；
 *   <li>打开应用后进入<b>模型驱动的执行循环</b>：每步读屏 → 把精简界面文本发给模型 →
 *   模型回一个 JSON 动作 → 用现成原语执行 → 再读屏，最多 12 步；
 *   <li>没网就根本不进这个类：调用方（{@code AssistantCommands.taskOps}）只开应用并如实说。
 * </ol>
 *
 * <p><b>安全边界</b>：要发送的内容只允许是用户原话里的字（模型只负责"找到发送按钮"，
 * 不负责"替用户编写内容"）；面板一收（用户不想看了）立即取消；步数用完就停手如实汇报，
 * 绝不无限重试。
 */
final class AssistantTaskRunner {

  /** 解析出的任务。 */
  static final class Task {
    final String app;
    final String goal;
    final String target;
    final String content;
    final String kind;
    /** 目标应用包名（taskOps 打开应用后回填），执行循环等它到前台再开跑。 */
    String pkg;

    Task(String app, String goal, String target, String content, String kind) {
      this.app = app;
      this.goal = goal;
      this.target = target;
      this.content = content;
      this.kind = kind;
    }
  }

  /** 最多连续动作数。到点如实汇报，别无限重试把用户的手机点成筛子。 */
  private static final int MAX_STEPS = 16;
  /** 每步之间等 UI 稳定的时间。 */
  private static final long STEP_SETTLE_MS = 800L;
  /** 单次喂模型的界面文本上限（token 安全）。 */
  private static final int DUMP_MAX_CHARS = 6000;

  /**
   * 「在 Telegram 上给自己的机器人'小飞鱼'发一个'你好'」这类发消息任务。
   * 分组：1=应用 2=收件人 3=内容。
   */
  private static final Pattern P_SEND =
      Pattern.compile(
          "^(?:帮我|请|麻烦)?\\s*(?:在|用|打开)\\s*(.{1,20}?)\\s*(?:上|里|中|里面)?\\s*[，,]?\\s*"
              + "(?:给|跟|对|和)\\s*(.{1,24}?)\\s*(?:发|发送|回复|回)\\s*"
              + "(?:一条|一个|条|个)?\\s*(?:消息|信息|短信)?\\s*"
              + "[「\"'\\u201c]?(.{1,80}?)[」\"'\\u201d]?$");

  /** 「在淘宝上搜一下机械键盘」这类应用内搜索。分组：1=应用 2=关键词。 */
  private static final Pattern P_SEARCH_IN_APP =
      Pattern.compile(
          "^(?:帮我|请)?\\s*(?:在|用)\\s*(.{1,20}?)\\s*(?:上|里|中|里面)?\\s*(?:帮我)?\\s*"
              + "(?:搜一下|搜索|搜|查一下|查查|查|找一下|找找|找)\\s*"
              + "[「\"'\\u201c]?(.{1,60}?)[」\"'\\u201d]?$");

  private static final String QUOTES = "「」\"'‘’“”";

  private AssistantTaskRunner() {}

  // ---------------------------------------------------------------- 解析

  /** 这句话是不是一个"要接着办"的任务。不是返回 null。 */
  static Task parse(String q) {
    if (q == null) return null;
    String s = q.trim();
    if (s.isEmpty() || s.length() > 60) return null;

    Matcher m = P_SEND.matcher(s);
    if (m.matches()) {
      String app = tidy(m.group(1));
      String target = pick(m.group(2));
      String content = pick(m.group(3));
      if (app.isEmpty() || content.isEmpty()) return null;
      return new Task(
          app,
          "在应用「" + app + "」里给联系人「" + target + "」发送消息「" + content + "」",
          target,
          content,
          "send");
    }
    m = P_SEARCH_IN_APP.matcher(s);
    if (m.matches()) {
      String app = tidy(m.group(1));
      String kw = pick(m.group(2));
      if (app.isEmpty() || kw.isEmpty()) return null;
      return new Task(app, "在应用「" + app + "」里搜索「" + kw + "」", kw, null, "search");
    }
    return null;
  }

  /** 应用名清理：去空格、去"帮我打开"残留、去尾部语气词。 */
  private static String tidy(String s) {
    if (s == null) return "";
    String out = s.replace("帮我", "").replace("打开", "").trim();
    return out.replaceAll("[啊吧呢了嘛哦呗]+$", "").trim();
  }

  /**
   * 收件人/内容提取：优先取引号里那段（"机器人'小飞鱼'" → 小飞鱼）；
   * 没引号就去掉"我的/自己的/咱们"前缀，再冒昧去掉"机器人"仨字 —— 用户口中的
   * "我的机器人小飞鱼"在应用里就叫"小飞鱼"。找不到引号时保守处理，只剥前缀。
   */
  private static String pick(String s) {
    if (s == null) return "";
    String t = s.trim();
    for (int i = 0; i < t.length(); i++) {
      char c = t.charAt(i);
      if (QUOTES.indexOf(c) >= 0) {
        int close = t.indexOf(c, i + 1);
        if (close > i + 1) return t.substring(i + 1, close).trim();
      }
    }
    t = t.replace("我的", "").replace("自己的", "").replace("咱们", "").trim();
    if (t.startsWith("机器人")) t = t.substring(3).trim();
    return t.replaceAll("[啊吧呢了嘛哦呗]+$", "").trim();
  }

  // ---------------------------------------------------------------- 执行

  private static Thread worker;

  /**
   * 任务代次（2026-10-11 审计 D-02 修复；随后抽成 {@link TaskGeneration} 以便单测）。
   *
   * <p><b>原来有什么问题</b>：取消只看一个全局 {@code cancelled} 布尔，而
   * {@code start()} 的写法是 {@code cancelAll(); cancelled = false;} ——
   * 先把旧任务标记取消、紧接着又把标志重置。如果旧任务此刻正卡在**两步之间**
   * （比如一次 6 秒的手势超时、或者模型请求的几十秒里），它会错过那个瞬间的
   * {@code true}，等它回到检查点时看到的已经是新任务设的 {@code false}，
   * 于是**继续执行** —— 新旧两个任务同时对屏幕做手势，互相点对方的界面。
   *
   * <p><b>怎么修的</b>：给每次 {@code start()} 发一个递增代次，任务线程把自己那一代
   * 记在 {@code gen} 里。检查点判"我还是不是最新那一代"，与"有没有错过某次布尔翻转"无关。
   *
   * <p><b>为什么抽成独立类</b>：用户要求并发测试必须**能稳定复现竞态**。
   * 原来这套逻辑是静态字段+私有方法，JVM 测不到也控制不了时序；
   * 抽成实例之后测试可以自己 new 一个、用 latch 精确编排交错时序。
   */
  private static final TaskGeneration TASKS = new TaskGeneration();

  /** 本代次是否已失效（被取消，或被更新的任务顶替）。 */
  private static boolean stale(int gen) {
    return TASKS.isStale(gen);
  }

  /** 面板收起 / 服务断开 / 新任务顶掉旧任务时，都从这里停。 */
  static void cancelAll() {
    if (worker != null && worker.isAlive()) {
      android.util.Log.i("DSHA/Task", "任务被取消（面板收起/服务断开/新任务顶替）");
    }
    // cancel() 内部同时推进代次：不依赖"某一刻置的 true 被旧任务看到"，
    // 旧任务在任何检查点都会因为代次不符而退出（D-02 修复）。
    TASKS.cancel();
  }

  /** 现在有没有网（调用方决定"接着办"还是"只开应用"）。 */
  static boolean online(Context ctx) {
    try {
      android.net.ConnectivityManager cm =
          (android.net.ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
      android.net.NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
      return ni != null && ni.isConnected();
    } catch (Throwable ignored) {
      return false;
    }
  }

  /**
   * 开跑。调用方已经把应用打开了，这里从"等应用稳定"开始。
   * 全程在后台线程，进度/结果通过 {@link AssistantPanel#note} / {@code AssistantPanel.finishTask} 回面板。
   */
  static void start(
      DshaAccessibilityService host, AssistantConfig cfg, Task task, String appLabel) {
    cancelAll(); // 先把旧的标记取消（内部推进代次 → 旧任务立即失效）
    final int gen = TASKS.begin(); // 本任务所属代次（同时清掉取消态）
    // D-10：原来打的是 task.goal 正文（用户让它干什么），改成结构化的目标长度 + 目标应用名。
    // appLabel 是应用显示名（"Telegram"这种），不是用户内容，保留它才有利于排障。
    android.util.Log.i("DSHA/Task", LogFormat.taskStart(gen, task.goal, appLabel));
    // 面板对触摸透明：执行器按坐标做手势点按，不能被面板挡住顶部的元素
    AssistantPanel.setTaskMode(true);
    final Thread me =
        new Thread(
            () -> {
              try {
                runLoop(host, cfg, task, appLabel, gen);
              } catch (Throwable t) {
                // D-10：异常消息过 redact（异常里可能带 URL/请求体/凭据片段），
                // 面板文案同样过一遍 —— 它也可能把 URL 显示给用户看。
                String detail =
                    com.deepseekharness.app.util.SensitiveData.redact(String.valueOf(t));
                android.util.Log.w("DSHA/Task", "任务执行炸了: " + detail);
                finish("⚠ 任务中断：" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : "（" + detail + "）"));
              }
            },
            "dsha-task");
    worker = me;
    me.start();
  }

  /** 统一收口：进日志 + 进面板。排障时日志是权威，面板只是给人看的。 */
  private static void finish(String msg) {
    // D-10：msg 里含任务内容（"没办成：给小明发消息…"）—— 它该进**面板**（用户自己看），
    // 但不该进 logcat。日志只记"有没有内容 + 多长"，够定位"哪一步结束的"。
    android.util.Log.i("DSHA/Task", LogFormat.taskEnd(msg));
    // 无论成败都把面板的触摸还回来 —— 任务模式是临时的，不能欠着不还
    AssistantPanel.setTaskMode(false);
    AssistantPanel.finishTask(msg);
  }

  private static void runLoop(
      DshaAccessibilityService host, AssistantConfig cfg, Task task, String appLabel, int gen)
      throws Exception {
    // 应用刚被 Intent 顶起来，等它真的到前台再开跑 —— 不等的话第一轮读屏抓到的是桌面，
    // 模型就会对着桌面上不存在的按钮瞎指挥（2026-10-09 真机踩到：第 1 步就要点"请稍后提醒我"）。
    long waited = 0L;
    while (waited < 8000L) {
      String fg = DshaAccessibilityService.currentPackage();
      if (task.pkg == null || (fg != null && fg.contains(task.pkg))) break;
      Thread.sleep(400L);
      waited += 400L;
    }
    android.util.Log.i("DSHA/Task", "前台等待 " + waited + "ms，当前包=" + DshaAccessibilityService.currentPackage());
    if (stale(gen)) return; // D-02：代次不符也算失效（被新任务顶替）

    // ===== 任务级授权上下文（2026-10-11 修复 D-01）=====
    //
    // 抓取"本任务认准的界面身份"（显示器 + 窗口 + 包名）。之后每次手势派发前，
    // 底层 DshaAccessibilityService.verifyActionTarget() 会复查"现在还是不是这个身份"，
    // 不是就抛 SCREEN_TARGET_CHANGED 拒绝执行。
    //
    // 修复前的状况：runAuthorized() 在整个轻量版里**没有任何调用方**
    // （原 DSHA 只有 HttpShellService 用它），于是 ACTION_AUTHORITY 恒为 null，
    // verifyActionTarget() 里那个 `authority != null` 判断永远为假 ——
    // 这套页面变化防护在助手路径上等于不存在。
    //
    // 抓不到身份（比如当前界面读不出 root）就不开工：宁可不开车，也不上没刹车的车。
    final com.deepseekharness.app.util.ScreenTarget taskTarget =
        DshaAccessibilityService.observeTarget(0);
    if (taskTarget == null || taskTarget.packageName.isEmpty()) {
      finish("⚠ 读不到当前界面的身份（无法确认现在是什么应用），为安全起见先停手。");
      return;
    }
    android.util.Log.i("DSHA/Task", "任务授权上下文: 包=" + taskTarget.packageName
        + " 窗口=" + taskTarget.windowId + " 显示器=" + taskTarget.displayId);

    AssistantPanel.note("好，我来接着办：" + task.goal);
    StringBuilder trace = new StringBuilder();
    int sameAction = 0;
    String lastAction = "";
    // 「点了没反应」检测：上一步是点击、两步之间屏幕一个字都没变 => 上一步八成点进了
    // 容器空白处（Telegram 的发送按钮节点宽 200px、真图标在最右端，点几何中心是打水漂的，
    // 2026-10-09 真机实测连 adb input tap 都发不出去）。这时要给模型指条明路。
    String lastDumpNorm = null;
    String noChangeHint = null;

    for (int step = 1; step <= MAX_STEPS && !stale(gen); step++) {
      String dump = DshaAccessibilityService.uiDumpUnderPanel();
      if (dump == null || dump.startsWith("[ERR]")) {
        finish("⚠ 读不到「" + appLabel + "」的界面（" + step + "步时），先停手。"
            + "有些应用会屏蔽辅助功能读取，那种只能你自己点。");
        return;
      }
      // ===== 读屏留痕（2026-10-11 修复 D-06：不再记录屏幕文字正文）=====
      //
      // 原来这里打的是 dump 的前 200~240 个字符 —— 排障价值确实高，但**等于把
      // 用户此刻屏幕上的文字（可能是聊天记录、通知内容）写进 logcat**。
      // `SensitiveData.redact` 救不了：它只认密钥/令牌模式，认不出"聊天内容"。
      //
      // 现在改记**结构性指标**：应用包名 + 节点行数 + 带 viewId / contentDescription 的数量 + 字符数。
      // 这几项足够排"模型是不是看到了不一样的界面""这屏有没有可定位的节点"
      // （这正是 2026-10-09 靠 head 定位到的那类问题），但**不含任何屏幕文字**。
      //
      // 包名那行本来就只有 "窗口应用: <包名>"，没有内容文字，可以安全保留。
      {
        int nodeLines = 0, withId = 0, withCd = 0;
        for (String ln : dump.split("\n")) {
          if (ln.startsWith("[")) nodeLines++;
          if (ln.contains(" id=")) withId++;
          if (ln.contains(" cd=\"")) withCd++;
        }
        int nl = dump.indexOf('\n');
        String pkgLine = (nl > 0 ? dump.substring(0, nl) : dump).trim();
        // D-15 修复（Phase 13）：原来这里是内联拼串，和 LogFormat.dumpMetrics
        // 的输出逐字符一样，但**绕开了那个函数** —— 结果是 LogFormatTest 里
        // "读屏指标不含节点文本"那条行为测试守护的是一段生产根本不走的影子逻辑，
        // 真正跑在生产里的这行反而没有任何行为级覆盖。
        // 改回调同一个函数，输出不变，测试才真的守在生产路径上。
        String metrics = LogFormat.dumpMetrics(pkgLine, nodeLines, withId, withCd, dump.length());
        if (step == 1) {
          // 第一轮界面长什么样必须留痕（现在用指标代替正文）
          android.util.Log.i("DSHA/Task", "第一轮读屏指标: " + metrics);
        } else {
          // 每步留痕：排「连点同一个字没反应」这类问题
          android.util.Log.d("DSHA/Task", "第" + step + "步读屏指标: " + metrics);
        }
      }
      if (dump.length() > DUMP_MAX_CHARS) dump = dump.substring(0, DUMP_MAX_CHARS);

      // ===== 敏感应用守卫（纵深防御，2026-10-11 审计 D-04）=====
      //
      // 原来这道防线只在 AssistantPanel 那一层：它对敏感应用不读屏、不把屏幕内容给模型，
      // 于是"模型拿不到内容 → 无从下令点 → 自然不会点"。这是**链式保护**，
      // 前提是"敏感内容永远只从面板那一个口子进来"。审计确认这个前提不稳健：
      // ① 本类（AssistantTaskRunner）作为点击的**唯一实际执行者**，自己没有任何守卫；
      // ② 将来若接入别的内容来源（如定位器自检、通知路由），前提立刻失效。
      //
      // 所以在这里加一道**独立**检查：每步拿到新 dump 就重新判一次当前前台应用。
      // 放在循环内（而不是任务开始时判一次）是因为任务过程中可能跳转/切 App，
      // 只有逐步判才能覆盖"中途切到支付宝"这种情况。
      try {
        // 判定逻辑收敛到 GuardDecision（纯函数、JVM 可测、生产与测试共用同一份）。
        // 顺序：身份未知 → 停手；受保护应用 → 不读不点；系统设置 → 不主动操作。
        // D-07：身份未知原本会被"非空"条件漏过去（放行），现在它是第一优先级。
        String curPkg = GuardDecision.extractPackage(dump);
        GuardDecision.Verdict verdict = GuardDecision.decide(curPkg, cfg.allowedPackages);
        if (GuardDecision.mustStop(verdict)) {
          android.util.Log.w("DSHA/Task",
              "第" + step + "步守卫拦截: " + GuardDecision.logReason(verdict, curPkg));
          finish(GuardDecision.userMessage(verdict, curPkg));
          return;
        }
      } catch (Throwable ignored) {
        // 守卫本身出错时不阻断主流程（宁可少一道防护，也不能让守卫把正常任务搞崩）
      }

      String norm = dump.replaceAll("\\s+", " ");
      if (lastDumpNorm != null && norm.equals(lastDumpNorm) && lastAction.startsWith("点")) {
        noChangeHint =
            "（注意：上一步「" + lastAction + "」执行了，但屏幕没有任何变化 —— 八成点进了容器"
                + "的空白处（宽容器的按钮图标常在左端或右端，不在几何中心）。"
                + "改用 tap_xy，从目标的 区域=l,t,r,b 里挑真正图标的中心点，"
                + "比如发送按钮通常在 区域 的右端。）";
      } else {
        noChangeHint = null;
      }
      lastDumpNorm = norm;

      String action = askAction(host, cfg, task, trace, dump, noChangeHint);
      JSONObject a = extractJson(action);
      if (a == null) {
        // 模型偶尔回空串/废话（2026-10-09 真机第 4 步踩到：整条回复是空的）。
        // 直接弃赛太亏 —— 补一句"只回一个 JSON"重问一次，还不行才停手。
        android.util.Log.i("DSHA/Task", "第 " + step + " 步模型没按格式回话（len="
            + (action == null ? -1 : action.length()) + "），重问一次");
        action = askAction(host, cfg, task, trace,
            dump + "\n（刚才你回的不是 JSON。只回一个 JSON 对象，禁止任何其他文字。）",
            noChangeHint);
        a = extractJson(action);
      }
      if (a == null) {
        finish("⚠ 执行器没按格式回话（" + step + "步），先停手，别乱点。");
        return;
      }
      String act = a.optString("act", "");
      String one = describe(act, a);

      // 原地打转检测：连续 3 步一模一样的动作 = 大概率卡死了
      if (one.equals(lastAction)) sameAction++;
      else sameAction = 1;
      lastAction = one;
      if (sameAction >= 3) {
        finish("⚠ 连着几步都在原地打转（" + one + "），我停手了，剩下的你来。");
        return;
      }
      if (trace.length() > 3000) trace.setLength(0);
      trace.append("第").append(step).append("步: ").append(one).append('\n');

      if ("done".equals(act)) {
        finish("办好了：" + a.optString("note", task.goal));
        return;
      }
      if ("fail".equals(act)) {
        finish("没办成：" + a.optString("why", "模型说不清原因") + "。"
            + "已经做了：" + (trace.length() == 0 ? "（一步都没走）" : trace.toString().trim()));
        return;
      }
      // ===== 带授权上下文执行（D-01 修复的核心）=====
      //
      // runAuthorized 把一个"授权作用域"绑到当前线程：作用域里带了
      //   ① taskTarget —— 本任务认准的界面身份
      //   ② live        —— 本任务是否还有效（这里直接复用代次判断）
      // 作用域生效期间，底层每次取窗口/派发手势都会先 verifyActionTarget()：
      //   · live 为假（任务被取消/被顶替）  → 抛 SCREEN_TARGET_CHANGED
      //   · 界面身份与 taskTarget 不符      → 抛 SCREEN_TARGET_CHANGED
      // 两种情况都会**拒绝手势**，而不是降级成"无保护执行"。
      //
      // 作用域用完由 runAuthorized 的 finally 自动清理（恢复上一层或 remove），
      // 所以不会把授权残留给别的任务 —— 即便残留，live 绑的是本任务代次，也过不了校验。
      // 注意：runAuthorized 的 action 是 Supplier<String>（它本来给"返回一行结果文本"的场景用），
      // 而 execute() 返回 boolean —— 所以用 "ok"/"fail" 包一层再解回来。
      boolean stepOk;
      try {
        // dump 在本循环里被重新赋值过（截断），不是 effectively final，
        // 所以进 lambda 前先取一份 final 副本。
        final String dumpForRun = dump;
        final JSONObject actionForRun = a;
        String r =
            DshaAccessibilityService.runAuthorized(
                taskTarget,
                () -> !stale(gen),
                () -> execute(host, act, actionForRun, dumpForRun) ? "ok" : "fail");
        stepOk = "ok".equals(r);
      } catch (IllegalStateException e) {
        if ("SCREEN_TARGET_CHANGED".equals(e.getMessage())) {
          // 界面在执行过程中变了（切了 App / 换了窗口）：停手重新评估，绝不沿用旧坐标硬点
          android.util.Log.w("DSHA/Task",
              "第" + step + "步检出界面变化（SCREEN_TARGET_CHANGED），停手重新评估");
          finish("⚠ 界面在操作过程中变了（不再是我认准的那个应用/窗口），我停手了。"
              + "已经做了：" + (trace.length() == 0 ? "（一步都没走）" : trace.toString().trim()));
          return;
        }
        throw e;
      }
      if (!stepOk) {
        finish("⚠ 第 " + step + " 步没点动（" + one + "），先停手。");
        return;
      }
      AssistantPanel.note("（" + step + "/" + MAX_STEPS + "）" + one);
      Thread.sleep(STEP_SETTLE_MS);
    }
    if (!stale(gen)) {
      finish("步数用完了还没办完，先停手。已经做了："
          + (trace.length() == 0 ? "（一步都没走）" : trace.toString().trim()));
    }
  }

  /** 宽容器判定：节点比高宽出这么多倍时，"中心"很可能不是图标真正的位置。 */
  private static final int WIDE_CONTAINER_MIN_PX = 140;
  /** 点按后等界面响应的时间，随后读屏对比是否发生了变化。 */
  private static final long TAP_VERIFY_SETTLE_MS = 700L;
  /**
   * 发送类按钮的"二次确认"等待：第一次读屏（TAP_VERIFY_SETTLE_MS 后）会被键盘收起 / 表情 /
   * 语音面板弹出的瞬间骗过（输入框被遮挡、文本属性临时为空，看起来像清空了，其实没发），
   * 必须再等这一段时间让键盘/面板完全落定后重读，框里若又冒出草稿就是假清空。
   */
  private static final long SEND_CONFIRM_SETTLE_MS = 1400L;

  private static boolean screenChanged(String before, String after) {
    if (after == null || after.startsWith("[ERR]")) return true; // 读不到就别重试，免得乱点
    return !before.replaceAll("\\s+", " ").equals(after.replaceAll("\\s+", " "));
  }

  /** 点一下并读屏对比：返回 true = 屏幕确实变了（这一下生效了）。 */
  private static String tapAndVerify(DshaAccessibilityService host, int x, int y, String before) {
    String r = DshaAccessibilityService.uiTap(x, y);
    if (r == null || r.startsWith("[ERR]")) return null;
    try {
      Thread.sleep(TAP_VERIFY_SETTLE_MS);
    } catch (InterruptedException ignored) {
    }
    String after = DshaAccessibilityService.uiDumpUnderPanel();
    return screenChanged(before, after) ? (after == null ? "" : after) : null;
  }

  /** 从 dump 里抠某个文字所在行的 区域=l,t,r,b。 */
  private static int[] rectFromDump(String dump, String text) {
    if (dump == null || text == null || text.isEmpty()) return null;
    for (String line : dump.split("\n")) {
      if (!line.contains(text)) continue;
      java.util.regex.Matcher m =
          java.util.regex.Pattern.compile("区域=(-?\\d+),(-?\\d+),(-?\\d+),(-?\\d+)").matcher(line);
      if (m.find()) {
        try {
          return new int[] {
            Integer.parseInt(m.group(1)),
            Integer.parseInt(m.group(2)),
            Integer.parseInt(m.group(3)),
            Integer.parseInt(m.group(4))
          };
        } catch (Throwable ignored) {
        }
      }
    }
    return null;
  }

  /**
   * 发送类按钮专用。同一个"发送"字眼在屏上可能同时冒出好几个：应用输入栏右下角那个
   * （content-desc="发送"，窄 ~200px，位于键盘上方 y≈991）、软键盘自带的发送/回车键
   * （全宽、贴屏幕最底 y≈1327）、以及聊天里旧文件消息行里出现的"发送/分享"字样
   * （可能在屏幕中部 y≈526）。取"最靠上"会点到中部那行假发送；取第一个匹配会点到键盘键。
   *
   * <p>2026-10-09 复测教训：只按"最靠上"筛，模型点到了 y=526 的旧文件横幅「发送」，
   * 输入框纹丝不动。真·发送键的可靠特征 = <b>坐落在屏幕最底部的输入栏区域</b>
   * （bottom 在屏幕高度的 ~55% 以下、且不低于键盘键），所以这里改成：
   * 先只保留 top 落在屏幕下半区（> 屏幕高 55%）的候选，再在这些里取 top 最小的
   * （避开贴底的键盘键 y≈1327）；若一个都没有才回退到全部候选取最靠上。
   */
  private static int[] bestSendRect(String dump, String text) {
    if (dump == null || text == null || text.isEmpty()) return null;
    int screenH = 1600; // 真机 720×1600；比例筛选够用
    int lowerBound = (int) (screenH * 0.55); // y=880 以下才算"底部输入栏区"
    int[] best = null;
    int bestTop = Integer.MAX_VALUE;
    int[] fallback = null;
    int fallbackTop = Integer.MAX_VALUE;
    for (String line : dump.split("\n")) {
      if (!line.contains(text)) continue;
      // 关键：只认 "发送" 本身就是这一行的主体文字/描述，别被消息气泡里的
      // "你好 已发送于 于 PM11:43, 已读" 这种行骗到 —— 那种行也含"发送"二字，
      // 2026-10-09 复测实测把发送键点到了 y≈526 的聊天气泡上，草稿纹丝不动。
      // dump 行格式：`[n] "文字" [可点击] 中心=(x,y) 区域=l,t,r,b` 或 `[n] 可点击 中心=… 区域=…`。
      // 这里要求引号里的文字（或"发送"紧邻引号/可点击标记）基本等于"发送"。
      boolean exactName = isExactSendLabel(line, text);
      if (!exactName) continue;
      java.util.regex.Matcher m =
          java.util.regex.Pattern.compile("区域=(-?\\d+),(-?\\d+),(-?\\d+),(-?\\d+)").matcher(line);
      if (m.find()) {
        try {
          int l = Integer.parseInt(m.group(1));
          int t = Integer.parseInt(m.group(2));
          int r = Integer.parseInt(m.group(3));
          int b = Integer.parseInt(m.group(4));
          // 全兜底：记录全局最靠上
          if (t < fallbackTop) {
            fallbackTop = t;
            fallback = new int[] {l, t, r, b};
          }
          // 首选：只在下半区（底部输入栏）里挑最靠上的
          if (t >= lowerBound && t < bestTop) {
            bestTop = t;
            best = new int[] {l, t, r, b};
          }
        } catch (Throwable ignored) {
        }
      }
    }
    return best != null ? best : fallback;
  }

  /**
   * 判断 dump 行里那个"发送"是不是真的发送按钮的标签，而不是消息气泡正文里的"已发送于"。
   * dump 行长这样：
   *   [3] "你好&#10;已发送于 于 PM11:43, 已读&#10;" 中心=(360,1313) 区域=0,1226,720,1401   ← 气泡，要排除
   *   [9] "发送" 可点击 中心=(606,1523) 区域=506,1494,706,1552                          ← 真按钮
   *   [9] 可点击 中心=(606,1523) 区域=506,1494,706,1552（content-desc 落在读屏里等同文字）← 真按钮
   * 判据：引号内的文字去掉换行/空白后必须<b>恰好等于</b>目标词（"发送"）；没有引号时，
   * 目标词必须出现在"可点击"之前的一小段里（说明它是该行的主角文字）。
   */
  private static boolean isExactSendLabel(String line, String text) {
    int q1 = line.indexOf('"');
    int q2 = line.lastIndexOf('"');
    if (q1 >= 0 && q2 > q1) {
      String inner = line.substring(q1 + 1, q2).replaceAll("[\\s\\u00a0]+", "");
      if (inner.equals(text)) return true;
      // 气泡那种长文本里带"已发送于"，inner 会长得多，直接否掉
      return false;
    }
    // 没引号：要求 text 出现在"可点击"标记之前，且该行不含"已发送/已读/于 "这类气泡标记
    if (line.contains("已发送") || line.contains("已读")) return false;
    int ci = line.indexOf("可点击");
    int ti = line.indexOf(text);
    return ti >= 0 && (ci < 0 || ti < ci);
  }

  /**
   * 执行一个动作。返回 false 表示这步没干成（原语自己会返回错误串）。
   *
   * <p>tap 与 input 都走<b>坐标优先</b>：dump 是上一行刚读的，中心坐标最可信；
   * 手势点按是"真触摸"，在 Telegram 这类自绘界面上一定生效。节点 ACTION_CLICK 在
   * 自绘控件上会"假成功"（performAction 返回 true 但界面没反应），只配当备胎。
   *
   * <p>tap 还有<b>端点重试</b>：宽容器按钮（发送键节点宽 200px、圆形图标只占右端 80px）
   * 点几何中心会踩进容器空白处 —— 手势发出去了、界面纹丝不动（2026-10-09 真机实测，
   * 连 adb input tap 都一样）。所以点完中心要读屏对比，没变化就自动改点区域左/右端再试。
   */
  private static boolean execute(
      DshaAccessibilityService host, String act, JSONObject a, String dump) {
    try {
      switch (act) {
        case "tap":
          {
            String text = a.optString("text", "");
            // 输入框类目标直接点一次拿焦点即可（软键盘是独立窗口，不要求"屏幕变化"来判定成功）
            if (isInputLikeTarget(dump, text)) {
              int[] ic = centerFromDump(dump, text);
              if (ic != null) {
                String ir = DshaAccessibilityService.uiTap(ic[0], ic[1]);
                if (ir != null && !ir.startsWith("[ERR]")) return true;
              }
              String ir2 = DshaAccessibilityService.uiTapText(text);
              return ir2 != null && !ir2.startsWith("[ERR]");
            }
            // 发送类按钮只认"按钮从屏幕上消失"（框清空后 Telegram 会把发送键换回回形针/麦克风），
            // 屏幕其他变化（键盘联想条、聊天动画、旧气泡）都不算 —— 否则会被键盘噪声误判成
            // "点了没变化→成功"，消息其实还躺在输入框里，模型还会被旧的相同气泡骗过去说 done
            // （2026-10-09 晚实测：发送键点了没发出去，模型看到 8 小时前的旧"你好"气泡直接 done）。
            boolean sendLike = isSendLikeTarget(text);
            String beforeInput = inputText(dump);
            if (sendLike) {
              // 发送前先收起遮挡层（表情面板 / 软键盘）：蓝色发送键（content-desc="发送"）被
              // 键盘/表情面板压住时就抓不到，只能抓到键盘自带的"发送"键（全宽、贴最底）点点错。
              //
              // 2026-10-09 血泪修正：**收键盘绝不能盲按 BACK**。键盘已经收起时再按 BACK 会直接
              // 退出当前应用 —— 真机实测连按两次 BACK 从 Telegram 聊天页退到列表、再退到桌面，
              // 任务直接跑飞（这也是 retest3/4/5 反复失败的元凶）。所以这里改成：
              // ① 先看 dump 里能不能直接找到蓝色发送键，能就直接用；
              // ② 找不到时，只有在 {@link #isImeLikelyOpen} 判定"键盘确实在"（输入框被顶到
              //    屏幕中上部、下方留出一大块给键盘）才按一次 BACK 收起，按完立刻重读；
              //    判不出键盘在就绝不按（宁可留着遮挡走坐标兜底，也不能把应用退出去）。
              String fresh0 = DshaAccessibilityService.uiDumpUnderPanel();
              if (fresh0 != null && !fresh0.startsWith("[ERR]") && bestSendRect(fresh0, text) != null) {
                dump = fresh0;
              } else if (isImeLikelyOpen(fresh0 != null && !fresh0.startsWith("[ERR]") ? fresh0 : dump)) {
                DshaAccessibilityService.uiKey("back"); // 只按一次：键盘在才按
                try {
                  Thread.sleep(450L);
                } catch (InterruptedException ignored) {
                }
                String fresh1 = DshaAccessibilityService.uiDumpUnderPanel();
                if (fresh1 != null && !fresh1.startsWith("[ERR]")
                    && bestSendRect(fresh1, text) != null) {
                  dump = fresh1;
                }
              }
            }
            int[] rect = sendLike ? bestSendRect(dump, text) : rectFromDump(dump, text);
            int[] c = (sendLike && rect != null)
                ? new int[] {(rect[0] + rect[2]) / 2, (rect[1] + rect[3]) / 2}
                : centerFromDump(dump, text);
            // 候选点位顺序：宽容器（发送键这类，图标只占一端）→ 右端、左端在前，中心垫底；
            // 普通按钮中心就是图标 → 中心优先
            java.util.List<Object[]> pts = new java.util.ArrayList<>();
            if (rect != null && rect[2] - rect[0] >= WIDE_CONTAINER_MIN_PX) {
              int cy = (rect[1] + rect[3]) / 2;
              pts.add(new Object[] {rect[2] - 36, cy, "右端"});
              pts.add(new Object[] {rect[0] + 36, cy, "左端"});
            }
            if (c != null) pts.add(new Object[] {c[0], c[1], "中心"});
            for (Object[] p : pts) {
              int x = (Integer) p[0];
              int y = (Integer) p[1];
              String after = tapAndVerify(host, x, y, dump);
              if (after == null) continue; // 屏幕没变化，下一个点
              if (sendLike) {
                // 发送成功的硬判据（2026-10-09 复测踩坑升级版）：
                // ① 第一次读屏（TAP_VERIFY_SETTLE_MS 后）看到"输入框清空"不算数 —— 键盘收起 /
                //    表情/语音面板弹出的瞬间，输入框被遮挡、文本属性临时为空，看起来像清空了，
                //    其实消息根本没发出（实测三次"右端点 生效"全是这种假清空，草稿一直在框里）。
                // ② 必须等 SEND_CONFIRM_SETTLE_MS 让键盘/面板完全落定后重读，框里若又冒出草稿
                //    就是假清空；只有落定后仍空、且输入框节点还在屏幕上，才算真清空。
                // ③ 地面真相兜底：数"含发送文字、且不是输入框"的气泡数量，发送成功后应 +1
                //    （旧气泡如 8:07 那条也算，新发出的会再多一条），单靠框清空也会被遮挡骗过，
                //    气泡计数才是不会撒谎的证据。
                String afterInput = inputText(after);
                boolean boxPresent = after != null && after.contains("可输入");
                boolean looksCleared = beforeInput != null && !beforeInput.isEmpty()
                    && !isPlaceholder(beforeInput)
                    && (afterInput == null || afterInput.isEmpty() || isPlaceholder(afterInput));
                if (looksCleared && boxPresent) {
                  try {
                    Thread.sleep(SEND_CONFIRM_SETTLE_MS);
                  } catch (InterruptedException ignored) {
                  }
                  String after2 = DshaAccessibilityService.uiDumpUnderPanel();
                  String afterInput2 = inputText(after2);
                  boolean boxPresent2 = after2 != null && after2.contains("可输入");
                  boolean stillCleared = after2 != null
                      && (afterInput2 == null || afterInput2.isEmpty() || isPlaceholder(afterInput2));
                  int beforeB = countBubbles(dump, beforeInput);
                  int afterB = countBubbles(after2, beforeInput);
                  boolean newBubble = afterB > beforeB;
                  if ((stillCleared && boxPresent2) || newBubble) {
                    android.util.Log.i("DSHA/Task",
                        p[2] + "点 (" + x + "," + y + ") 生效：落定后输入框仍清空"
                            // D-16 修复（Phase 13）：beforeInput 是从屏幕上读出来的
                            // **用户在输入框里亲手打的字**（inputText() 取自无障碍节点树）。
                            // 原来把它原样写进 logcat —— 那是用户要发出去的消息正文，
                            // 属于"用户可控内容"，跟 D-06 那条"屏幕正文不得进日志"是同一类问题。
                            // 现在只记字数：排障要的是"气泡多了没有"，不是"那条消息说了什么"。
                            + (newBubble
                                ? " 且聊天多出一条新气泡（匹配文字 " + LogFormat.size(beforeInput) + "）"
                                : "")
                            + "，消息已发出");
                    return true;
                  }
                  android.util.Log.i("DSHA/Task",
                      p[2] + "点 (" + x + "," + y + ") 初判清空但落定后框又出现文字且无新气泡，假清空，试下一个点");
                  continue;
                }
                android.util.Log.i("DSHA/Task",
                    p[2] + "点 (" + x + "," + y + ") 后输入框仍有文字/被遮挡，没发出去，试下一个点");
                continue;
              }
              android.util.Log.i("DSHA/Task", p[2] + "点 (" + x + "," + y + ") 生效");
              return true;
            }
            // 各点都无效：交给节点点击兜底，再不行由上层"点了没反应"提示接管
            android.util.Log.i("DSHA/Task", "各候选点都无效，改走节点点击兜底");
            String r2 = DshaAccessibilityService.uiTapText(text);
            return r2 != null && !r2.startsWith("[ERR]");
          }
        case "tap_xy":
          {
            // 参数校验（2026-10-11 审计 D-05）：
            // 模型漏给 x/y 时 optInt 会返回 -1，原样传给 uiTap 就是往屏幕外发手势。
            // 这里先挡住并留下明确日志（uiTap 那边也有一道范围校验，这里是第二道）。
            int tx = a.optInt("x", -1);
            int ty = a.optInt("y", -1);
            if (tx < 0 || ty < 0) {
              android.util.Log.w("DSHA/Task",
                  "tap_xy 参数非法（x=" + tx + " y=" + ty + "），拒绝执行");
              return false;
            }
            String r = DshaAccessibilityService.uiTap(tx, ty);
            return r != null && !r.startsWith("[ERR]");
          }
        case "input":
          {
            String text = a.optString("text", "");
            String r = DshaAccessibilityService.uiInput(text);
            if (r != null && !r.startsWith("[ERR]")) return true;
            // 没有焦点输入框：从 dump 里找"可输入"的元素，点它拿到焦点再输一遍
            int[] box = editableFromDump(dump);
            if (box == null) return false;
            android.util.Log.i("DSHA/Task",
                "没有焦点输入框，先点可输入元素 (" + box[0] + "," + box[1] + ") 再输入");
            String tap = DshaAccessibilityService.uiTap(box[0], box[1]);
            if (tap == null || tap.startsWith("[ERR]")) return false;
            try {
              Thread.sleep(500L);
            } catch (InterruptedException ignored) {
            }
            String r2 = DshaAccessibilityService.uiInput(text);
            return r2 != null && !r2.startsWith("[ERR]");
          }
        case "key":
          {
            String r = DshaAccessibilityService.uiKey(a.optString("name", "back"));
            return r != null && !r.startsWith("[ERR]");
          }
        case "scroll":
          {
            boolean down = !"up".equalsIgnoreCase(a.optString("dir", "down"));
            String r =
                DshaAccessibilityService.uiSwipe(360, down ? 1000 : 600, 360, down ? 600 : 1000, 400);
            return r != null && !r.startsWith("[ERR]");
          }
        case "wait":
          return true;
        default:
          return false;
      }
    } catch (Throwable t) {
      android.util.Log.w("DSHA/Task", "动作执行失败: " + t);
      return false;
    }
  }

  /**
   * 从 dump 里找包含该文字的那一行，抠出它的 {@code 中心=(x,y)}。
   * dump 每行形如 {@code [3] "文字" 中心=(360,1304) 区域=…}。
   */
  /**
   * 一个 tap 目标是不是"输入框"：可输入标记，或占位文字像输入框（消息/说点什么/话题…
   * Telegram 话题机器人的输入框占位是"非话题消息"，不标 editable，只能按关键字认）。
   *
   * <p>用途：输入框点一下只是拿焦点，软键盘是独立的 IME 窗口、不会反映到应用 dump 上，
   * 所以不能用"点了屏幕有没有变化"来判定成功 —— 否则会误判失败、跑去点端点/节点兜底，
   * 把输入流程带歪。这类目标直接点一次即可（和加 tapAndVerify 之前的行为一致）。
   */
  private static boolean isInputLikeTarget(String dump, String text) {
    if (dump == null || text == null || text.isEmpty()) return false;
    for (String line : dump.split("\n")) {
      if (!line.contains(text)) continue;
      if (line.contains("可输入")) return true;
      for (String kw : new String[] {"消息", "说点什么", "写点", "输入", "Send a message", "话题"}) {
        if (line.contains(kw)) return true;
      }
    }
    return false;
  }

  /**
   * 一个 tap 目标是不是"发送类按钮"：发送 / 送出 / Send / 回车 / 发送消息 这类字眼。
   *
   * <p>用途：发送类按钮要认"按钮从屏幕上消失"才算成功（Telegram 发完后把发送键换回
   * 回形针/麦克风）。只是屏幕有变化（键盘联想条、聊天动画、旧气泡）不算 —— 否则会被
   * 键盘噪声误判成"点了没变化→成功"，消息其实还躺在输入框里，模型还会被旧的相同气泡
   * 骗过去说 done（2026-10-09 晚实测：发送键点了没发出去，模型看到 8 小时前的旧"你好"
   * 气泡直接 done）。普通按钮则沿用"屏幕有任意变化即生效"的判定。
   */
  private static boolean isSendLikeTarget(String text) {
    if (text == null) return false;
    for (String kw : new String[] {"发送", "送出", "Send", "回车", "发送消息"}) {
      if (text.contains(kw)) return true;
    }
    return false;
  }

  /**
   * 从 dump 推断软键盘是不是正开着 —— 用来决定"收键盘"那一次 BACK 到底该不该按。
   *
   * <p>判据：面板读屏只读应用窗口。键盘弹出时应用窗口会被压扁，输入框（可输入节点）会从
   * 屏幕最底（bottom≈1552）被顶到中上部（bottom 通常 &lt; 1400）；键盘收起后输入框落回最底。
   * 所以：找到"可输入"节点，看它 bottom 是否明显不在屏幕底部 —— 是则键盘大概率开着。
   *
   * <p>2026-10-09 血泪事实：键盘已经收起时盲按 BACK 会直接退出应用（真机实测两次 BACK 从
   * 聊天页退到列表再到桌面），所以宁可判不出来就不按，也不能误按。
   */
  private static boolean isImeLikelyOpen(String dump) {
    if (dump == null || dump.isEmpty()) return false;
    int screenH = 1600; // 真机 720×1600
    int editableBottom = -1;
    for (String line : dump.split("\n")) {
      if (!line.contains("可输入")) continue;
      java.util.regex.Matcher m =
          java.util.regex.Pattern.compile("区域=(-?\\d+),(-?\\d+),(-?\\d+),(-?\\d+)").matcher(line);
      if (m.find()) {
        try {
          editableBottom = Integer.parseInt(m.group(4));
        } catch (Throwable ignored) {
        }
        break;
      }
    }
    if (editableBottom < 0) return false; // 读不到输入框，不敢乱按
    // 收起时输入框 bottom 贴近屏幕底（≈1552）；开着时被键盘顶上去（一般 <1350）
    return editableBottom < screenH - 250;
  }

  /**
   * 取输入框当前文本：在 dump 里找带「可输入」标记那一行，抠它引号里的文字。
   * 没找到（没有输入框 / 输入框没文字）返回 null。用来判断"发送后输入框有没有清空"。
   */
  private static String inputText(String dump) {
    if (dump == null) return null;
    for (String line : dump.split("\n")) {
      if (!line.contains("可输入")) continue;
      java.util.regex.Matcher m =
          java.util.regex.Pattern.compile("\"([^\"]*)\"").matcher(line);
      if (m.find()) return m.group(1);
    }
    return null;
  }

  /** 一段文字是不是输入框的占位提示（"非话题消息"/"说点什么"…），用来把"空框提示"和"真输了字"区分开。 */
  private static boolean isPlaceholder(String t) {
    if (t == null) return false;
    for (String kw :
        new String[] {"消息", "说点什么", "写点", "输入", "Send a message", "话题", "发消息"}) {
      if (t.contains(kw)) return true;
    }
    return false;
  }

  /**
   * 数 dump 里"含某段文字、且不是输入框"的行数 —— 也就是聊天里出现过该文字的气泡条数。
   * 输入框那一行带「可输入」标记会被排除，所以这里只数真正的消息气泡。
   * 用来做发送成功的地面真相：发出去之后，含该文字的气泡应该 +1（旧的那条也算在内）。
   */
  private static int countBubbles(String dump, String text) {
    if (dump == null || text == null || text.isEmpty()) return 0;
    int n = 0;
    for (String line : dump.split("\n")) {
      if (line.contains(text) && !line.contains("可输入")) n++;
    }
    return n;
  }

  private static int[] centerFromDump(String dump, String text) {
    if (dump == null || text == null || text.isEmpty()) return null;
    for (String line : dump.split("\n")) {
      if (!line.contains(text)) continue;
      java.util.regex.Matcher m =
          java.util.regex.Pattern.compile("中心=\\((\\d+),(\\d+)\\)").matcher(line);
      if (m.find()) {
        try {
          return new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        } catch (Throwable ignored) {
        }
      }
    }
    return null;
  }

  /**
   * 从 dump 里找"能当输入框"的元素中心坐标。
   *
   * <p>两级匹配：先找带「可输入」标记的标准输入框；找不到再退一步 —— Telegram/微信的
   * 输入框经常把占位文字（"非话题消息"、"说点什么…"）挂在 content-desc 上、自己反而不标
   * editable（2026-10-09 真机踩到：小肥鱼是话题机器人，输入框占位是"非话题消息"），
   * 这种只能按"名字像输入框 + 在屏幕下半部"认。
   */
  private static int[] editableFromDump(String dump) {
    if (dump == null) return null;
    int[] bottomHalf = null;
    for (String line : dump.split("\n")) {
      boolean editable = line.contains("可输入");
      boolean looksLikeInput = false;
      if (!editable) {
        for (String kw : new String[] {"消息", "说点什么", "写点", "输入", "Send a message", "话题"}) {
          if (line.contains(kw)) {
            looksLikeInput = true;
            break;
          }
        }
      }
      if (!editable && !looksLikeInput) continue;
      java.util.regex.Matcher m =
          java.util.regex.Pattern.compile("中心=\\((\\d+),(\\d+)\\)").matcher(line);
      if (!m.find()) continue;
      try {
        int[] c = {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        if (editable) return c;
        // "像输入框"的只认屏幕下半部的（输入框永远在下面，上半部的"消息"是聊天记录标题）
        if (bottomHalf == null && c[1] >= 800) bottomHalf = c;
      } catch (Throwable ignored) {
      }
    }
    return bottomHalf;
  }

  private static String describe(String act, JSONObject a) {
    switch (act) {
      case "tap":
        return "点「" + a.optString("text", "?") + "」";
      case "tap_xy":
        return "点坐标(" + a.optInt("x", -1) + "," + a.optInt("y", -1) + ")";
      case "input":
        return "输入「" + a.optString("text", "?") + "」";
      case "key":
        return "按" + a.optString("name", "back") + "键";
      case "scroll":
        return "往" + ("up".equalsIgnoreCase(a.optString("dir", "down")) ? "上" : "下") + "滑";
      case "wait":
        return "等一下";
      case "done":
        return "完成";
      case "fail":
        return "放弃";
      default:
        return "未知动作(" + act + ")";
    }
  }

  // ---------------------------------------------------------------- 模型

  private static final String SYSTEM =
      "你是安卓手机上的 GUI 操作执行器。每轮给你：任务目标、已做过的动作、当前屏幕上可见的元素列表。"
          + "列表每行形如 [序号] \"文字\" 中心=(x,y) 区域=…。"
          + "你只输出一个 JSON 对象，禁止解释、禁止 markdown 代码块。可选动作：\n"
          + "{\"act\":\"tap\",\"text\":\"要点的可见文字\"}\n"
          + "{\"act\":\"tap_xy\",\"x\":123,\"y\":456}\n"
          + "{\"act\":\"input\",\"text\":\"要输入的内容\"}\n"
          + "{\"act\":\"key\",\"name\":\"back\"}\n"
          + "{\"act\":\"scroll\",\"dir\":\"down\"}\n"
          + "{\"act\":\"wait\"}\n"
          + "{\"act\":\"done\",\"note\":\"一句话说完成了什么\"}\n"
          + "{\"act\":\"fail\",\"why\":\"为什么办不成\"}\n"
          + "规则：一次只做一个动作；tap 的 text 必须是屏幕上真实存在的可见文字；"
          + "某个元素按文字点不动（弹窗/自绘按钮）就用 tap_xy 点它的 中心=(x,y)；"
          + "要点输入框才能 input；任务目标完成（消息已发出/结果已出现）立刻 done；"
          + "屏幕没变化就换别的动作，不要原地重复。\n"
          + "找联系人/找内容的标准套路：优先用应用自己的搜索：点搜索框 → input 关键词 →"
          + " 从结果里点目标。列表里能看到目标就直接点，别舍近求远。"
          + "只有既没有搜索框、列表又太长时才 scroll，且 scroll 后要重新看屏幕再决定。\n"
          + "发消息场景：进了聊天页后<b>不要点顶部的聊天标题</b>（那会打开资料页绕远路），"
          + "<b>也别点聊天里的旧消息、表情、文件、或任何像'你好'这样要发的内容</b>——那些只是聊天记录或要写进输入框的文字，"
          + "不是按钮；点它们只会打开表情面板/资料页、把界面搞乱。只操作最底部的输入框和发送按钮。\n"
          + "另外，聊天里若出现'回复 XXX'/'点击此处以回复'这类横幅，那是引用回复框，<b>不是输入框</b>，别点；"
          + "真正的输入框只在屏幕<b>最底部</b>那条带占位文字的框（占位文字可能是'消息'、'非话题消息'、'说点什么'之类）。\n"
          + "如果误点打开了表情/贴纸面板（屏幕中部冒出一堆 emoji），先点底部输入框把它收起，再继续 input，别在面板开着时点别处。\n"
          + "输入框不一定标'可输入'，但一定在最底部。消息内容必须一字不改用任务里给的原话，绝不自己改写或补充。\n"
          + "发消息的硬顺序：① 点底部输入框（拿焦点）→ ② input 要发的内容（哪怕框里好像已有字也再 input 一次确保内容在、焦点拿到）"
          + "→ ③ tap 发送按钮。"
          + "<b>重要防卡死：点一次输入框就够了。点完之后，屏幕元素列表里很可能仍然显示『非话题消息』『消息』这类占位文字——"
          + "这不代表点失败了，只说明输入框是空的、焦点已经拿到。看到占位文字还在就立刻发 input，绝不要再第二次 tap / tap_xy 那个输入框，"
          + "否则会原地空转。</b>"
          + "<b>发送按钮是输入栏里的那个纸飞机/发送图标（无障碍描述就叫「发送」，位于输入栏最底部一行）"
          + "；不要去点输入法键盘上的发送/回车键</b>"
          + "（键盘键坐标不稳、经常点不出去）。<b>发送键具体在输入框左边还是右边由应用决定，"
          + "不要凭左右猜——它就在最底部那条输入栏里、和输入框同一水平线上，用 dump 里它自己的"
          + "区域坐标去点</b>。tap 发送之后必须确认输入框<b>变空了</b>才算真的发出去"
          + "（Telegram 发完会把输入框清空、且聊天里多出一条刚发的消息）；如果输入框还是满的、或聊天没多新消息，"
          + "说明没发出去，别急着 done——代码会自动换候选点重试，你只需再 tap 一次'发送'即可，"
          + "不要因为看到旧聊天记录里相似的消息就提前 done。\n"
          + "机器人/文件型聊天特别提示：聊天顶部若出现'Enable/Disable'这类机器人说明、"
          + "或聊天记录里全是 DOCX/图片/文件，一律<b>忽略</b>，绝不去点那些文件或说明文字"
          + "（点文件会打开别的 App、点说明不会发消息）。始终只看最底部的输入框 + 发送按钮，"
          + "输入框哪怕藏在很长的聊天记录下面也要先 scroll 到底、或直接在屏幕底部区域找它。";

  private static String askAction(
      DshaAccessibilityService host,
      AssistantConfig cfg,
      Task task,
      StringBuilder trace,
      String dump,
      String noChangeHint)
      throws Exception {
    String key;
    if (cfg.chatApiKey == null || cfg.chatApiKey.trim().isEmpty()) {
      CredentialRead read = new ConfigStore(host).readApiKey();
      if (read.state == CredentialRead.State.AVAILABLE) key = read.requireValue();
      else throw new IllegalStateException("没有 API Key，办不了需要联网的后续操作");
    } else {
      key = cfg.chatApiKey;
    }

    StringBuilder user = new StringBuilder();
    user.append("任务：").append(task.goal).append('\n');
    if ("send".equals(task.kind)) {
      user.append("发送内容必须一字不改用这个：").append(task.content).append('\n');
      if (task.target != null && !task.target.isEmpty())
        user.append("收件人/目标叫：").append(task.target).append('\n');
    }
    if (trace.length() > 0) user.append("已做过的动作：\n").append(trace).append('\n');
    if (noChangeHint != null) user.append(noChangeHint).append('\n');
    user.append("当前屏幕内容：\n").append(dump);

    java.util.List<AssistantApiClient.Message> messages = new java.util.ArrayList<>();
    messages.add(new AssistantApiClient.Message("system", SYSTEM));
    messages.add(new AssistantApiClient.Message("user", user.toString()));
    String resp =
        AssistantApiClient.chat(
            cfg.chatEndpoint, cfg.chatModel, key, messages, 800);
    // D-13 修复（Phase 13）：原来把模型完整响应 JSON 直接打进 logcat，
    // 里面可能夹带从屏幕/通知读到的正文。现在只记长度。
    android.util.Log.i("DSHA/Task", LogFormat.modelAction(resp));
    return resp;
  }

  /** 从模型回复里抠 JSON（容忍 ```json 围栏、前后废话）。 */
  private static JSONObject extractJson(String resp) {
    if (resp == null) return null;
    String s = resp.trim();
    int b = s.indexOf('{');
    int e = s.lastIndexOf('}');
    if (b < 0 || e <= b) return null;
    try {
      return new JSONObject(s.substring(b, e + 1));
    } catch (Throwable ignored) {
      return null;
    }
  }
}
