package io.oryxos.boot;

import io.oryxos.core.cluster.WorkspaceVersionPoller;
import io.oryxos.core.workspace.WorkspaceAvailability;
import io.oryxos.core.workspace.WorkspaceStorage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Cached probe: a stalled NFS syscall must not block HTTP health requests or create more probes.
 */
@Component("workspaceHealthIndicator")
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected storage and poller are shared application services.")
public final class WorkspaceHealthIndicator implements HealthIndicator, WorkspaceAvailability {
  private static final Duration MAX_AGE = Duration.ofSeconds(15);
  private final WorkspaceStorage storage;
  private final ObjectProvider<WorkspaceVersionPoller> poller;
  private final Clock clock;
  private final ScheduledExecutorService executor =
      new ScheduledThreadPoolExecutor(
          1, runnable -> Thread.ofPlatform().daemon().name("workspace-health").unstarted(runnable));
  private volatile Probe probe = new Probe(false, Instant.EPOCH);

  @Autowired
  public WorkspaceHealthIndicator(
      WorkspaceStorage storage, ObjectProvider<WorkspaceVersionPoller> poller) {
    this(storage, poller, Clock.systemUTC());
  }

  WorkspaceHealthIndicator(
      WorkspaceStorage storage, ObjectProvider<WorkspaceVersionPoller> poller, Clock clock) {
    this.storage = storage;
    this.poller = poller;
    this.clock = clock;
  }

  @PostConstruct
  void start() {
    executor.scheduleWithFixedDelay(this::probeOnce, 0, 5, TimeUnit.SECONDS);
  }

  void probeOnce() {
    try {
      storage.checkHealth();
      java.nio.file.Path target =
          storage.root().resolve(".workspace-health-" + java.util.UUID.randomUUID());
      try {
        io.oryxos.core.io.AtomicFiles.writeString(target, "probe");
      } finally {
        java.nio.file.Files.deleteIfExists(target);
      }
      probe = new Probe(true, clock.instant());
    } catch (IOException | RuntimeException failure) {
      // Do not return paths, remote endpoints, or exception messages through public health.
      probe = new Probe(false, clock.instant());
    }
  }

  @Override
  public Health health() {
    Probe current = probe;
    boolean fresh = current.checkedAt().plus(MAX_AGE).isAfter(clock.instant());
    WorkspaceVersionPoller currentPoller = poller.getIfAvailable();
    boolean reloadOk = currentPoller == null || currentPoller.reloadFailures().isEmpty();
    boolean busOk = currentPoller == null || !currentPoller.lastPollFailed();
    Health.Builder status =
        current.available() && fresh && reloadOk && busOk ? Health.up() : Health.down();
    return status
        .withDetail("provider", storage.providerId())
        .withDetail("storageAvailable", current.available() && fresh)
        .withDetail("reloadHealthy", reloadOk)
        .withDetail("notificationBusHealthy", busOk)
        .build();
  }

  @Override
  public boolean available() {
    Probe current = probe;
    return current.available() && current.checkedAt().plus(MAX_AGE).isAfter(clock.instant());
  }

  @PreDestroy
  void stop() {
    executor.shutdownNow();
  }

  private record Probe(boolean available, Instant checkedAt) {}
}
