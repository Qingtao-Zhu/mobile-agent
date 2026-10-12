package com.deepseekharness.app.assistant;

import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.R;

/**
 * 悬浮球（2026-10-08 起，2026-10-09 加静置半隐 + 删除区）。
 *
 * <p><b>它是什么</b>：一颗贴着左边或右边的小球，常驻在桌面任何应用之上 —— 点一下叫助手，
 * 拖一拖挪位置（挪过的位置 X/Y 都记下来，下次还在那儿）。
 *
 * <p><b>为什么要它</b>：已有的唤醒入口（通知栏「待命」、桌面快捷方式、长按电源键）都在抽屉或桌面上，
 * 很"远"。悬浮球是唯一一个**你正在用某个 App 时抬手就能碰到**的入口
 * （小米悬浮球 / OPPO 智能侧边栏那一路子）。
 *
 * <p><b>为什么用 TYPE_ACCESSIBILITY_OVERLAY</b>：本项目无障碍服务已经授权在手上，
 * 同一把钥匙开这扇门，不用再申请"显示在其他应用上层"。
 *
 * <p><b>生命周期</b>：挂在助手面板的闭环上 —— 面板亮起时它撤掉（别两层浮层叠着），
 * 面板收干净后它回来（{@link AssistantPanel} 的 settle 里调 {@link #ensure}）。
 *
 * <p><b>2026-10-09 新增两条（用户原话）</b>：
 * <ol>
 *   <li><b>静置半隐</b>：停靠在一侧后静置 2 秒，向内平移半个身位，只露出半个圆；碰一下恢复。
 *   <li><b>拖动删除</b>：拖动时屏幕中下方浮出一个删除区，球拖进去松手即消失。
 * </ol>
 */
public final class DshaPebbleView {

  private static final String TAG = "DSHA/Pebble";

  private static volatile DshaPebbleView cur;
  private static final String PREF = "dsha_pebble";
  private static final String KEY_X = "x";
  private static final String KEY_Y = "y";
  private static final String KEY_HIDDEN = "hidden";

  /** 停靠在边上后，静置多久自动半隐（用户指定 2 秒左右）。 */
  private static final long DOCK_DELAY_MS = 2000L;

  private Handler ui;
  private WindowManager wm;
  private View ball;
  private WindowManager.LayoutParams lp;
  private float startX;
  private float startY;
  private int startLpX;
  private int startLpY;
  private boolean dragged;
  private long downAt;

  // ---- 静置半隐 ----
  private boolean docked;
  private boolean dragging;
  private Runnable dockTask;
  private int ballSize;

  // ---- 删除区 ----
  private View trash;
  private WindowManager.LayoutParams trashLp;
  private boolean trashHot;

  private DshaPebbleView() {}

  /** 挂球。面板亮着的时候外面会先 {@link #dismiss()}，别跟面板抢屏幕。 */
  public static void ensure(DshaAccessibilityService host) {
    android.util.Log.i(TAG, "ensure: host=" + (host != null) + " cur=" + (cur != null));
    if (host == null) return;
    // 用户把它拖进删除区删掉了就别再冒出来，不然"删除"等于没删
    if (isHidden(host)) {
      android.util.Log.i(TAG, "ensure: 悬浮球已被用户删除，不再挂出（用『把悬浮球弄回来』恢复）");
      return;
    }
    if (cur != null) {
      cur.restore(host);
      return;
    }
    cur = new DshaPebbleView();
    cur.ui = new Handler(Looper.getMainLooper());
    cur.ui.post(() -> cur.build(host));
  }

  public static void dismiss() {
    final DshaPebbleView p = cur;
    if (p == null) return;
    cur = null;
    p.ui.post(p::teardown);
  }

  /** 是否被删掉了。 */
  public static boolean isHidden(Context ctx) {
    try {
      return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_HIDDEN, false);
    } catch (Throwable ignored) {
      return false;
    }
  }

  /**
   * 把删掉的悬浮球找回来（离线命令「把悬浮球弄回来 / 恢复悬浮球」用）。
   *
   * <p><b>为什么必须留这条路</b>：删除区是"碰到就消失"，如果没有恢复入口，用户一次性误操作
   * 就把这个唯一随时可及入口弄丢了，只能重装。所以它既是功能也是后悔药。
   */
  public static boolean revive(Context ctx) {
    try {
      ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_HIDDEN, false).apply();
    } catch (Throwable ignored) {
    }
    if (ctx instanceof DshaAccessibilityService) {
      // 面板正亮着就别急着挂：球的窗口栈和面板叠在一起，球会浮在面板卡片上看着像残影。
      // 面板一收，AssistantPanel.settle() 自己会再调一次 ensure()，那时挂才合适。
      if (AssistantPanel.visible()) {
        android.util.Log.i(TAG, "revive: 面板还亮着，等它收干净再挂球");
        return true;
      }
      ensure((DshaAccessibilityService) ctx);
      return true;
    }
    return false;
  }

  /**
   * 用一句话把悬浮球收起来（「把悬浮球关掉 / 别显示悬浮球」）。
   *
   * <p>跟拖进删除区走的是同一份 {@code KEY_HIDDEN} 开关，所以两条路互斥又互通：
   * 不管怎么关的，都是同一句「把悬浮球弄回来」把它请回来。
   */
  public static void banish(Context ctx) {
    try {
      ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_HIDDEN, true).apply();
    } catch (Throwable ignored) {
    }
    dismiss();
  }

  /** 面板亮着后重新挂上（用户可能把面板收了又收）。 */
  private void restore(DshaAccessibilityService host) {
    if (ball == null) ui.post(() -> build(host));
  }

  private void build(DshaAccessibilityService host) {
    if (host == null || ball != null) return;
    try {
      Context ctx = host;
      wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
      if (wm == null) return;
      // 球别贴着屏幕最右边：EMUI 右边缘是侧边手势/防误触带，贴太近点它就收不到 touch
      // （2026-10-08 真机：贴 10dp 时 adb input tap 打在球心都没反应，挪到 22dp 就通了）
      int size = dp(ctx, 56);
      ballSize = size;
      FrameLayout root = new FrameLayout(ctx);
      root.setLayoutParams(new FrameLayout.LayoutParams(size, size));
      root.setPadding(0, 0, 0, 0);
      ImageView dot = new ImageView(ctx);
      dot.setImageResource(R.drawable.dsha_pebble);
      dot.setLayoutParams(new FrameLayout.LayoutParams(size, size));
      dot.setScaleType(ImageView.ScaleType.FIT_CENTER);
      root.addView(dot);
      // 球心放 DeepSeek 那头黑白小鲸鱼（用户指定）。原来是个纯色圆点+外圈，
      // 在深色 App 上和背景糊成一片，看不出这是个"能按的东西"。
      int whale = dp(ctx, 34);
      ImageView core = new ImageView(ctx);
      core.setImageResource(R.drawable.dsha_pebble_whale);
      core.setLayoutParams(new FrameLayout.LayoutParams(whale, whale, Gravity.CENTER));
      core.setScaleType(ImageView.ScaleType.FIT_CENTER);
      root.addView(core);

      loadSaved(host);
      lp = new WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
          WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
          PixelFormat.TRANSLUCENT);
      // 用绝对定位（左上角原点），拖动时直接改 lp.x/lp.y，不再被重力钉死在某条边
      lp.gravity = Gravity.TOP | Gravity.START;
      lp.width = size;
      lp.height = size;
      int maxX0 = screenW(ctx) - size;
      int maxY0 = screenH(ctx) - size;
      // 上次记得的位置优先；没记过（-1）才用默认值兜底。
      // 注意别用 <=0 判"没存过"：球被拖到最顶时 savedY 就是 0，那样会被误判成没存过而弹回默认位。
      lp.x = savedX >= 0 ? Math.min(savedX, maxX0) : maxX0 - dp(ctx, 22);
      lp.y = savedY >= 0 ? Math.min(savedY, maxY0) : dp(ctx, 140);
      lp.alpha = 0.92f;

      root.setOnTouchListener(
          (v, e) -> {
            int act = e.getActionMasked();
            if (act == MotionEvent.ACTION_DOWN) {
              // 碰一下先把球从边里唤出来（还在半隐状态的话），再接着往下处理
              cancelDock();
              if (docked) undock();
              dragging = true;
              startX = e.getRawX();
              startY = e.getRawY();
              startLpX = lp.x;
              startLpY = lp.y;
              downAt = System.currentTimeMillis();
              dragged = false;
              return true;
            }
            if (act == MotionEvent.ACTION_MOVE) {
              float dx = e.getRawX() - startX;
              float dy = e.getRawY() - startY;
              if (Math.abs(dx) > 8 || Math.abs(dy) > 8) dragged = true;
              if (dragged) {
                // 拖动一开始就把删除区亮出来，别等到已经拖半天才出现
                if (trash == null) showTrash(ctx);
                // 全方向跟手：以按下瞬间记录的窗口位置为基准，加上手指位移
                int maxX = screenW(ctx) - lp.width;
                int maxY = screenH(ctx) - lp.height;
                lp.x = Math.max(0, Math.min(maxX, startLpX + (int) dx));
                lp.y = Math.max(0, Math.min(maxY, startLpY + (int) dy));
                wm.updateViewLayout(root, lp);
                // 命中判定用**坐标**算（=x+size/2, y+size/2），不依赖窗口触摸：
                // 删除区窗口是 NOT_TOUCHABLE 的，自己不收事件
                setTrashHot(overTrash(ctx, lp.x + ballSize / 2, lp.y + ballSize / 2));
              }
              return true;
            }
            if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
              dragging = false;
              boolean tap = !dragged && (System.currentTimeMillis() - downAt) < 600L;
              boolean drop = false;
              if (dragged) {
                drop = trashHot && overTrash(ctx, lp.x + ballSize / 2, lp.y + ballSize / 2);
                hideTrash();
              }
              int maxY = screenH(ctx) - lp.height;
              if (lp.y < 0) lp.y = 0;
              if (lp.y > maxY) lp.y = maxY;
              if (drop) {
                android.util.Log.i(TAG, "松手落在删除区 -> 隐藏悬浮球");
                doDelete(ctx);
                return true;
              }
              wm.updateViewLayout(root, lp);
              if (tap) {
                wake(host);
              } else {
                // 拖完吸到最近的左/右边（像侧边栏），并把位置记下来
                snapToEdge(host);
              }
              // 停稳了才开始算 2 秒
              scheduleDock();
              return true;
            }
            return true;
          });

      ball = root;
      wm.addView(ball, lp);
      android.util.Log.i(TAG, "球挂上了 x=" + lp.x + " y=" + lp.y + " size=" + size);
      // 一挂上来也按"已经放在边上"处理：静置 2 秒后同样半隐
      scheduleDock();
    } catch (Throwable t) {
      android.util.Log.w("DSHA/Pebble", "悬浮球没挂上: " + t);
      ball = null;
    }
  }

  // ============================== 静置半隐 ==============================

  /**
   * 贴边静置一段时间后"半隐"：整体向内平移半个身位，只露出半个圆（2026-10-09 用户原话）。
   *
   * <p><b>为什么改 translationX 而不是缩小</b>：上一版做的是"贴边 scale 0.5"（整颗球变小），
   * 用户要的是"只露出一半的圆" —— 那是<b>被屏幕边缘切掉一半</b>的观感，
   * 得让球的一部分挪到窗口/屏幕之外。窗口宽度就是球的尺寸，往里（往屏幕外）平移半个身位，
   * 窗口天然会把超出部分裁掉，剩下的正好是半个圆。
   *
   * <p><b>为什么能碰回来</b>：View 的平移会被计入触摸命中测试，剩下露在外面那半个
   * 圆正好还在窗口范围内（且与屏幕边缘重合），所以点得到。
   */
  private void dockHalf() {
    if (ball == null || dragging || docked) return;
    final int maxX = screenW(ball.getContext()) - lp.width;
    // 球偏左就往左藏，偏右就往右藏 —— 藏的方向由当前位置决定
    final boolean toLeft = lp.x <= maxX / 2;
    final float tx = (toLeft ? -1f : 1f) * (ballSize / 2f);
    docked = true;
    ball.animate().cancel();
    ball.animate()
        .translationX(tx)
        .setDuration(220L)
        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
        .start();
    android.util.Log.i(TAG, "静置半隐: " + (toLeft ? "左边" : "右边") + " translationX=" + tx);
  }

  /** 碰一下 / 开始拖，把藏起来的球请回来。 */
  private void undock() {
    if (ball == null || !docked) return;
    docked = false;
    ball.animate().cancel();
    ball.animate()
        .translationX(0f)
        .setDuration(180L)
        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
        .start();
  }

  private void scheduleDock() {
    cancelDock();
    dockTask =
        () -> {
          dockTask = null;
          dockHalf();
        };
    ui.postDelayed(dockTask, DOCK_DELAY_MS);
  }

  private void cancelDock() {
    if (dockTask != null) {
      ui.removeCallbacks(dockTask);
      dockTask = null;
    }
  }

  // ============================== 删除区 ==============================

  /** 删除区圆心的屏幕坐标：横向居中、纵向偏下（用户说的"中间偏下方"）。 */
  private static int trashCx(Context ctx) {
    return screenW(ctx) / 2;
  }

  private static int trashCy(Context ctx) {
    return (int) (screenH(ctx) * 0.72f);
  }

  /**
   * 亮出删除区。
   *
   * <p><b>两个必须注意的点</b>：
   * <ol>
   *   <li><b>窗口必须小</b>（这里 132dp），绝不能用全屏窗口 —— EMUI 上全屏窗口只要出现
   *   非零 alpha 的像素，HWC 就会把下层整个盖成纯黑（报告 §5.6）。
   *   <li><b>必须 FLAG_NOT_TOUCHABLE</b>：删除区是拖动过程中后加的同类型浮窗，
   *   窗口栈在球之上；它一旦能收触摸，手指划过这里会把 MOVE 事件抢走、拖动当场断开。
   *   它只负责"看"，命中判定全靠 {@link #overTrash} 的坐标计算。
   * </ol>
   */
  private void showTrash(Context ctx) {
    if (trash != null || wm == null) return;
    try {
      int zone = dp(ctx, 132);
      FrameLayout wrap = new FrameLayout(ctx);
      wrap.setBackgroundResource(R.drawable.dsha_trash_bg);
      ImageView icon = new ImageView(ctx);
      icon.setImageResource(R.drawable.dsha_ic_trash);
      icon.setLayoutParams(new FrameLayout.LayoutParams(dp(ctx, 28), dp(ctx, 28), Gravity.CENTER));
      wrap.addView(icon);

      WindowManager.LayoutParams tl =
          new WindowManager.LayoutParams(
              zone,
              zone,
              WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
              WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                  | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                  | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
              PixelFormat.TRANSLUCENT);
      tl.gravity = Gravity.TOP | Gravity.START;
      tl.x = trashCx(ctx) - zone / 2;
      tl.y = trashCy(ctx) - zone / 2;
      // 注意：入场淡入必须用 View 的 setAlpha(0) 起步。
      // 这里踩过坑：把 WindowManager.LayoutParams.alpha 设成 0 且从没改回 1 ——
      // 窗口级 alpha 是合成阶段的乘数，0 = 整个窗口永远不可见，
      // 后面 View.animate().alpha(1f) 动的是 View 自己的绘制属性，救不回来。
      wrap.setAlpha(0f);
      wrap.setScaleX(0.86f);
      wrap.setScaleY(0.86f);
      wm.addView(wrap, tl);
      trash = wrap;
      trashLp = tl;
      trashHot = false;
      wrap.animate()
          .alpha(1f)
          .scaleX(1f)
          .scaleY(1f)
          .setDuration(150L)
          .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
          .start();
    } catch (Throwable t) {
      android.util.Log.w(TAG, "删除区没挂上: " + t);
      trash = null;
    }
  }

  private void hideTrash() {
    final View t = trash;
    if (t == null) return;
    trash = null;
    trashHot = false;
    try {
      t.animate().cancel();
      t.animate()
          .alpha(0f)
          .scaleX(0.86f)
          .scaleY(0.86f)
          .setDuration(130L)
          .setListener(
              new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator a) {
                  try {
                    if (wm != null) wm.removeView(t);
                  } catch (Throwable ignored) {
                  }
                }
              })
          .start();
    } catch (Throwable ignored) {
      try {
        if (wm != null) wm.removeView(t);
      } catch (Throwable ignored2) {
      }
    }
  }

  /** 球心是否落在删除区里。纯几何判定，不依赖任何触摸事件。 */
  private static boolean overTrash(Context ctx, int cx, int cy) {
    int r = dp(ctx, 58);
    int dx = cx - trashCx(ctx);
    int dy = cy - trashCy(ctx);
    return dx * dx + dy * dy <= r * r;
  }

  private void setTrashHot(boolean hot) {
    if (trashHot == hot || trash == null) return;
    trashHot = hot;
    try {
      trash.animate().cancel();
      trash.animate()
          .scaleX(hot ? 1.18f : 1f)
          .scaleY(hot ? 1.18f : 1f)
          .alpha(hot ? 1f : 0.92f)
          .setDuration(120L)
          .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
          .start();
    } catch (Throwable ignored) {
    }
  }

  /** 删除：记档 + 收走，之后 {@link #ensure} 不会再把它挂出来。 */
  private void doDelete(Context ctx) {
    final View b = ball;
    try {
      ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_HIDDEN, true).apply();
    } catch (Throwable ignored) {
    }
    try {
      if (b != null) {
        b.animate().cancel();
        b.animate()
            .alpha(0f)
            .scaleX(0.3f)
            .scaleY(0.3f)
            .setDuration(160L)
            .setListener(
                new AnimatorListenerAdapter() {
                  @Override
                  public void onAnimationEnd(android.animation.Animator a) {
                    try {
                      if (wm != null && b.getParent() != null) wm.removeView(b);
                    } catch (Throwable ignored) {
                    }
                    ball = null;
                  }
                })
            .start();
      } else {
        ball = null;
      }
    } catch (Throwable ignored) {
      ball = null;
    }
    // 别再排下一次半隐了：已经不存在了
    cancelDock();
  }

  // ============================== 常规流程 ==============================

  /** 点一下（或长按不到 600ms 的轻点）= 叫助手。 */
  private void wake(DshaAccessibilityService host) {
    try {
      AssistantPanel.show(host);
    } catch (Throwable t) {
      android.util.Log.w("DSHA/Pebble", "点球叫助手失败: " + t);
    }
  }

  private void teardown() {
    cancelDock();
    hideTrash();
    try {
      if (ball != null && wm != null) wm.removeView(ball);
    } catch (Throwable ignored) {
    }
    ball = null;
  }

  // ---------------------------------------------------------------- 位置记忆

  private static void loadSaved(Context ctx) {
    android.content.SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    savedX = sp.getInt(KEY_X, -1);
    savedY = sp.getInt(KEY_Y, -1);
  }

  private static int savedX = -1;
  private static int savedY = -1;

  private static void saveXY(Context ctx, int x, int y) {
    try {
      ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
          .putInt(KEY_X, x).putInt(KEY_Y, y).apply();
      // 浮窗落在哪儿是最难一眼看出来的状态（全靠存的坐标，不像布局里的控件能 dump），排障时靠这条。
      android.util.Log.i(TAG, "球位置落盘: x=" + x + " y=" + y + " screenW=" + screenW(ctx));
    } catch (Throwable ignored) {
    }
  }

  /** 松手后吸到最近的左 / 右边，180ms 滑过去，结束才落盘（避免动画中途被杀丢了位置）。 */
  private void snapToEdge(DshaAccessibilityService host) {
    if (ball == null || lp == null || wm == null) return;
    final int maxX = screenW(host) - lp.width;
    if (maxX <= 0) {
      saveXY(host, lp.x, lp.y);
      return;
    }
    final int target = (lp.x < maxX / 2) ? 0 : maxX;
    final int from = lp.x;
    android.util.Log.i(TAG, "拖完吸边: from x=" + from + " -> target x=" + target
        + " (" + (target == 0 ? "左边" : "右边") + ") maxX=" + maxX);
    if (target == lp.x) {
      saveXY(host, lp.x, lp.y);
      return;
    }
    ValueAnimator va = ValueAnimator.ofInt(from, target);
    va.setDuration(180L);
    va.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
    va.addUpdateListener(anim -> {
      lp.x = (int) anim.getAnimatedValue();
      try {
        if (wm != null && ball != null) wm.updateViewLayout(ball, lp);
      } catch (Throwable ignored) {
      }
    });
    va.addListener(new AnimatorListenerAdapter() {
      @Override
      public void onAnimationEnd(android.animation.Animator a) {
        saveXY(host, lp.x, lp.y);
      }
    });
    va.start();
  }

  private static int dp(Context ctx, float v) {
    return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
  }

  private static int screenW(Context ctx) {
    return ctx.getResources().getDisplayMetrics().widthPixels;
  }

  private static int screenH(Context ctx) {
    return ctx.getResources().getDisplayMetrics().heightPixels;
  }
}
