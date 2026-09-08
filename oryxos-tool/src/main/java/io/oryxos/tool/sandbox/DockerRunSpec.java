package io.oryxos.tool.sandbox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * docker 档命令构造（024 FR-002/FR-004，纯函数——参数顺序与安全默认值在此钉死并由单测锁死）。
 *
 * <p>产物形态：{@code docker run --rm --cidfile <f> -v <root>:/workspace --network <n> --memory <m>
 * --cpus <c> --read-only --tmpfs /tmp --user <u> <image> <cmd…>}——工作区读写挂载 （RQ-4）、默认安全参数全在场（RQ-5：断网
 * / 限额 / rootfs 只读 + tmpfs / 非 root）。 命令内的工作区绝对路径经 {@link WorkspacePathMapper} 翻译为 {@code
 * /workspace/...}（FR-003）。
 */
public final class DockerRunSpec {

  private DockerRunSpec() {}

  /**
   * 构造完整的 docker run argv。
   *
   * @param props 执行后端配置（镜像必填——启动校验把关，此处不重复校验仅要求非空）
   * @param mapper 工作区路径翻译器（宿主根来自 {@code mapper.workspaceRoot()}）
   * @param cidFile 容器 ID 回写文件路径（{@code --cidfile}，超时联动 docker kill 的依据，FR-006）
   * @param command argv 形式的命令（argv[0] 为可执行文件），工作区路径参数被翻译
   */
  public static List<String> build(
      ExecutionBackendProperties props,
      WorkspacePathMapper mapper,
      Path cidFile,
      List<String> command) {
    Objects.requireNonNull(props, "props 不能为空");
    Objects.requireNonNull(mapper, "mapper 不能为空");
    Objects.requireNonNull(cidFile, "cidFile 不能为空");
    if (command == null || command.isEmpty()) {
      throw new IllegalArgumentException("command 不能为空");
    }
    if (props.image() == null || props.image().isBlank()) {
      throw new IllegalArgumentException("docker 档必须配置执行镜像（oryxos.sandbox.execution.image）");
    }

    List<String> argv = new ArrayList<>();
    argv.add("docker");
    argv.add("run");
    argv.add("--rm"); // 短命容器（RQ-2）：正常退出即销毁，无残留
    argv.add("--cidfile");
    argv.add(cidFile.toString());
    argv.add("-v");
    argv.add(mapper.workspaceRoot() + ":" + WorkspacePathMapper.CONTAINER_ROOT); // 读写挂载（RQ-4）
    argv.add("--network");
    argv.add(props.network()); // 默认 none（RQ-5）：容器内出网需显式打开
    argv.add("--memory");
    argv.add(props.memory());
    argv.add("--cpus");
    argv.add(props.cpus());
    argv.add("--read-only"); // 容器自身 rootfs 只读（工作区挂载不受影响，见 spec FR-014 澄清）
    argv.add("--tmpfs");
    argv.add("/tmp"); // 命令的临时写空间（rootfs 只读的配套）
    argv.add("--user");
    argv.add(props.user()); // 非 root 执行（默认 nobody）
    argv.add(props.image());
    for (String arg : command) {
      argv.add(mapper.toContainer(arg)); // 工作区路径翻译，非工作区直通
    }
    return List.copyOf(argv);
  }
}
