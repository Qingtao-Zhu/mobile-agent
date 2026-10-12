package com.deepseekharness.app.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 目标按钮定位器（2026-10-11 大众化改造核心之二）。
 *
 * <p><b>四级策略</b>（用户要求「按可靠性从高到低选择」）：
 * <ol>
 *   <li><b>无障碍节点定位</b> —— {@link #locate}：在 {@code uiDump} 的节点清单里，
 *       按 viewId / contentDescription / text 特征找。这是主力，快且准。
 *   <li><b>预配置定位规则</b> —— 同样走 {@link #locate}，规则来自
 *       {@link ActionTargetStore}（用户预先配好的、验证过的特征）。
 *       ①② 在本类里其实是同一条路径：「现算特征」和「读配置特征」都只是
 *       {@link ActionTargetStore.Rule} 列表，只是来源不同。
 *   <li><b>AI 辅助识别</b> —— 本类判不了时返回 {@link Result#needsAi}，
 *       由调用方（{@code AssistantTaskRunner}）走"截图 + 视觉模型"那条路。
 *       本类不实现视觉，只负责**明确地说"我认不出来"**，而不是乱猜。
 *   <li><b>用户辅助校准</b> —— 同样通过 {@link Result#needsAi} 之外的状态
 *       （{@link Result#reason}）告诉上层"该请用户确认了"。
 * </ol>
 *
 * <p><b>绝不做什么</b>（用户明确禁止）：
 * <ul>
 *   <li>不返回"猜"的坐标 —— 找不到就是找不到。
 *   <li>不使用任何存下来的绝对坐标 —— 坐标只在当前这屏算出来。
 *   <li>不在置信度不足时硬点 —— 宁可失败让用户校准。
 * </ul>
 */
final class ActionTargetLocator {

  private ActionTargetLocator() {}

  /** 一次定位的结果。 */
  static final class Result {
    /** 命中的节点中心（屏幕坐标）。 */
    int cx;
    int cy;
    /** 命中节点的区域 [l,t,r,b]。 */
    int[] bounds;
    /** 是哪条规则命中的（写进日志，方便用户理解"为什么点这里"）。 */
    String via;
    /**
     * 置信度 0~1。1.0 = viewId 完全匹配且可点击；
     * 0.7 = 描述/文本匹配且可点击；0.4 = 匹配到但不可点击（多半是容器，风险高）。
     */
    float confidence;
    /** 失败原因（成功时为 null）。 */
    String reason;
    /** 是否建议升级到 AI 视觉识别。 */
    boolean needsAi;
    /** 试过哪些规则（失败时给用户看，说明"我都找过什么"）。 */
    final List<String> tried = new ArrayList<>();

    boolean found() {
      return reason == null && bounds != null;
    }
  }

  /** 一个解析出来的节点行。 */
  private static final class Node {
    String label = "";
    String id = "";
    String cd = "";
    boolean clickable;
    boolean editable;
    boolean enabled = true;
    int cx, cy;
    int[] bounds = new int[4];
    /** 区域面积，用于在多个候选里挑"最像按钮"的（通常按钮较小、容器较大）。 */
    long area() {
      return (long) Math.max(0, bounds[2] - bounds[0]) * Math.max(0, bounds[3] - bounds[1]);
    }
  }

  private static final Pattern LINE =
      Pattern.compile("^\\[(\\d+)]\\s*(.*)$");
  private static final Pattern FIRST_QUOTED =
      Pattern.compile("\"([^\"]*)\"");
  private static final Pattern CENTER =
      Pattern.compile("中心=\\((-?\\d+),(-?\\d+)\\)");
  private static final Pattern BOUNDS =
      Pattern.compile("区域=(-?\\d+),(-?\\d+),(-?\\d+),(-?\\d+)");
  private static final Pattern VIEW_ID =
      Pattern.compile("\\bid=(\\S+)");
  private static final Pattern CD =
      Pattern.compile("\\bcd=\"([^\"]*)\"");

  /**
   * 在当前屏幕的 node dump 里找目标。
   *
   * @param dump   {@code DshaAccessibilityService.uiDump()} 的输出
   * @param target 目标（含有序候选规则）
   * @param pkg    当前界面的包名（用于校验"是不是在目标应用里"）；可为 null 跳过校验
   */
  static Result locate(String dump, ActionTargetStore.Target target, String pkg) {
    Result r = new Result();
    if (target == null) {
      r.reason = "目标不存在";
      return r;
    }
    if (dump == null || dump.isEmpty()) {
      r.reason = "读不到屏幕内容（无障碍没连上，或当前界面不可读）";
      r.needsAi = true;
      return r;
    }

    // 包名校验：不在目标应用里就别找了 —— 否则可能在别的地方点到同名按钮
    if (pkg != null && !pkg.isEmpty() && !target.packages.isEmpty()
        && !target.packages.contains(pkg)) {
      r.reason = "当前界面是 " + pkg + "，不是目标应用（"
          + target.appLabel + "）";
      r.tried.add("包名校验");
      return r;
    }

    List<Node> nodes = parse(dump);
    if (nodes.isEmpty()) {
      r.reason = "当前界面没有可读控件（可能是游戏/自绘界面）";
      r.needsAi = true;
      return r;
    }

    if (target.rules.isEmpty()) {
      r.reason = "这个目标还没配置定位规则";
      r.needsAi = true;
      return r;
    }

    // 逐条规则找，命中即返回（规则是有序候选：越靠前越可靠）
    for (ActionTargetStore.Rule rule : target.rules) {
      List<Node> hits = new ArrayList<>();
      for (Node n : nodes) {
        if (ruleMatches(rule, n)) hits.add(n);
      }
      String desc = rule.by + "≈" + rule.value + (rule.exact ? "(完全匹配)" : "(包含)");
      if (hits.isEmpty()) {
        r.tried.add(desc + " → 无命中");
        continue;
      }
      Node best = pickBest(hits);
      // 命中但不可点击：多半是包着按钮的容器。给低置信度，让上层决定要不要升级。
      float conf = best.clickable ? (isIdRule(rule) ? 1.0f : 0.7f) : 0.4f;
      r.tried.add(desc + " → 命中（置信度 " + String.format(java.util.Locale.US, "%.1f", conf) + "）");
      r.via = desc;
      r.confidence = conf;

      if (!best.clickable && !best.editable) {
        // 找到了、但既不可点也不可输入 —— 多半只是文字标签或容器。
        //
        // **关键：不把坐标交出去。** found() 是"可以安全对这个位置下手"的意思，
        // 这里把 cx/cy/bounds 留空，上层就不可能在它上面贸然点击。
        // 只报告"看见了但不可操作"，并建议走 AI 复核或请用户校准。
        // （2026-10-11 单元测试抓到这类语义歧义：原来留了坐标却又让 found()=false，
        //   自相矛盾；现在改成"要么给可用坐标并说找到了，要么一个坐标都不给"。）
        r.cx = 0;
        r.cy = 0;
        r.bounds = null;
        r.reason = "找到「" + rule.value + "」但它不可点击（可能只是文字标签或容器）";
        r.needsAi = true;
        return r;
      }

      r.cx = best.cx;
      r.cy = best.cy;
      r.bounds = best.bounds;
      return r;
    }

    // 一条都没中 → 交给上层决定走 AI 还是请用户校准
    r.reason = "在这个界面没找到「" + target.name + "」"
        + (r.tried.isEmpty() ? "" : "（试过 " + r.tried.size() + " 条规则）");
    r.needsAi = true;
    return r;
  }

  private static boolean isIdRule(ActionTargetStore.Rule rule) {
    return "viewId".equals(rule.by);
  }

  private static boolean ruleMatches(ActionTargetStore.Rule rule, Node n) {
    if (rule == null || n == null) return false;
    switch (rule.by) {
      case "viewId":
        return rule.matches(n.id);
      case "contentDescription":
        // dump 里 cd= 只在"与 label 不同"时才输出，所以两处都要看
        return rule.matches(n.cd) || rule.matches(n.label);
      case "text":
        return rule.matches(n.label);
      default:
        return false;
    }
  }

  /**
   * 多个候选里挑最像"那个按钮"的一个。
   *
   * <p>排序：可点击 > 可输入 > 面积小（按钮通常比它外面的容器小）。
   * 面积这一条很关键：同一个 viewId 前缀会匹配到容器和按钮两层，
   * 容器面积是按钮的十几倍，点容器中心往往落在空白处。
   */
  private static Node pickBest(List<Node> hits) {
    Node best = null;
    for (Node n : hits) {
      if (best == null) {
        best = n;
        continue;
      }
      int scoreN = score(n);
      int scoreB = score(best);
      if (scoreN > scoreB) best = n;
    }
    return best;
  }

  private static int score(Node n) {
    int s = 0;
    if (n.clickable) s += 1000;
    if (n.editable) s += 500;
    if (n.enabled) s += 100;
    // 面积越小越像按钮：给一个反向分数（上限 200，避免极端值主导）
    long a = n.area();
    s += (int) Math.max(0, 200 - Math.min(200, a / 5000));
    return s;
  }

  /** 解析 uiDump 输出。逐行解析，格式不符的行直接跳过（dump 里还有"窗口应用:"这类行）。 */
  private static List<Node> parse(String dump) {
    List<Node> out = new ArrayList<>();
    for (String raw : dump.split("\n")) {
      String line = raw.trim();
      if (line.isEmpty() || !line.startsWith("[")) continue;
      Matcher m = LINE.matcher(line);
      if (!m.find()) continue;
      String body = m.group(2);

      Node n = new Node();
      Matcher q = FIRST_QUOTED.matcher(body);
      if (q.find()) n.label = q.group(1);
      Matcher c = CENTER.matcher(body);
      if (c.find()) {
        try {
          n.cx = Integer.parseInt(c.group(1));
          n.cy = Integer.parseInt(c.group(2));
        } catch (NumberFormatException ignored) {
        }
      } else {
        continue; // 没有坐标的节点没法点，跳过
      }
      Matcher b = BOUNDS.matcher(body);
      if (b.find()) {
        try {
          for (int i = 0; i < 4; i++) n.bounds[i] = Integer.parseInt(b.group(i + 1));
        } catch (NumberFormatException ignored) {
        }
      } else {
        // 区域解析不出来时**不能**让 bounds 停在 [0,0,0,0]：
        // 那样 area() 恒为 0，「取面积最小的候选」这条排序就整体失效，
        // 而且返回的 bounds 是无意义的 [0,0,0,0]，上层还会当成找到了。
        // （2026-10-11 单元测试抓到：测试数据写成带括号的 "区域=(0,0),(320,800)" 时，
        //   真实 dump 格式其实是 "区域=0,0,320,800"，解析全空 → 三个用例失败。）
        // 退而求其次：用中心点构造一个 1×1 的退化区域，保证面积非 0、坐标仍可用。
        n.bounds[0] = n.cx - 1;
        n.bounds[1] = n.cy - 1;
        n.bounds[2] = n.cx + 1;
        n.bounds[3] = n.cy + 1;
      }
      Matcher v = VIEW_ID.matcher(body);
      if (v.find()) n.id = v.group(1);
      Matcher d = CD.matcher(body);
      if (d.find()) n.cd = d.group(1);
      n.clickable = body.contains("可点击");
      n.editable = body.contains("可输入");
      n.enabled = !body.contains("不可用");
      out.add(n);
    }
    return out;
  }

  /**
   * 从一段界面里"学习"出一组候选规则（用户校准流程会用到）。
   *
   * <p>把用户在屏幕上点中的那个节点，反向提取出可复用的特征：viewId 最优先，
   * 其次 contentDescription，最后文本。**不生成坐标规则** —— 这是底线。
   */
  static List<ActionTargetStore.Rule> learnRules(String dump, int tapX, int tapY) {
    List<ActionTargetStore.Rule> rules = new ArrayList<>();
    Node hit = null;
    long bestArea = Long.MAX_VALUE;
    for (Node n : parse(dump)) {
      if (tapX >= n.bounds[0] && tapX <= n.bounds[2]
          && tapY >= n.bounds[1] && tapY <= n.bounds[3]) {
        // 命中多个就取最小的那个（最贴近用户实际点的小控件）
        if (n.area() < bestArea) {
          bestArea = n.area();
          hit = n;
        }
      }
    }
    if (hit == null) return rules;
    if (!hit.id.isEmpty()) rules.add(new ActionTargetStore.Rule("viewId", hit.id, false));
    if (!hit.cd.isEmpty()) rules.add(new ActionTargetStore.Rule("contentDescription", hit.cd, true));
    if (!hit.label.isEmpty()) rules.add(new ActionTargetStore.Rule("text", hit.label, true));
    return rules;
  }
}
