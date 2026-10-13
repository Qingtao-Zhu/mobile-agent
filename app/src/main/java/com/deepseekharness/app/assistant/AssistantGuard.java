package com.deepseekharness.app.assistant;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.Locale;

/**
 * 助手面板的敏感应用防护（用户 2026-10-07 立规）。
 *
 * <p><b>规矩：</b>微信、支付宝等支付类应用属于敏感目录，不可以在它们里面进行操作。
 * 工作台那条路（/app/ui/*）本来就有「敏感界面逐次确认」闸门
 * （{@code HttpShellService.uiAuthorized} + {@code SensitiveAppPolicy}），
 * V2 助手面板是后加的入口，**必须接同一道防线**。命中时 ——
 * <ul>
 *   <li>不读屏：屏幕文字一行都不进内存、不给模型；</li>
 *   <li>不代操作：面板本身不注入点击/手势，读屏一断，操作链路就不存在；</li>
 *   <li>摘要行显示保护提示，解释为什么这次读不到内容。</li>
 * </ul>
 * 提问 / 语音输入仍然可用：那是用户自己对模型说话，不碰敏感界面。
 *
 * <p><b>白名单优先（用户 2026-10-07 拍板）：</b>微信不在受保护之列 —— 用户要求助手能读微信、
 * 能操作微信，所以 {@code com.tencent.mm} 直接放行；支付宝与各家银行 / 钱包 / 密码管理类仍然拦。
 * 需要再放行别的应用时，在两个地方加包名（都不用重编 APK 的方式见下）：
 * <pre>
 * /sdcard/dsh/assistant-voice.json  →  "allowedPackages": ["com.xxx"]
 * </pre>
 * 白名单与黑名单同时命中时，**白名单赢**（用户明确点名的优先，宁可放行也不误拦）。
 *
 * <p>名单口径 = {@code SensitiveAppPolicy} 的「涉钱、涉密」子集，减去白名单那几个：
 * 支付 / 银行 / 钱包 / 密码管理 / 券商，**不含** settings / systemui / telephony /
 * messaging / sms —— 面板是「看着屏幕陪你聊」的入口，用户在系统设置里问「这页是干什么的」
 * 是正常用法（上一轮就在设置上验证过读屏）；真正要守的是钱和密码。
 * 口径调整要同时改工作台与这里，保持一致。
 */
final class AssistantGuard {

  private AssistantGuard() {}

  /** 用户点名放行：白名单优先于黑名单。 */
  private static final String[] ALLOWED = {
    "com.tencent.mm", // 微信 —— 用户 2026-10-07 拍板：不需要防护，要能读能操作
  };

  /** 涉钱涉密关键词（子串匹配，与 SensitiveAppPolicy 同一套写法；小写比较）。 */
  private static final String[] MONEY_OR_SECRET = {
    "alipay",        // 支付宝
    "tencent.mm",    // 微信（含应用分身）
    "unionpay",      // 云闪付
    "jdpay",         // 京东支付
    "wallet",        // 各家钱包（华为钱包等）
    "paypal",
    "bank",          // 各银行通用
    "icbc", "ccb", "abchina", "bankofchina", "cmbchina", "bankcomm",
    "psbc", "cebbank", "cmbc", "spdb", "citic", "hxb",
    "keepass", "bitwarden", "lastpass", "1password", "authenticator",
    "securities", "broker", "trading",  // 券商 / 交易类
  };

  /** 该包名是否属于敏感应用（支付/银行/钱包/密码管理/券商）。 */
  static boolean isProtected(String pkg) {
    return isProtected(pkg, null);
  }

  /**
   * 白名单优先的判定。
   *
   * @param allowed 外部追加的白名单（{@code AssistantConfig.allowedPackages}，可为 null）
   */
  static boolean isProtected(String pkg, java.util.List<String> allowed) {
    if (pkg == null || pkg.isEmpty()) return false;
    String p = pkg.toLowerCase(Locale.ROOT);
    // ★ GD-01 修复（Phase 14）：白名单改为**精确匹配**。
    //
    // 原来是 matchAny()（contains 子串），导致任何"名字里含 com.tencent.mm"的包
    // 都被一票放行 —— 而白名单优先于黑名单，于是连 com.tencent.mm.bank
    // 这种含 bank 关键词的支付类包名也一并被放行。这是"漏拦"，方向是危险的。
    //
    // 为什么可以改精确：Android 原生多用户/工作资料机制下，应用分身
    // **包名不变**（只有 userId 不同）；而部分厂商 ROM 的克隆体会用
    // com.original.app.clone1 这类扩展包名 —— 这种场景本来就不该由
    // "模糊匹配"自动放行，而应由用户在 assistant-voice.json 的
    // allowedPackages 里**显式**加一行（外部白名单本来就是精确匹配）。
    // 自动放行一个用户没点名过的包，比要求用户多点一次配置危险得多。
    if (matchExact(p, ALLOWED)) return false; // 白名单一票放行
    if (allowed != null) {
      for (String rule : allowed) {
        if (rule != null && !rule.trim().isEmpty() && p.equals(rule.trim().toLowerCase(Locale.ROOT)))
          return false;
      }
    }
    return matchAny(p, MONEY_OR_SECRET);
  }

  /**
   * 精确匹配（大小写不敏感）。用于**白名单**。
   *
   * <p>白名单是"用户点名放行这一个应用"，语义就是精确到包名。
   * 与外部白名单 {@code AssistantConfig.allowedPackages} 的匹配口径一致
   * （那边一直是 {@code equals}，Phase 14 之前两边并不一致）。
   */
  private static boolean matchExact(String pkgLower, String[] rules) {
    for (String key : rules) {
      if (pkgLower.equals(key.toLowerCase(Locale.ROOT))) return true;
    }
    return false;
  }

  /**
   * 子串匹配。仅用于**黑名单**。
   *
   * <p>黑名单的方向是"宁可多拦、不可漏拦"：{@code bank} 命中 {@code com.somebank.app}
   * 这种名字相近的第三方包属于误伤（多拦），不危险；反过来若漏掉一个真银行，
   * 助手就会在支付界面里读屏代操作。
   *
   * <p><b>为什么白名单和黑名单口径不同</b>（Phase 14 明确写下来，免得以后有人
   * "顺手统一"）：模糊匹配对黑名单是保守的，对白名单是危险的。
   */
  private static boolean matchAny(String pkgLower, String[] rules) {
    for (String key : rules) {
      if (pkgLower.contains(key)) return true;
    }
    return false;
  }

  /** 命中时给摘要行用的提示文案（pkg 换成应用名，取不到就用包名）。 */
  static String notice(Context ctx, String pkg) {
    String name = pkg;
    try {
      PackageManager pm = ctx.getPackageManager();
      ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
      CharSequence label = pm.getApplicationLabel(info);
      if (label != null && label.length() > 0) name = label.toString();
    } catch (Throwable ignored) {
    }
    return "🔒 "
        + name
        + " 属于支付/敏感类应用，出于隐私保护，助手不读取、也不操作这个界面。想问别的直接输入。";
  }
}
