package io.oryxos.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxos.core.cluster.ClusterProperties;
import io.oryxos.core.workspace.versioned.InMemoryVersionedAssetPointerStore;
import io.oryxos.core.workspace.versioned.VersionedAssetSource;
import io.oryxos.web.error.ResourceNotFoundException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VersionedAssetApiControllerTest {

  @TempDir Path root;

  @Test
  void disabledReturnsNotFound() {
    ClusterProperties cluster = new ClusterProperties();
    cluster.setVersionedAssetSourceEnabled(false);
    VersionedAssetSource source =
        new VersionedAssetSource(
            root, new InMemoryVersionedAssetPointerStore(), cluster, domain -> {});
    VersionedAssetApiController controller = new VersionedAssetApiController(source);
    assertThrows(ResourceNotFoundException.class, () -> controller.active("agents", "demo"));
  }

  @Test
  void enabledActiveAfterPublish() throws Exception {
    ClusterProperties cluster = new ClusterProperties();
    cluster.setVersionedAssetSourceEnabled(true);
    VersionedAssetSource source =
        new VersionedAssetSource(
            root, new InMemoryVersionedAssetPointerStore(), cluster, domain -> {});
    Path live = root.resolve("agents/demo");
    java.nio.file.Files.createDirectories(live);
    io.oryxos.core.io.AtomicFiles.writeString(live.resolve("AGENT.md"), "hello\n");
    VersionedAssetApiController controller = new VersionedAssetApiController(source);
    var req = mock(jakarta.servlet.http.HttpServletRequest.class);
    when(req.getAttribute(org.mockito.ArgumentMatchers.anyString())).thenReturn(null);
    controller.publish(req, "agents", "demo");
    assertEquals(1L, controller.active("agents", "demo").getData().get("version"));
  }
}
