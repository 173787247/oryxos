package io.oryxos.cli;

import static org.junit.jupiter.api.Assertions.*;

import io.oryxos.core.workspace.LocalWorkspaceStorageProvider;
import io.oryxos.core.workspace.WorkspaceCapability;
import io.oryxos.core.workspace.WorkspaceStorage;
import io.oryxos.core.workspace.WorkspaceStorageProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class WorkspaceStorageConfigurationTest {
  @TempDir Path root;

  @Test
  void configuredExtensionBeanIsSelectedWithoutChangingBusinessStore() {
    AtomicBoolean opened = new AtomicBoolean();
    var extension =
        new WorkspaceStorageProvider() {
          public String id() {
            return "extension";
          }

          public WorkspaceStorage open(Path configuredRoot, String identity) throws IOException {
            opened.set(true);
            WorkspaceStorage delegate =
                new LocalWorkspaceStorageProvider()
                    .open(configuredRoot.resolve("materialized"), identity);
            return new WorkspaceStorage() {
              public String providerId() {
                return "extension";
              }

              public Path root() {
                return delegate.root();
              }

              public Set<WorkspaceCapability> capabilities() {
                return delegate.capabilities();
              }

              public void checkHealth() throws IOException {
                delegate.checkHealth();
              }

              public Path resolve(String path) {
                return delegate.resolve(path);
              }

              public Path nativePath(Path path) throws IOException {
                return delegate.nativePath(path);
              }
            };
          }
        };
    new ApplicationContextRunner()
        .withUserConfiguration(WorkspaceStorageConfiguration.class)
        .withBean("extensionProvider", WorkspaceStorageProvider.class, () -> extension)
        .withPropertyValues("oryxos.root=" + root, "oryxos.workspace.storage.provider=extension")
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              WorkspaceStorage selected = context.getBean(WorkspaceStorage.class);
              assertTrue(opened.get());
              assertEquals("extension", selected.providerId());
              var store = new io.oryxos.core.agent.AgentStore(selected.root());
              store.write("configured", "content");
              assertEquals("content", store.read("configured"));
              var runtime = new OryxOsRuntime();
              org.springframework.test.util.ReflectionTestUtils.setField(
                  runtime, "workspaceStorage", selected);
              var whitelist =
                  runtime.sandbox(
                      org.mockito.Mockito.mock(io.oryxos.core.sandbox.SandboxWhitelistStore.class),
                      new io.oryxos.tool.sandbox.FileSandboxProperties(java.util.List.of()),
                      new io.oryxos.tool.sandbox.ShellSandboxProperties(java.util.List.of()),
                      new io.oryxos.tool.sandbox.HttpSandboxProperties(java.util.List.of()),
                      new io.oryxos.tool.sandbox.SmtpSandboxProperties(java.util.List.of()));
              var files = new io.oryxos.tool.builtin.FileTools(whitelist, selected);
              String selectedFile = selected.root().resolve("report.txt").toString();
              files.writeFile(selectedFile, "selected root");
              assertEquals("selected root", files.readFile(selectedFile));
              assertThrows(
                  io.oryxos.tool.sandbox.SandboxViolationException.class,
                  () -> files.writeFile(root.resolve("not-selected.txt").toString(), "denied"));
            });
  }

  @Test
  void sharedModeCannotInitializeAnUnidentifiedRoot() {
    Path missing = root.resolve("missing-mount");
    new ApplicationContextRunner()
        .withUserConfiguration(WorkspaceStorageConfiguration.class)
        .withPropertyValues(
            "oryxos.root=" + missing,
            "oryxos.workspace.storage.provider=shared-posix",
            "oryxos.workspace.storage.identity=expected")
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              assertFalse(Files.exists(missing));
            });
  }
}
