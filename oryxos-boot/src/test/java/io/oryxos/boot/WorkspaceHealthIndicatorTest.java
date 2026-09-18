package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.oryxos.core.cluster.WorkspaceVersionPoller;
import io.oryxos.core.workspace.SharedPosixWorkspaceStorageProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.actuate.health.Status;

class WorkspaceHealthIndicatorTest {
  @TempDir Path root;

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
