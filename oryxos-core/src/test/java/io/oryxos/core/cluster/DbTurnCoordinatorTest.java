package io.oryxos.core.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** DbTurnCoordinator 语义单测（026 T007）：等待超时、续租 fencing 中断、stillHeld 硬闸、NOOP。 */
class DbTurnCoordinatorTest {

  private CoordinationStore store;
  private ClusterProperties properties;
  private ThreadPoolTaskScheduler scheduler;
  private DbTurnCoordinator coordinator;

  @BeforeEach
  void setUp() {
    store = mock(CoordinationStore.class);
    properties = new ClusterProperties();
    properties.setEnabled(true);
    properties.setInstanceId("test-inst");
    properties.setLeaseTtl(Duration.ofSeconds(30));
    properties.setPollInterval(Duration.ofMillis(20));
    properties.setWaitTimeout(Duration.ofMillis(150));
    scheduler = new ThreadPoolTaskScheduler();
    scheduler.initialize();
    coordinator = new DbTurnCoordinator(store, properties, scheduler);
  }

  @AfterEach
  void tearDown() {
    scheduler.shutdown();
  }

  @Test
  void acquire_succeedsAndReleasesOwnLease() {
    when(store.tryAcquireTurn(anyString(), anyString(), any())).thenReturn(true);

    TurnLease lease = coordinator.acquire("s-1");
    coordinator.release("s-1", lease);

    verify(store).releaseTurn("s-1", properties.owner());
  }

  @Test
  void acquire_waitsThenTimesOut() {
    when(store.tryAcquireTurn(anyString(), anyString(), any())).thenReturn(false);

    long start = System.nanoTime();
    assertThatThrownBy(() -> coordinator.acquire("s-busy"))
        .isInstanceOf(TurnWaitTimeoutException.class);
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
    assertThat(elapsedMs).isGreaterThanOrEqualTo(140L); // 等满 wait-timeout 而非立即失败
  }

  @Test
  void renewalFailure_interruptsHolderAndInvalidatesLease() throws Exception {
    properties.setHeartbeatInterval(Duration.ofMillis(30)); // 快续租便于测试
    when(store.tryAcquireTurn(anyString(), anyString(), any())).thenReturn(true);
    when(store.renewTurn(anyString(), anyString(), any())).thenReturn(false); // 续租即失败

    CountDownLatch interrupted = new CountDownLatch(1);
    Thread holder =
        new Thread(
            () -> {
              TurnLease lease = coordinator.acquire("s-1");
              try {
                Thread.sleep(5_000); // 模拟处理中的轮次
              } catch (InterruptedException e) {
                interrupted.countDown();
              }
              assertThat(lease.stillHeld()).isFalse(); // 失效后硬闸恒 false
            });
    holder.start();

    assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue(); // fencing 中断送达
    holder.join(2_000);
  }

  @Test
  void stillHeld_usesOwnerConditionalRenew() {
    when(store.tryAcquireTurn(anyString(), anyString(), any())).thenReturn(true);
    when(store.renewTurn("s-1", properties.owner(), properties.getLeaseTtl()))
        .thenReturn(true, false); // 第一次仍持有，第二次被回收

    TurnLease lease = coordinator.acquire("s-1");
    assertThat(lease.stillHeld()).isTrue();
    assertThat(lease.stillHeld()).isFalse();
  }

  @Test
  void noop_alwaysHoldsAndNeverTouchesStore() {
    TurnLease lease = TurnCoordinator.NOOP.acquire("any");
    assertThat(lease.stillHeld()).isTrue();
    lease.attachExecution(1L);
    TurnCoordinator.NOOP.release("any", lease); // 零存储访问，无异常即过
  }
}
