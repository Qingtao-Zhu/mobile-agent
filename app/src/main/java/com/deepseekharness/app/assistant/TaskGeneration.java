package com.deepseekharness.app.assistant;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 任务代次管理（2026-10-11 从 {@code AssistantTaskRunner} 抽出来的纯逻辑）。
 *
 * <p><b>为什么单独成类</b>：用户要求"并发测试必须能够稳定复现竞态，而不能仅凭连续触发几次
 * 就宣称竞态已经修复"。原来代次判断是 {@code AssistantTaskRunner} 里的静态字段 +
 * 私有方法，JVM 测不到、时序也控制不了。抽成独立类之后，
 * 测试可以持有一个实例、用 {@link java.util.concurrent.CountDownLatch} 精确编排
 * "旧任务正在检查点前 → 新任务启动 → 旧任务回到检查点" 的时序，
 * **稳定复现**出修复前会出错的那个交错。
 *
 * <p><b>它解决的是什么</b>：原来取消只看一个全局布尔 {@code cancelled}，而
 * {@code start()} 的写法是 {@code cancelAll(); cancelled = false;} ——
 * 先把旧任务标记取消、紧接着又重置。旧任务若此刻正卡在两步之间
 * （6 秒手势超时 / 几十秒模型请求），会错过那次 {@code true}，
 * 回到检查点时看到的是新任务设的 {@code false}，于是**继续操作屏幕**。
 *
 * <p>代次的做法：每次提交任务都发一个新号，任务把自己的号记下来。
 * 检查点判的是"我还是不是最新那一号"，**与它有没有错过某次布尔翻转无关**。
 *
 * <p>线程安全：{@link #generation} 用 AtomicInteger，{@link #cancelled} 用 volatile。
 */
final class TaskGeneration {

  private final AtomicInteger generation = new AtomicInteger(0);
  private volatile boolean cancelled;

  /**
   * 开一个新任务，返回属于它的代次号。
   *
   * <p>注意：新任务开始会让**所有旧代次立即失效**（它们的号不再等于最新号）。
   */
  int begin() {
    cancelled = false;
    return generation.incrementAndGet();
  }

  /**
   * 取消当前任务。同时推进代次 —— 这样不依赖"某一刻置的 true 被旧任务看到"，
   * 旧任务在任何检查点都会因为代次不符而退出。
   */
  void cancel() {
    cancelled = true;
    generation.incrementAndGet();
  }

  /** 本代次是否已失效（被取消，或被更新的任务顶替）。 */
  boolean isStale(int myGeneration) {
    return cancelled || myGeneration != generation.get();
  }

  /** 当前代次号（诊断/日志用）。 */
  int current() {
    return generation.get();
  }

  /** 是否处于取消态（诊断用）。 */
  boolean isCancelled() {
    return cancelled;
  }
}
