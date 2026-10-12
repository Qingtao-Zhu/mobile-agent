package com.deepseekharness.app.assistant;

import android.graphics.Bitmap;

/**
 * 面板玻璃背景用的模糊：**纯 Java，零依赖**。
 *
 * <p>为什么自己写而不用 RenderScript / RenderEffect / 第三方库：
 * <ul>
 *   <li>{@code RenderEffect.createBlurEffect} 要 API 31，DSHA 兼容版最低 Android 6（API 23），用不了；
 *   <li>RenderScript 在 AGP 8 起已被移除，加回来要动构建插件，风险大；
 *   <li>Blurry / BlurView 这些库要新增 JitPack 依赖，离线构建环境不保证拉得到。
 * </ul>
 * 面板只需要把「屏幕缩略图」糊掉，输入是 180×400 这种小图，
 * 三趟盒式模糊（box blur ≈ 高斯）足够快，实测整条链路在后台线程 <30ms。
 *
 * <p>算法：可分离盒式模糊，滑动窗口累加（O(w·h)，与半径无关），
 * 横竖各一趟算一趟，跑三趟逼近高斯。直接对 ARGB 四个通道分别累加，
 * 截图是不透明图（alpha=255），边缘按 clamp 取像素，不会露黑边。
 */
final class FastBlur {

  private FastBlur() {}

  /**
   * @param src    输入位图（不会被修改）
   * @param radius 盒半径（像素）；建议 4~16
   * @param passes 趟数（1~3，越多越接近高斯）
   */
  static Bitmap blur(Bitmap src, int radius, int passes) {
    if (src == null || src.getWidth() < 2 || src.getHeight() < 2 || radius < 1) return src;
    Bitmap.Config cfg = src.getConfig();
    Bitmap out = src.copy(cfg == null ? Bitmap.Config.ARGB_8888 : cfg, true);
    if (out == null) return src;
    int w = out.getWidth();
    int h = out.getHeight();
    int[] pix = new int[w * h];
    out.getPixels(pix, 0, w, 0, 0, w, h);
    int[] tmp = new int[w * h];
    int p = passes < 1 ? 1 : (passes > 3 ? 3 : passes);
    for (int i = 0; i < p; i++) {
      boxH(pix, tmp, w, h, radius);
      boxV(tmp, pix, w, h, radius);
    }
    out.setPixels(pix, 0, w, 0, 0, w, h);
    return out;
  }

  private static void boxH(int[] src, int[] dst, int w, int h, int r) {
    int win = r + r + 1;
    for (int y = 0; y < h; y++) {
      int row = y * w;
      long sa = 0, sr = 0, sg = 0, sb = 0;
      for (int i = -r; i <= r; i++) {
        int c = src[row + clamp(i, 0, w - 1)];
        sa += (c >>> 24) & 0xFF;
        sr += (c >>> 16) & 0xFF;
        sg += (c >>> 8) & 0xFF;
        sb += c & 0xFF;
      }
      for (int x = 0; x < w; x++) {
        dst[row + x] =
            (((int) (sa / win)) << 24)
                | (((int) (sr / win)) << 16)
                | (((int) (sg / win)) << 8)
                | ((int) (sb / win));
        int co = src[row + clamp(x - r, 0, w - 1)];
        int ci = src[row + clamp(x + r + 1, 0, w - 1)];
        sa += ((ci >>> 24) & 0xFF) - ((co >>> 24) & 0xFF);
        sr += ((ci >>> 16) & 0xFF) - ((co >>> 16) & 0xFF);
        sg += ((ci >>> 8) & 0xFF) - ((co >>> 8) & 0xFF);
        sb += (ci & 0xFF) - (co & 0xFF);
      }
    }
  }

  private static void boxV(int[] src, int[] dst, int w, int h, int r) {
    int win = r + r + 1;
    for (int x = 0; x < w; x++) {
      long sa = 0, sr = 0, sg = 0, sb = 0;
      for (int i = -r; i <= r; i++) {
        int c = src[clamp(i, 0, h - 1) * w + x];
        sa += (c >>> 24) & 0xFF;
        sr += (c >>> 16) & 0xFF;
        sg += (c >>> 8) & 0xFF;
        sb += c & 0xFF;
      }
      for (int y = 0; y < h; y++) {
        dst[y * w + x] =
            (((int) (sa / win)) << 24)
                | (((int) (sr / win)) << 16)
                | (((int) (sg / win)) << 8)
                | ((int) (sb / win));
        int co = src[clamp(y - r, 0, h - 1) * w + x];
        int ci = src[clamp(y + r + 1, 0, h - 1) * w + x];
        sa += ((ci >>> 24) & 0xFF) - ((co >>> 24) & 0xFF);
        sr += ((ci >>> 16) & 0xFF) - ((co >>> 16) & 0xFF);
        sg += ((ci >>> 8) & 0xFF) - ((co >>> 8) & 0xFF);
        sb += (ci & 0xFF) - (co & 0xFF);
      }
    }
  }

  private static int clamp(int v, int lo, int hi) {
    return v < lo ? lo : (v > hi ? hi : v);
  }
}
