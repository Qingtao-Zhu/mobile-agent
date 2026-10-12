package com.deepseekharness.app.assistant;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Siri 那颗「活的」光球。
 *
 * <p>四团彩色径向渐变（青 / 紫 / 粉 / 橙）各按自己的频率做李萨如运动，
 * 用 {@code PorterDuff.Mode.ADD} 叠在一起，就成了会慢慢翻涌的彩色雾团 ——
 * 这是整个面板「有生气」的主要来源：静止时缓缓呼吸，听你说话时跟着音量鼓动，
 * 思考时快节奏脉动。
 *
 * <p>性能：所有绘制都发生在一张 ≤160px 的离屏位图上，再放大画到 View 上。
 * 放大本身就是一次双线性插值，等于免费拿到了模糊，一帧 4 个圆，低端机也稳 60fps。
 * 不 attached / 不可见时动画自动停。
 */
public final class SiriOrbView extends View {

  public enum Mode {
    /** 待机：慢速呼吸 */
    IDLE,
    /** 录音中：快，跟着麦克风音量鼓 */
    LISTENING,
    /** 等模型：中速，自己脉动 */
    THINKING
  }

  private static final int[] COLORS = {
    0xFF1FC8FF, // 青
    0xFF8A5BFF, // 紫
    0xFFFF4D9E, // 粉
    0xFFFF9E4D // 橙
  };

  /** 每团的相位与频率，错开才不会出现「整体平移」的呆板感。 */
  private static final float[][] BLOBS = {
    {0.00f, 0.61f, 0.83f, 0.42f},
    {1.90f, 0.47f, 1.07f, 0.55f},
    {3.40f, 0.83f, 0.59f, 0.38f},
    {5.10f, 0.53f, 1.31f, 0.60f}
  };

  private static final int BUF_MAX = 160;

  private final Paint blobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint drawPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private final PorterDuffXfermode addMode = new PorterDuffXfermode(PorterDuff.Mode.ADD);

  private Bitmap buffer;
  private Canvas bufferCanvas;
  private ValueAnimator animator;

  private Mode mode = Mode.IDLE;
  private float level = 0.35f;
  private float shownLevel = 0.35f;
  private long startMs;

  public SiriOrbView(Context c) {
    super(c);
    init();
  }

  public SiriOrbView(Context c, AttributeSet a) {
    super(c, a);
    init();
  }

  public SiriOrbView(Context c, AttributeSet a, int style) {
    super(c, a, style);
    init();
  }

  private void init() {
    startMs = SystemClock.uptimeMillis();
    blobPaint.setXfermode(addMode);
    // 硬件加速下 clipPath/ADD 表现一致，但离屏位图 + 放大必须走软件更稳。
    setLayerType(View.LAYER_TYPE_NONE, null);
  }

  public void setMode(Mode m) {
    if (m == null || m == mode) return;
    mode = m;
    if (m != Mode.LISTENING) level = m == Mode.THINKING ? 0.6f : 0.35f;
    ensureAnimator();
  }

  /** 0~1 的瞬时音量（仅 LISTENING 生效）。 */
  public void setLevel(float v) {
    float clamped = v < 0f ? 0f : (v > 1f ? 1f : v);
    // 简单低通，避免波形抖得像噪点
    level = level * 0.45f + clamped * 0.55f;
  }

  private float speed() {
    switch (mode) {
      case LISTENING:
        return 1.9f;
      case THINKING:
        return 1.15f;
      default:
        return 0.42f;
    }
  }

  private void ensureAnimator() {
    if (getWindowVisibility() != VISIBLE || getVisibility() != VISIBLE) return;
    if (animator != null && animator.isRunning()) return;
    if (animator == null) {
      animator = ValueAnimator.ofFloat(0f, 1f);
      animator.setDuration(7000L);
      animator.setRepeatCount(ValueAnimator.INFINITE);
      animator.setInterpolator(new LinearInterpolator());
      animator.addUpdateListener(
          an -> {
            invalidate();
          });
    }
    animator.start();
  }

  private void stopAnimator() {
    if (animator != null) animator.cancel();
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    startMs = SystemClock.uptimeMillis();
    ensureAnimator();
  }

  @Override
  protected void onDetachedFromWindow() {
    stopAnimator();
    super.onDetachedFromWindow();
  }

  @Override
  protected void onWindowVisibilityChanged(int visibility) {
    super.onWindowVisibilityChanged(visibility);
    if (visibility == VISIBLE) ensureAnimator();
    else stopAnimator();
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    int size = Math.max(48, Math.min(BUF_MAX, Math.min(w, h)));
    if (buffer != null && buffer.getWidth() == size) return;
    try {
      buffer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
      bufferCanvas = new Canvas(buffer);
    } catch (Throwable t) {
      buffer = null;
      bufferCanvas = null;
    }
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (buffer == null || bufferCanvas == null) return;
    float t = (SystemClock.uptimeMillis() - startMs) / 1000f;
    float speed = speed();
    float phase = t * speed;

    float want = mode == Mode.LISTENING ? level : (mode == Mode.THINKING ? 0.55f + 0.35f * (float) Math.sin(t * 4.2) : 0.35f);
    shownLevel = shownLevel * 0.75f + want * 0.25f;

    int size = buffer.getWidth();
    float cx = size / 2f;
    float cy = size / 2f;
    float baseR = size * 0.34f;

    bufferCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
    float amp = size * 0.17f * (0.55f + shownLevel);
    for (int i = 0; i < BLOBS.length; i++) {
      float[] b = BLOBS[i];
      float a = phase * b[1] + b[0];
      float bx = cx + amp * (float) Math.sin(a);
      float by = cy + amp * (float) Math.cos(a * b[2] + b[0]);
      float r = baseR * (b[3] + 0.22f * shownLevel + 0.05f * (float) Math.sin(phase * b[2] + i));
      // 起点 = 本色（带 alpha），终点 = 同色但 alpha=0：
      // 之前写成 COLORS[i] & 0x00FFFFFF，起点就被清成全透明，整颗球画了个寂寞。
      Shader shader =
          new RadialGradient(
              bx, by, r, COLORS[i], COLORS[i] & 0x00FFFFFF, Shader.TileMode.CLAMP);
      blobPaint.setShader(shader);
      blobPaint.setAlpha((int) (0xFF * (0.55f + 0.45f * shownLevel)));
      bufferCanvas.drawCircle(bx, by, r, blobPaint);
    }

    float pad = 0f;
    RectF dst = new RectF(pad, pad, getWidth() - pad, getHeight() - pad);
    drawPaint.setAlpha(0xFF);
    canvas.drawBitmap(buffer, null, dst, drawPaint);
  }
}
