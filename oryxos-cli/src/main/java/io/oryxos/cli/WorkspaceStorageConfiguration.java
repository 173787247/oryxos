package io.oryxos.cli;

import io.oryxos.core.workspace.LocalWorkspaceStorageProvider;
import io.oryxos.core.workspace.SharedPosixWorkspaceStorageProvider;
import io.oryxos.core.workspace.WorkspaceStorage;
import io.oryxos.core.workspace.WorkspaceStorageProvider;
import io.oryxos.core.workspace.WorkspaceStorageRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit workspace plugin registry. Extensions contribute a named provider bean. */
@Configuration(proxyBeanMethods = false)
public class WorkspaceStorageConfiguration {
  @Bean
  WorkspaceStorageProvider localWorkspaceStorageProvider() {
    return new LocalWorkspaceStorageProvider();
  }

  @Bean
  WorkspaceStorageProvider sharedPosixWorkspaceStorageProvider() {
    return new SharedPosixWorkspaceStorageProvider();
  }

  @Bean(destroyMethod = "close")
  WorkspaceStorage workspaceStorage(
      List<WorkspaceStorageProvider> providers,
      @Value("${oryxos.workspace.storage.provider:local}") String provider,
      @Value("${oryxos.root:.oryxos}") String root,
      @Value("${oryxos.workspace.storage.identity:}") String identity)
      throws IOException {
    return new WorkspaceStorageRegistry(providers).open(provider, Path.of(root), identity);
  }
}
