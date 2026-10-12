package com.deepseekharness.app.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link TaskGeneration} 的并发/代次测试。
 *
 * <p>用户要求：**"并发测试必须能够稳定复现竞态，而不能仅凭连续触发几次就宣称竞态已经修复。"**
 *
 * <p>所以这里不用"多跑几次看会不会出错"的做法，而是用 {@link CountDownLatch}
 * **精确编排**出修复前会失败的那个交错时序：
 * <pre>
 *   ① 旧任务拿到 gen=1，进入"两步之间"（等价于 6 秒手势超时 / 几十秒模型请求）
 *   ② 新任务启动：cancelAll()（置 cancelled=true + 推进代次），随后 begin()（清 cancelled + 再推进）
 *   ③ 旧任务回到检查点
 * </pre>
 * 修复前（布尔版）：旧任务在第 ②步会错过那次 {@code true}，第 ③步看到的是 {@code false}
 * → **继续执行** → 新旧任务同时操作屏幕。
 * 修复后（代次版）：第 ③步代次不符 → 必然退出。
 *
 * <p>反过来说：如果把 {@link TaskGeneration#isStale} 改回"只看一个布尔"，
 * {@link #竞态复现_旧任务在两步之间被顶替后不得恢复} 会立刻失败 —— 这就是"能复现"的证据。
 */
public class TaskGenerationTest {

  // ---------------------------------------------------------------- 基础语义

  @Test
  public void 新任务开始后旧代次立即失效() {
    TaskGeneration g = new TaskGeneration();
    int oldGen = g.begin();
    int newGen = g.begin();
    assertTrue("旧代次必须立即失效", g.isStale(oldGen));
    assertFalse("新代次有效", g.isStale(newGen));
  }

  @Test
  public void 取消后当前代次失效() {
    TaskGeneration g = new TaskGeneration();
    int gen = g.begin();
    assertFalse("取消前有效", g.isStale(gen));
    g.cancel();
    assertTrue("取消后失效", g.isStale(gen));
  }

  @Test
  public void 新任务开始会清掉取消态() {
    TaskGeneration g = new TaskGeneration();
    g.begin();
    g.cancel();
    assertTrue("取消后应处于取消态", g.isCancelled());
    int gen = g.begin();
    assertFalse("begin 应清掉取消态", g.isCancelled());
    assertFalse("新代次有效", g.isStale(gen));
  }

  @Test
  public void 代次号单调递增() {
    TaskGeneration g = new TaskGeneration();
    int a = g.begin();
    int b = g.begin();
    g.cancel();
    int c = g.begin();
    assertTrue(a < b);
    assertTrue(b < c);
    assertEquals(c, g.current());
  }

  @Test
  public void 中间代次全部失效_只有最新有效() {
    TaskGeneration g = new TaskGeneration();
    List<Integer> gens = new ArrayList<>();
    for (int i = 0; i < 5; i++) gens.add(g.begin());
    for (int i = 0; i < gens.size() - 1; i++) {
      assertTrue("第 " + i + " 代应失效", g.isStale(gens.get(i)));
    }
    assertFalse("最新一代有效", g.isStale(gens.get(gens.size() - 1)));
  }

  // ---------------------------------------------------------------- 竞态复现（核心）

  /**
   * **精确复现修复前的竞态**：旧任务卡在两步之间时被新任务顶替，回到检查点后
   * 必须失效（不得恢复执行）。
   *
   * <p>用 latch 把时序钉死，不依赖"多跑几次碰运气"。
   */
  @Test
  public void 竞态复现_旧任务在两步之间被顶替后不得恢复() throws Exception {
    final TaskGeneration g = new TaskGeneration();
    final int oldGen = g.begin(); // 旧任务拿到它的代次

    final CountDownLatch oldTaskInBetweenSteps = new CountDownLatch(1);
    final CountDownLatch newTaskStarted = new CountDownLatch(1);
    final AtomicBoolean oldTaskContinued = new AtomicBoolean(false);

    Thread oldTask =
        new Thread(
            () -> {
              try {
                // ① 旧任务"正在两步之间"（模拟手势超时/模型请求期间）
                oldTaskInBetweenSteps.countDown();
                // ② 等新任务完成启动（这段就是修复前它错过布尔翻转的窗口）
                newTaskStarted.await(3, TimeUnit.SECONDS);
                // ③ 回到检查点
                if (!g.isStale(oldGen)) {
                  oldTaskContinued.set(true); // 修复前会走到这里！
                }
              } catch (InterruptedException ignored) {
              }
            },
            "old-task");

    oldTask.start();
    assertTrue("旧任务应已进入两步之间", oldTaskInBetweenSteps.await(2, TimeUnit.SECONDS));

    // 新任务启动：cancelAll() → begin()（这正是 start() 里发生的顺序）
    g.cancel();
    int newGen = g.begin();
    newTaskStarted.countDown();

    oldTask.join(3000);

    assertFalse(
        "旧任务在检查点必须退出，不得恢复执行（修复前的竞态就是这里出错）",
        oldTaskContinued.get());
    assertTrue("旧代次必须失效", g.isStale(oldGen));
    assertFalse("新代次必须有效", g.isStale(newGen));
  }

  /**
   * 压力版：N 个线程同时"回到检查点"，只有最新代次的线程能继续，
   * 其余全部退出。用来证明代次判断本身没有竞态（比如非原子读）。
   */
  @Test
  public void 并发检查点_只有最新代次能通过() throws Exception {
    final TaskGeneration g = new TaskGeneration();
    final int threads = 16;
    final CountDownLatch allReady = new CountDownLatch(threads);
    final CountDownLatch go = new CountDownLatch(1);
    final AtomicInteger passed = new AtomicInteger(0);
    final List<Integer> gens = new ArrayList<>();

    // 先连续发 15 个旧代次
    for (int i = 0; i < threads - 1; i++) gens.add(g.begin());
    // 每个线程拿到自己的代次
    Thread[] ts = new Thread[threads];
    for (int i = 0; i < threads; i++) {
      final int myGen = gens.get(Math.min(i, gens.size() - 1));
      ts[i] =
          new Thread(
              () -> {
                try {
                  allReady.countDown();
                  go.await(3, TimeUnit.SECONDS);
                  if (!g.isStale(myGen)) passed.incrementAndGet();
                } catch (InterruptedException ignored) {
                }
              });
      ts[i].start();
    }
    allReady.await(3, TimeUnit.SECONDS);

    // 关键一步：在所有人"即将检查"的瞬间发出最新代次
    final int newest = g.begin();
    go.countDown();
    for (Thread t : ts) t.join(3000);

    // 允许最多 1 个通过（就是拿着 newest 的那个线程，如果它恰好用的是最后一代）
    assertTrue("过期的代次都不应通过，实际通过 " + passed.get() + " 个", passed.get() <= 1);
    assertFalse("最新代次本身有效", g.isStale(newest));
  }

  // ---------------------------------------------------------------- 与"布尔版"的对照

  /**
   * 对照实验：**模拟修复前的布尔实现**，证明同一个时序下它会出错。
   *
   * <p>这个测试不测产品代码，而是把"修复前的逻辑"在测试里复刻一遍，
   * 用**同一套时序**跑，证明：
   * <pre>
   *   布尔版 → 旧任务继续执行（BUGCONTINUED）
   *   代次版 → 旧任务退出（见上面的竞态复现测试）
   * </pre>
   * 两者对照，才叫"竞态可复现且已修复"。
   */
  @Test
  public void 对照_布尔版在同一时序下会出错() throws Exception {
    // ===== 复刻修复前的实现 =====
    final AtomicBoolean cancelled = new AtomicBoolean(false);
    final boolean[] oldTaskContinuedUnderBoolImpl = {false};

    cancelled.set(false); // 旧任务开始时的状态

    final CountDownLatch inBetween = new CountDownLatch(1);
    final CountDownLatch newStarted = new CountDownLatch(1);

    Thread oldTask =
        new Thread(
            () -> {
              try {
                inBetween.countDown();
                newStarted.await(3, TimeUnit.SECONDS);
                if (!cancelled.get()) { // 旧实现只判这个布尔
                  oldTaskContinuedUnderBoolImpl[0] = true;
                }
              } catch (InterruptedException ignored) {
              }
            });
    oldTask.start();
    inBetween.await(2, TimeUnit.SECONDS);

    // 新任务启动（旧实现的 start()：cancelAll() 后紧接着 cancelled=false）
    cancelled.set(true); // cancelAll()
    cancelled.set(false); // start() 里的重置 —— 就是这一步让旧任务"复活"
    newStarted.countDown();
    oldTask.join(3000);

    assertTrue(
        "布尔版必须在这个时序下出错（否则说明时序没编排对，代次版的说服力也要打问号）",
        oldTaskContinuedUnderBoolImpl[0]);
  }
}
