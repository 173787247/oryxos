package io.oryxos.storage;

import static org.assertj.core.api.Assertions.assertThat;

import io.oryxos.core.durable.DurableTaskState;
import io.oryxos.core.durable.TaskCheckpoint;
import io.oryxos.core.flow.FlowRun;
import io.oryxos.core.flow.FlowRunState;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 原子态迁移契约（043 / #465 {@code TaskCheckpointStore#tryTransition}、046 / #468 {@code
 * FlowRunStore#tryTransitionRun}）：仅当库中当前态等于 expected 时写入 next；成功返回 next，冲突（状态不符或行不存在）返回 empty。
 *
 * <p>两档实跑：{@link AtomicTransitionSqliteTest}（SQLite 产品档参数 WAL + busy_timeout）与 {@link
 * AtomicTransitionPostgresTest}（zonky 嵌入式 PG，READ COMMITTED）。
 *
 * <p>核心用例是 {@code *_两个调用方都读到expected态_*}：两个调用方都先读到 expected 态再各自落写。读-判-写实现下两个 调用方都会拿到非空结果（PG
 * 档可稳定观测；SQLite 档是写锁异常，见两档子类说明），单条条件 UPDATE 下恰有一个成功。
 */
abstract class AtomicTransitionContractTest {

  /** 毫秒精度固定时刻：两库往返无截断，读回后可与记录逐字段相等。 */
  private static final Instant T0 = Instant.parse("2026-02-03T04:05:06.007Z");

  @Autowired DurableTaskCheckpointRepository checkpointRepository;

  @Autowired FlowRunRepository flowRunRepository;

  @Autowired FlowStepRepository stepRepository;

  @Autowired PlatformTransactionManager transactionManager;

  // ---------------------------------------------------------------- 检查点

  @Test
  void 检查点_状态相符_写全列且库中即返回值() {
    JpaTaskCheckpointStore store = checkpointStore();
    String id = "cp-happy";
    store.save(checkpoint(id, DurableTaskState.WAITING_APPROVAL, 1));

    TaskCheckpoint next = checkpoint(id, DurableTaskState.RUNNING, 2);
    Optional<TaskCheckpoint> moved =
        store.tryTransition(id, DurableTaskState.WAITING_APPROVAL, next);

    assertThat(moved).contains(next);
    // 逐列回读：SET 子句与 toEntity 差一列，这里就不相等（每列都被 next 改成了新值）。
    assertThat(store.findById(id)).contains(next);
  }

  @Test
  void 检查点_状态不符_返回empty且整行零改动() {
    JpaTaskCheckpointStore store = checkpointStore();
    String id = "cp-conflict";
    store.save(checkpoint(id, DurableTaskState.WAITING_APPROVAL, 1));
    TaskCheckpoint before = store.findById(id).orElseThrow();

    Optional<TaskCheckpoint> moved =
        store.tryTransition(
            id, DurableTaskState.SUCCEEDED, checkpoint(id, DurableTaskState.RUNNING, 2));

    assertThat(moved).isEmpty();
    assertThat(store.findById(id)).contains(before);
  }

  @Test
  void 检查点_行不存在_返回empty() {
    JpaTaskCheckpointStore store = checkpointStore();
    String id = "cp-absent";

    Optional<TaskCheckpoint> moved =
        store.tryTransition(
            id, DurableTaskState.WAITING_APPROVAL, checkpoint(id, DurableTaskState.RUNNING, 1));

    assertThat(moved).isEmpty();
    assertThat(store.findById(id)).isEmpty();
  }

  @Test
  void 检查点_从目标态再出发的迁移合法() {
    // DurableTaskService#completeReplay 真实形状：WAITING_APPROVAL -> RUNNING，再从 RUNNING 出发收敛终态。
    JpaTaskCheckpointStore store = checkpointStore();
    String id = "cp-chain";
    store.save(checkpoint(id, DurableTaskState.WAITING_APPROVAL, 1));

    assertThat(
            store.tryTransition(
                id, DurableTaskState.WAITING_APPROVAL, checkpoint(id, DurableTaskState.RUNNING, 2)))
        .isPresent();
    assertThat(
            store.tryTransition(
                id, DurableTaskState.RUNNING, checkpoint(id, DurableTaskState.SUCCEEDED, 3)))
        .isPresent();

    TaskCheckpoint stored = store.findById(id).orElseThrow();
    assertThat(stored.state()).isEqualTo(DurableTaskState.SUCCEEDED);
    assertThat(stored.attempt()).isEqualTo(3);
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void 检查点_两个调用方都读到expected态_恰有一个迁移成功() throws Exception {
    String id = "cp-race";
    JpaTaskCheckpointStore setup = checkpointStore();
    setup.save(checkpoint(id, DurableTaskState.WAITING_APPROVAL, 1));

    // 两个调用方各自的事务里先读到 expected 态，都读完才允许落写（读屏障只对先读的实现生效）。
    JpaTaskCheckpointStore racing =
        new JpaTaskCheckpointStore(
            readBarrier(checkpointRepository, DurableTaskCheckpointRepository.class));
    TaskCheckpoint left = checkpoint(id, DurableTaskState.CANCELLED, 2);
    TaskCheckpoint right = checkpoint(id, DurableTaskState.SUCCEEDED, 3);

    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    try {
      Callable<Race<TaskCheckpoint>> decideLeft =
          () -> call(tx, () -> racing.tryTransition(id, DurableTaskState.WAITING_APPROVAL, left));
      Callable<Race<TaskCheckpoint>> decideRight =
          () -> call(tx, () -> racing.tryTransition(id, DurableTaskState.WAITING_APPROVAL, right));
      Future<Race<TaskCheckpoint>> first = pool.submit(decideLeft);
      Future<Race<TaskCheckpoint>> second = pool.submit(decideRight);
      Race<TaskCheckpoint> a = first.get(30, TimeUnit.SECONDS);
      Race<TaskCheckpoint> b = second.get(30, TimeUnit.SECONDS);

      assertThat(a.error()).as("左调用方不得抛异常：%s / %s", a, b).isNull();
      assertThat(b.error()).as("右调用方不得抛异常：%s / %s", a, b).isNull();
      assertThat(List.of(a.won(), b.won()))
          .as("恰有一个调用方拿到非空结果：%s / %s", a, b)
          .containsExactlyInAnyOrder(true, false);
      TaskCheckpoint winner = a.won() ? a.snapshot().orElseThrow() : b.snapshot().orElseThrow();
      assertThat(setup.findById(id)).as("库中必须是赢家的快照，输家不得覆写").contains(winner);
    } finally {
      pool.shutdownNow();
    }
  }

  // ------------------------------------------------------------- Flow run

  @Test
  void 流程运行_状态相符_写全列且库中即返回值() {
    JpaFlowRunStore store = flowRunStore();
    String id = "fr-happy";
    store.saveRun(flowRun(id, FlowRunState.RUNNING, 1));

    FlowRun next = flowRun(id, FlowRunState.WAITING, 2);
    Optional<FlowRun> moved = store.tryTransitionRun(id, FlowRunState.RUNNING, next);

    assertThat(moved).contains(next);
    assertThat(store.findRun(id)).contains(next);
  }

  @Test
  void 流程运行_状态不符_返回empty且整行零改动() {
    JpaFlowRunStore store = flowRunStore();
    String id = "fr-conflict";
    store.saveRun(flowRun(id, FlowRunState.RUNNING, 1));
    FlowRun before = store.findRun(id).orElseThrow();

    Optional<FlowRun> moved =
        store.tryTransitionRun(id, FlowRunState.SUCCEEDED, flowRun(id, FlowRunState.WAITING, 2));

    assertThat(moved).isEmpty();
    assertThat(store.findRun(id)).contains(before);
  }

  @Test
  void 流程运行_行不存在_返回empty() {
    JpaFlowRunStore store = flowRunStore();
    String id = "fr-absent";

    Optional<FlowRun> moved =
        store.tryTransitionRun(id, FlowRunState.RUNNING, flowRun(id, FlowRunState.WAITING, 1));

    assertThat(moved).isEmpty();
    assertThat(store.findRun(id)).isEmpty();
  }

  @Test
  void 流程运行_从目标态再出发的迁移合法() {
    JpaFlowRunStore store = flowRunStore();
    String id = "fr-chain";
    store.saveRun(flowRun(id, FlowRunState.RUNNING, 1));

    assertThat(
            store.tryTransitionRun(id, FlowRunState.RUNNING, flowRun(id, FlowRunState.WAITING, 2)))
        .isPresent();
    assertThat(
            store.tryTransitionRun(
                id, FlowRunState.WAITING, flowRun(id, FlowRunState.SUCCEEDED, 3)))
        .isPresent();

    assertThat(store.findRun(id).orElseThrow().state()).isEqualTo(FlowRunState.SUCCEEDED);
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void 流程运行_两个调用方都读到expected态_恰有一个迁移成功() throws Exception {
    String id = "fr-race";
    JpaFlowRunStore setup = flowRunStore();
    setup.saveRun(flowRun(id, FlowRunState.RUNNING, 1));

    JpaFlowRunStore racing =
        new JpaFlowRunStore(
            readBarrier(flowRunRepository, FlowRunRepository.class), stepRepository);
    FlowRun left = flowRun(id, FlowRunState.CANCELLED, 2);
    FlowRun right = flowRun(id, FlowRunState.SUCCEEDED, 3);

    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    try {
      Callable<Race<FlowRun>> decideLeft =
          () -> call(tx, () -> racing.tryTransitionRun(id, FlowRunState.RUNNING, left));
      Callable<Race<FlowRun>> decideRight =
          () -> call(tx, () -> racing.tryTransitionRun(id, FlowRunState.RUNNING, right));
      Future<Race<FlowRun>> first = pool.submit(decideLeft);
      Future<Race<FlowRun>> second = pool.submit(decideRight);
      Race<FlowRun> a = first.get(30, TimeUnit.SECONDS);
      Race<FlowRun> b = second.get(30, TimeUnit.SECONDS);

      assertThat(a.error()).as("左调用方不得抛异常：%s / %s", a, b).isNull();
      assertThat(b.error()).as("右调用方不得抛异常：%s / %s", a, b).isNull();
      assertThat(List.of(a.won(), b.won()))
          .as("恰有一个调用方拿到非空结果：%s / %s", a, b)
          .containsExactlyInAnyOrder(true, false);
      FlowRun winner = a.won() ? a.snapshot().orElseThrow() : b.snapshot().orElseThrow();
      assertThat(setup.findRun(id)).as("库中必须是赢家的快照，输家不得覆写").contains(winner);
    } finally {
      pool.shutdownNow();
    }
  }

  // ------------------------------------------------------------------ 夹具

  JpaTaskCheckpointStore checkpointStore() {
    return new JpaTaskCheckpointStore(checkpointRepository);
  }

  JpaFlowRunStore flowRunStore() {
    return new JpaFlowRunStore(flowRunRepository, stepRepository);
  }

  /** 每个字段都随 {@code marker} 变化：任何一列没写进库里，回读比对都会不等。 */
  static TaskCheckpoint checkpoint(String id, DurableTaskState state, int marker) {
    return new TaskCheckpoint(
        id,
        1000L + marker,
        "session-" + marker,
        "agent-" + marker,
        state,
        "idem-" + id + "-" + marker,
        "PRE_TOOL_APPROVAL",
        "tool-" + marker,
        "call-" + marker,
        "{\"arg\":" + marker + "}",
        "policy-" + marker,
        "rule-" + marker,
        marker,
        marker == 0 ? null : "error-" + marker,
        marker == 0 ? null : 60 + marker,
        marker == 0 ? null : T0.plusSeconds(marker),
        T0.plusSeconds(marker),
        T0.plusSeconds(marker).plusMillis(1));
  }

  static FlowRun flowRun(String id, FlowRunState state, int marker) {
    return new FlowRun(
        id,
        "flow-" + marker,
        "v" + marker,
        "# flow " + marker,
        state,
        "entry-" + marker,
        "node-" + marker,
        "{\"in\":" + marker + "}",
        "{\"out\":" + marker + "}",
        marker == 0 ? null : "error-" + marker,
        marker,
        T0.plusSeconds(marker),
        T0.plusSeconds(marker).plusMillis(1));
  }

  /**
   * 交错屏障：把两个调用方推到「都读到 expected 态」或「都要落写」的同一时刻，两种实现各走一条——
   *
   * <ul>
   *   <li>读-判-写实现：{@code findById} 返回后等对方也读完，于是两边都拿到 expected 态的快照；
   *   <li>条件 UPDATE 实现：{@code transition*} 进入后等对方也进事务，两条 UPDATE 同时落在同一行上。
   * </ul>
   */
  private static <T> T readBarrier(T delegate, Class<T> type) {
    CountDownLatch allArrived = new CountDownLatch(2);
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> {
              String name = method.getName();
              boolean before = name.startsWith("transition");
              if (before) {
                rendezvous(allArrived);
              }
              Object result = invoke(delegate, method, args);
              if ("findById".equals(name)) {
                rendezvous(allArrived);
              }
              return result;
            }));
  }

  private static void rendezvous(CountDownLatch allArrived) throws InterruptedException {
    allArrived.countDown();
    allArrived.await(10, TimeUnit.SECONDS);
  }

  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }

  /** 在自己的事务里跑一个调用方，把结局（快照 / 异常）原样收下来——两档的失败方式不同，断言里都要看得见。 */
  private static <T> Race<T> call(TransactionTemplate tx, Supplier<Optional<T>> work) {
    try {
      Optional<T> snapshot = tx.execute(status -> work.get());
      return snapshot == null
          ? Race.failed(new IllegalStateException("事务回调返回 null"))
          : Race.of(snapshot);
    } catch (RuntimeException e) {
      return Race.failed(rootCause(e));
    }
  }

  private static Throwable rootCause(Throwable error) {
    Throwable current = error;
    while (current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    return current;
  }

  /** 一个调用方的结局：{@code snapshot} 空 = 契约上的冲突；{@code error} 非空 = 调用方拿到的是异常而非 empty。 */
  record Race<T>(Optional<T> snapshot, Throwable error) {

    static <T> Race<T> of(Optional<T> snapshot) {
      return new Race<>(snapshot, null);
    }

    static <T> Race<T> failed(Throwable error) {
      return new Race<>(null, error);
    }

    boolean won() {
      return snapshot != null && snapshot.isPresent();
    }

    @Override
    public String toString() {
      if (error != null) {
        return "抛异常 " + error.getClass().getName() + ": " + error.getMessage();
      }
      return snapshot.isPresent() ? "成功 " + snapshot.get() : "冲突 empty";
    }
  }
}
