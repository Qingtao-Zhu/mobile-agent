package com.deepseekharness.app.assistant;

import java.util.List;

/**
 * 敏感应用 / 系统设置 / 未知身份 的守卫判定（2026-10-11 离线安全验证轮抽出）。
 *
 * <p><b>为什么抽出来</b>：这段判定原来写在 {@code AssistantTaskRunner.runLoop()} 里 ——
 * 那是 Android 类，JVM 测不到。而用户要求"用可控测试 Activity 验证守卫、
 * 断言所有拒绝分支都发生在手势派发之前"。判定抽成纯函数之后：
 * <ul>
 *   <li>测试可以直接喂各种包名，断言裁决结果；
 *   <li>生产代码调用的是**同一个函数**（不是影子逻辑）。
 * </ul>
 *
 * <p><b>四种裁决</b>：
 * <pre>
 *   ALLOW                  普通应用，继续
 *   DENY_UNKNOWN_IDENTITY  身份读不出来 → 停手（D-07：这里原本是"放行"，是缺口）
 *   DENY_PROTECTED         支付/银行/钱包/密码/券商 → 不读不点
 *   DENY_SETTINGS          系统设置 → 不主动操作（改设置影响面大且不可逆）
 * </pre>
 *
 * <p><b>顺序很重要</b>：身份未知放在最前面。旧代码把"非空"写在条件里，
 * 导致"空包名"从所有分支里溜过去（既不是 protected 也不是 settings）→ 放行。
 * 现在它是**第一个**被判定的情形，且结果就是停手。
 *
 * <p><b>能力边界</b>：包名是"当前前台窗口"的包名，不从进程列表推断。
 * 分屏 / 悬浮窗场景下它指的是**当前活跃窗口**，不一定是用户以为的那个应用。
 * 这一层不做更强的承诺。
 */
final class GuardDecision {

  private GuardDecision() {}

  /** 裁决结果。 */
  enum Verdict {
    /** 允许继续。 */
    ALLOW,
    /** 身份读不出来 → 停手。 */
    DENY_UNKNOWN_IDENTITY,
    /** 受保护应用（支付/银行/钱包/密码/券商）→ 不读不点。 */
    DENY_PROTECTED,
    /** 系统设置 → 不主动操作。 */
    DENY_SETTINGS
  }

  /** 系统设置类包名前缀。 */
  static final String SETTINGS_PREFIX = "com.android.settings";

  /**
   * 从 uiDump 的首行提取当前前台包名。
   *
   * <p>dump 首行形如 {@code 窗口应用: com.android.settings}。
   * 提取失败（没有该行 / 冒号后为空）返回 {@code null} —— 调用方据此判定"身份未知"。
   *
   * <p>纯函数，不碰 Android。
   */
  static String extractPackage(String dump) {
    if (dump == null || dump.isEmpty()) return null;
    int nl = dump.indexOf('\n');
    String firstLine = nl > 0 ? dump.substring(0, nl) : dump;
    int ci = firstLine.indexOf("窗口应用: ");
    if (ci < 0) return null;
    String pkg = firstLine.substring(ci + "窗口应用: ".length()).trim();
    return pkg.isEmpty() ? null : pkg;
  }

  /**
   * 守卫裁决（纯函数）。
   *
   * @param curPkg  当前前台包名（{@link #extractPackage} 的结果，可为 null）
   * @param allowed 外部追加的白名单（{@code AssistantConfig.allowedPackages}，可为 null）
   */
  static Verdict decide(String curPkg, List<String> allowed) {
    // ① 身份未知 → 停手（必须最先判，见类注释）
    //
    // D-09（2026-10-11 由 GuardDecisionTest 抓到）：原来只判 `isEmpty()`，
    // 而 `"   "`（全空白）的 isEmpty() 是 **false** —— 于是它既不算未知身份、
    // 也匹配不到任何敏感规则、也不是系统设置，**一路溜到 ALLOW**。
    // 单元测试喂了空白包名才发现。现在用 trim() 判。
    if (curPkg == null || curPkg.trim().isEmpty()) {
      return Verdict.DENY_UNKNOWN_IDENTITY;
    }
    // ② 敏感应用 → 不读不点
    if (AssistantGuard.isProtected(curPkg, allowed)) {
      return Verdict.DENY_PROTECTED;
    }
    // ③ 系统设置 → 不主动操作
    //
    // D-08（同轮由测试抓到）：原来用裸 startsWith(PREFIX)，
    // 于是 `com.android.settingsx`（名字相近的第三方包）也被当成系统设置。
    // 误判方向是"多拦"，不算危险，但不精确 —— 改成"完全相等"或"后面紧跟一个点"。
    if (isSystemSettings(curPkg)) {
      return Verdict.DENY_SETTINGS;
    }
    return Verdict.ALLOW;
  }

  /**
   * 是否属于系统设置包。
   *
   * <p>必须是 {@code com.android.settings} 本身，或它下面的子包/子组件
   * （{@code com.android.settings.xxx}）。**不用裸前缀匹配** ——
   * 那会把 {@code com.android.settingsx} 这种名字相近的包一起误伤（D-08）。
   */
  static boolean isSystemSettings(String pkg) {
    if (pkg == null) return false;
    return pkg.equals(SETTINGS_PREFIX) || pkg.startsWith(SETTINGS_PREFIX + ".");
  }

  /** 给日志用的拒绝原因（不包含屏幕内容）。 */
  static String logReason(Verdict v, String curPkg) {
    switch (v) {
      case DENY_UNKNOWN_IDENTITY:
        return "读不到当前应用身份（dump 首行无「窗口应用」），按安全策略停手";
      case DENY_PROTECTED:
        return "落在受保护应用 " + curPkg + "，按规矩不读不点，停手";
      case DENY_SETTINGS:
        return "落在系统设置 " + curPkg + "，按安全策略不主动操作";
      default:
        return "允许继续";
    }
  }

  /** 给面板/用户看的拒绝文案。 */
  static String userMessage(Verdict v, String curPkg) {
    switch (v) {
      case DENY_UNKNOWN_IDENTITY:
        return "⚠ 读不到当前应用的身份，按安全策略停手 —— 不做无法确认身份的操作。";
      case DENY_PROTECTED:
        return "⚠ 当前界面是受保护应用（" + curPkg + "），按规矩我不读也不点它。";
      case DENY_SETTINGS:
        return "⚠ 当前是系统设置界面。改系统设置影响面大且部分不可逆，"
            + "我不主动点它 —— 需要的话请手动改，或告诉我具体改哪一项我们再商量。";
      default:
        return null; // ALLOW 不提示
    }
  }

  /** 是否应当停手。 */
  static boolean mustStop(Verdict v) {
    return v != Verdict.ALLOW;
  }
}
