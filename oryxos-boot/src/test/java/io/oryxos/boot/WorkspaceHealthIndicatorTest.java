package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxos.core.cluster.WorkspaceVersionPoller;
import io.oryxos.core.workspace.SharedPosixWorkspaceStorageProvider;
import io.oryxos.core.workspace.WorkspaceStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.health.contributor.Status;

class WorkspaceHealthIndicatorTest {
  @TempDir Path root;

  @Test
  void blockedProbeDoesNotBlockHealthRequestsAndExpiredCacheRecovers() throws Exception {
    Instant initial = Instant.parse("2026-01-01T00:00:00Z");
    AtomicReference<Instant> now = new AtomicReference<>(initial);
    Clock clock = mock(Clock.class);
    when(clock.instant()).thenAnswer(invocation -> now.get());
    WorkspaceStorage storage = mock(WorkspaceStorage.class);
    when(storage.root()).thenReturn(root);
    when(storage.providerId()).thenReturn("blocked-test");
    AtomicBoolean block = new AtomicBoolean();
    AtomicInteger checks = new AtomicInteger();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              checks.incrementAndGet();
              if (block.get()) {
                entered.countDown();
                release.await();
              }
              return null;
            })
        .when(storage)
        .checkHealth();
    var provider = new DefaultListableBeanFactory().getBeanProvider(WorkspaceVersionPoller.class);
    var health = new WorkspaceHealthIndicator(storage, provider, clock);
    var probeThread = Executors.newSingleThreadExecutor();
    var requestThread = Executors.newSingleThreadExecutor();
    try {
      health.probeOnce();
      assertEquals(Status.UP, health.health().getStatus());
      block.set(true);
      var blockedProbe = probeThread.submit(() -> health.probeOnce());
      assertTrue(entered.await(2, TimeUnit.SECONDS), "probe must enter the blocked storage call");

      now.set(initial.plusSeconds(14));
      assertEquals(
          Status.UP, requestThread.submit(() -> health.health()).get(2, TimeUnit.SECONDS).getStatus());
      now.set(initial.plusSeconds(15));
      var expired = requestThread.submit(() -> health.health()).get(2, TimeUnit.SECONDS);
      assertEquals(Status.DOWN, expired.getStatus());
      assertEquals(false, requestThread.submit(() -> health.available()).get(2, TimeUnit.SECONDS));
      assertEquals(false, expired.getDetails().get("storageAvailable"));
      assertEquals(2, checks.get(), "health requests must not launch additional storage probes");

      release.countDown();
      blockedProbe.get(2, TimeUnit.SECONDS);
      var recovered = requestThread.submit(() -> health.health()).get(2, TimeUnit.SECONDS);
      assertEquals(Status.UP, recovered.getStatus());
      assertEquals(true, requestThread.submit(() -> health.available()).get(2, TimeUnit.SECONDS));
      assertEquals(true, recovered.getDetails().get("storageAvailable"));
      try (var files = Files.list(root)) {
        assertEquals(0, files.count(), "completed probes must clean their temporary artifacts");
      }
    } finally {
      release.countDown();
      probeThread.shutdownNow();
      requestThread.shutdownNow();
      health.stop();
    }
  }

  @Test
  void failedReloadDoesNotPreventRepairWhenStorageItselfIsHealthy() throws Exception {
    var storage = new SharedPosixWorkspaceStorageProvider();
    Files.writeString(root.resolve(".workspace-id"), "repair-test");
    var poller = mock(WorkspaceVersionPoller.class);
    when(poller.reloadFailures()).thenReturn(java.util.Map.of("agents", "invalid definition"));
    when(poller.lastPollFailed()).thenReturn(true);
    var factory = new DefaultListableBeanFactory();
    factory.registerSingleton("poller", poller);
    var health =
        new WorkspaceHealthIndicator(
            storage.open(root, "repair-test"),
            factory.getBeanProvider(WorkspaceVersionPoller.class));
    try {
      health.probeOnce();
      assertEquals(Status.DOWN, health.health().getStatus());
      assertTrue(health.available(), "healthy storage must allow management repairs");
    } finally {
      health.stop();
    }
  }

  @Test
  void identityLossWithdrawsReadinessAndRepairRestoresIt() throws Exception {
    Files.writeString(root.resolve(".workspace-id"), "test-workspace");
    var storage = new SharedPosixWorkspaceStorageProvider().open(root, "test-workspace");
    var provider = new DefaultListableBeanFactory().getBeanProvider(WorkspaceVersionPoller.class);
    var health = new WorkspaceHealthIndicator(storage, provider);
    try {
      assertEquals(Status.DOWN, health.health().getStatus());
      health.probeOnce();
      assertEquals(Status.UP, health.health().getStatus());
      Files.delete(root.resolve(".workspace-id"));
      health.probeOnce();
      assertEquals(Status.DOWN, health.health().getStatus());
      Files.writeString(root.resolve(".workspace-id"), "test-workspace");
      health.probeOnce();
      assertEquals(Status.UP, health.health().getStatus());
    } finally {
      health.stop();
    }
  }
}
