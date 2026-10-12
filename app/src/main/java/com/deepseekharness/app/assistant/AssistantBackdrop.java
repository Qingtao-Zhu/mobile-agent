package com.deepseekharness.app.assistant;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;

import com.deepseekharness.app.DshaAccessibilityService;

/**
 * 面板的「真玻璃」底层。—— <b>2026-10-07 起暂停使用</b>：面板已改为悬浮窗 + 活玻璃
 * （见 {@link AssistantPanel} 类注释：本机 API 30 做不了系统模糊，截图糊玻璃又违背
 * "背景必须活、下面的页面可操作"的用户要求）。这两个类（本类 + FastBlur）保留给
 * 以后 API 31+ 机器的 RenderEffect / blurBehind 方案，别急着删。
 *
 * <p>市面上所谓玻璃拟态，大多只是一层半透明纯色 —— 那种在深色卡片上看上去就是「硬邦邦」的。
 * 这里做的是真·背景模糊：面板刚起来、自己还没画上屏幕的那一刻，让无障碍服务抓一张真屏幕，
 * 把卡片压住的那块区域裁出来、降采样、糊掉，再合成为一张四角全圆的玻璃底图。
 * 于是卡片里透出来的就是**用户刚才真正在看的东西**（微信的绿、相册的照片、桌面的壁纸），
 * 而不是一块死板的灰。
 *
 * <p>时序（关键，决定了为什么截图里不会有面板自己）：
 * <ol>
 *   <li>Activity {@code onCreate} → 布局就位，卡片 {@code alpha=0}（窗口是透明的，不占像素）；
 *   <li>后台线程调 {@link DshaAccessibilityService#uiScreenshotBitmap(long)} 抓屏；
 *   <li>回来后合成玻璃底图 → 设成卡片背景 → 播入场动画（下滑淡入）。
 * </ol>
 * 抓屏失败 / 超时 / 服务没开，一律返回 null，面板退回 {@code bg_glass_card} 纯色玻璃，
 * 功能不受任何影响。
 */
final class AssistantBackdrop {

  /** 截屏降采样倍率：原图 720×1600 → 120×266，糊起来又快又够用。 */
  private static final int DOWNSCALE = 6;
  /**
   * 玻璃底色的压暗量（叠在模糊图上）。2026-10-07 按用户反馈调亮过一轮：
   * 0x8C(55%) 的黑罩在深色壁纸上整张卡发闷、圆角看起来像"从黑边界里裁出来的"，
   * 降到 0x66(40%) 让下层画面更多透上来，字仍可读。
   */
  private static final int TINT = 0x660D1120;
  /** 顶部高光：玻璃「反光」的来源。 */
  private static final int GLOSS_TOP = 0x3CFFFFFF;

  private AssistantBackdrop() {}

  /** 抓一张真屏幕；拿不到（服务没开/超时/低版本）返回 null。调用方必须在后台线程。 */
  static Bitmap capture(long timeoutMs) {
    if (Build.VERSION.SDK_INT < 30) return null;
    try {
      return DshaAccessibilityService.uiScreenshotBitmap(timeoutMs);
    } catch (Throwable t) {
      return null;
    }
  }

  /**
   * 把整屏截图合成成卡片玻璃底图。
   *
   * @param screen 整屏截图（原尺寸）
   * @param cardW  卡片宽度（px）
   * @param cardH  卡片高度（px）
   * @param radius 四个角的圆角（px）
   * @param cardX  卡片左上角在屏幕上的 x（卡片现在不贴屏幕边，模糊要裁对地方）
   * @param cardY  卡片左上角在屏幕上的 y
   * @return 玻璃底图；参数不合法返回 null
   */
  static Bitmap glass(Bitmap screen, int cardW, int cardH, float radius, int cardX, int cardY) {
    if (screen == null || cardW <= 8 || cardH <= 8) return null;
    int sw = screen.getWidth();
    int sh = screen.getHeight();
    if (sw < 16 || sh < 16) return null;

    try {
      // 1) 降采样整屏
      int smallW = Math.max(16, sw / DOWNSCALE);
      int smallH = Math.max(16, sh / DOWNSCALE);
      Bitmap small = Bitmap.createScaledBitmap(screen, smallW, smallH, true);

      // 2) 裁出卡片压住的那块（横向按卡片真实位置裁；向上多留 25% 让模糊有素材，
      //    避免边缘发暗）。卡片可能超出截图边界（理论不会），一律夹回屏内。
      int cx = Math.max(0, Math.min(cardX, sw - 1)) / DOWNSCALE;
      int cy = Math.max(0, Math.min(cardY, sh - 1)) / DOWNSCALE;
      float wantW = Math.min(smallW - cx, cardW / (float) DOWNSCALE);
      float wantH = Math.min(smallH - cy, (cardH * 1.25f) / DOWNSCALE);
      int cropW = Math.max(8, Math.round(wantW));
      int cropH = Math.max(8, Math.round(wantH));
      if (cx + cropW > smallW) cropW = smallW - cx;
      if (cy + cropH > smallH) cropH = smallH - cy;
      Bitmap region = Bitmap.createBitmap(small, cx, cy, cropW, cropH);
      if (region != small) small.recycle();

      // 3) 糊
      int radiusBlur = Math.min(18, Math.max(5, cropH / 10));
      Bitmap blurred = FastBlur.blur(region, radiusBlur, 3);
      if (blurred != region) region.recycle();

      // 4) 合成四角全圆的玻璃
      Bitmap out = Bitmap.createBitmap(cardW, cardH, Bitmap.Config.ARGB_8888);
      Canvas c = new Canvas(out);
      Path path = rounded(0, 0, cardW, cardH, radius);

      c.save();
      c.clipPath(path);
      // 模糊图铺满卡片
      Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
      c.drawBitmap(blurred, null, new RectF(0, 0, cardW, cardH), bmpPaint);
      // 压暗 + 一点点冷色调，保证白字可读
      Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
      tint.setColor(TINT);
      c.drawPath(path, tint);
      // 顶部高光
      Paint gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
      gloss.setShader(
          new LinearGradient(
              0f, 0f, 0f, cardH * 0.45f, GLOSS_TOP, 0x00FFFFFF, Shader.TileMode.CLAMP));
      c.drawPath(path, gloss);
      c.restore();

      // 5) 一圈极细的亮边：玻璃的「厚度」
      Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
      edge.setStyle(Paint.Style.STROKE);
      edge.setStrokeWidth(Math.max(1f, radius / 22f));
      edge.setColor(0x40FFFFFF);
      c.drawPath(path, edge);

      blurred.recycle();
      return out;
    } catch (Throwable t) {
      return null;
    }
  }

  /** 四个角全圆的路径（凸路径，clipPath 在所有版本都不会炸）。 */
  private static Path rounded(float l, float t, float r, float b, float radius) {
    Path p = new Path();
    float rr = Math.min(radius, Math.min(r - l, b - t) / 2.5f);
    p.addRoundRect(new RectF(l, t, r, b), rr, rr, Path.Direction.CW);
    return p;
  }

  /**
   * 从整屏截图里 1:1 裁一块（坐标夹回屏内），给根布局当「假透明」底。
   *
   * <p>2026-10-07 实测：这台 EMUI 12 的 HWC 不给窗口里的透明像素合成下层
   * （SurfaceFlinger 里我们的层 TransparentRegion 恒为空，与报告 §5.6 同源），
   * 窗口里没画到的像素一律合成成纯黑 —— 卡片圆角外、四周留缝全是黑边，
   * 用户反馈"像从黑边界里硬裁出来的"。对策是把呼出面板那一瞬抓到的画面原样贴回去：
   * 圆角外露出的就是呼出前的真实屏幕，视觉上与真透明无差别
   * （玻璃卡片本来就定格了背景，不存在"背后还在动"的破绽）。
   */
  static Bitmap region(Bitmap screen, int x, int y, int w, int h) {
    if (screen == null || w <= 0 || h <= 0) return null;
    int sw = screen.getWidth();
    int sh = screen.getHeight();
    if (sw <= 0 || sh <= 0) return null;
    int l = Math.max(0, Math.min(x, sw - 1));
    int t = Math.max(0, Math.min(y, sh - 1));
    int r = Math.min(sw, x + w);
    int b = Math.min(sh, y + h);
    if (r - l <= 0 || b - t <= 0) return null;
    try {
      // 用 canvas 显式拷贝，不依赖 createBitmap 的"可能共享源缓冲"行为，
      // 调用方随后 recycle(rawScreen) 也不会把这块底图弄坏。
      Bitmap out = Bitmap.createBitmap(r - l, b - t, Bitmap.Config.ARGB_8888);
      new Canvas(out).drawBitmap(
          screen, new Rect(l, t, r, b), new RectF(0, 0, r - l, b - t),
          new Paint(Paint.FILTER_BITMAP_FLAG));
      return out;
    } catch (Throwable t2) {
      return null;
    }
  }

  /** 把一张位图清成全透明（兜底用）。 */
  static void clear(Bitmap b) {
    if (b == null) return;
    try {
      new Canvas(b).drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
    } catch (Throwable ignored) {
    }
  }

  static {
    // 触发一次 PorterDuffXfermode 的类加载，避免第一次合成时在主线程做类初始化。
    new PorterDuffXfermode(PorterDuff.Mode.SRC_OVER);
  }
}
