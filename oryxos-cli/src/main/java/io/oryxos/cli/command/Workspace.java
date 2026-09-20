package io.oryxos.cli.command;

import java.nio.file.Path;

/**
 * 轻命令（init / status / profile 不启动 Spring，图秒回）共用的工作区根解析。
 *
 * <p>解析顺序：系统属性 {@code -Doryxos.root} → 环境变量 {@code ORYXOS_ROOT} → 默认 {@code .oryxos}。与
 * serve/gateway 走的 Spring {@code oryxos.root} 对齐——Spring relaxed binding 同样把 {@code ORYXOS_ROOT} 绑到
 * {@code oryxos.root}，所以设一个环境变量两边都认。注意：{@code application.yml} 里的 {@code oryxos.root} 只对启动 Spring
 * 的命令生效，轻命令不读 yaml；要让轻命令也用自定义根，请用环境变量或系统属性。
 */
final class Workspace {

  static final String DEFAULT_ROOT = ".oryxos";

  private Workspace() {}

  /** 解析工作区根目录（可自定义路径与名字）。 */
  static Path root() {
    String root = setting("oryxos.root", "ORYXOS_ROOT", DEFAULT_ROOT);
    String provider =
        setting("oryxos.workspace.storage.provider", "ORYXOS_WORKSPACE_STORAGE_PROVIDER", "local");
    String identity =
        setting("oryxos.workspace.storage.identity", "ORYXOS_WORKSPACE_STORAGE_IDENTITY", "");
    var providers = new java.util.ArrayList<io.oryxos.core.workspace.WorkspaceStorageProvider>();
    providers.add(new io.oryxos.core.workspace.LocalWorkspaceStorageProvider());
    providers.add(new io.oryxos.core.workspace.SharedPosixWorkspaceStorageProvider());
    java.util.ServiceLoader.load(io.oryxos.core.workspace.WorkspaceStorageProvider.class)
        .forEach(providers::add);
    try {
      return new io.oryxos.core.workspace.WorkspaceStorageRegistry(providers)
          .open(provider, Path.of(root), identity)
          .root();
    } catch (java.io.IOException failure) {
      throw new java.io.UncheckedIOException("Workspace unavailable", failure);
    }
  }

  private static String setting(String property, String environment, String fallback) {
    String value = System.getProperty(property);
    if (value == null || value.isBlank()) {
      value = System.getenv(environment);
    }
    return value == null || value.isBlank() ? fallback : value;
  }
}
