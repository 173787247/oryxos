package io.oryxos.tool.sandbox;

import java.nio.file.Path;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * 工作区路径双向翻译（024 FR-003 / 设计决策 D6）：宿主 {@code ORYXOS_ROOT} 前缀 ↔ 容器内固定挂载点 {@code
 * /workspace}。纯前缀字符串映射，不做 realpath 跟随（容器内路径无宿主 realpath 语义）；
 * 非工作区路径与相对路径原样直通（不属翻译范围）。审计记录始终使用宿主原始路径（FR-003）。
 */
public final class WorkspacePathMapper {

  /** 容器内固定挂载点（RQ-4 裁决推荐：固定挂载点让翻译规则可预测、可测试）。 */
  public static final String CONTAINER_ROOT = "/workspace";

  /** POSIX 绝对路径前缀。 */
  private static final String POSIX_ROOT = "/";

  /** Windows 盘符绝对路径（C:/ 或 C:\ 形态）。 */
  private static final String WINDOWS_DRIVE = "^[A-Za-z]:[\\\\/].*";

  /** 仅根（POSIX 的 "/"）不参与尾分隔符剥离。 */
  private static final int ROOT_ONLY = 1;

  /** 前缀剥离时的分隔符长度。 */
  private static final int ROOT_SEPARATOR_LENGTH = 1;

  private final Path workspaceRoot;

  public WorkspacePathMapper(Path workspaceRoot) {
    Path normalized =
        Objects.requireNonNull(workspaceRoot, "workspaceRoot 不能为空").toAbsolutePath().normalize();
    if (!normalized.equals(workspaceRoot.normalize()) && !normalized.isAbsolute()) {
      throw new IllegalArgumentException("workspaceRoot 必须能解析为绝对路径: " + workspaceRoot);
    }
    this.workspaceRoot = normalized;
  }

  /** 宿主工作区根（绝对、已归一化）——挂载参数 {@code -v <root>:/workspace} 的宿主侧来源。 */
  public Path workspaceRoot() {
    return workspaceRoot;
  }

  /** 该宿主路径是否落在工作区内（按路径组件比较，非字符串前缀）。 */
  public boolean isWorkspacePath(String hostPath) {
    if (hostPath == null || !isAbsolute(hostPath)) {
      return false; // 相对路径与非绝对路径不属工作区判定
    }
    try {
      return Path.of(hostPath).normalize().startsWith(workspaceRoot);
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static boolean isAbsolute(String path) {
    return path.startsWith(POSIX_ROOT) || path.matches(WINDOWS_DRIVE);
  }

  /**
   * 宿主绝对路径 → 容器内路径（{@code /workspace/<相对部分>}）。非工作区路径原样返回（直通）。 尾分隔符归一：工作区根本身 → {@code
   * /workspace}；子路径斜杠统一。
   */
  public String toContainer(String hostPath) {
    if (!isWorkspacePath(hostPath)) {
      return hostPath;
    }
    Path relative = workspaceRoot.relativize(Path.of(hostPath).normalize());
    if (relative.toString().isEmpty()) {
      return CONTAINER_ROOT;
    }
    StringJoiner joiner = new StringJoiner("/", CONTAINER_ROOT + "/", "");
    for (Path part : relative) {
      joiner.add(part.toString());
    }
    return joiner.toString();
  }

  /**
   * 容器内路径 → 宿主路径字符串。仅翻译 {@code /workspace} 前缀（含本体）；其余原样返回—— 容器自有路径（如 {@code
   * /tmp}）不属于工作区，保持容器视角原样（模型输出反译场景见 FR-003）。
   */
  public String toHost(String containerPath) {
    if (containerPath == null) {
      return null;
    }
    String normalized =
        containerPath.endsWith(POSIX_ROOT) && containerPath.length() > ROOT_ONLY
            ? containerPath.substring(0, containerPath.length() - 1)
            : containerPath;
    if (CONTAINER_ROOT.equals(normalized)) {
      return workspaceRoot.toString();
    }
    if (!normalized.startsWith(CONTAINER_ROOT + POSIX_ROOT)) {
      return containerPath;
    }
    String relative = normalized.substring(CONTAINER_ROOT.length() + ROOT_SEPARATOR_LENGTH);
    return workspaceRoot.resolve(relative).toString();
  }
}
