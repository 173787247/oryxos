package io.oryxos.tool.sandbox;

import io.oryxos.core.agent.ToolExecutionContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * docker 档执行后端（024 FR-002，Phase 3）：组合 {@link DockerRunSpec}（argv 构造）与 {@link
 * CidfileProcessWrapper}（destroy 联动 docker kill），经 CLI 派生短命容器执行命令。
 *
 * <p>失败语义（FR-011 fail-loud，不静默回落 local）：docker CLI 不存在时 {@link #start} 直接抛 带排障提示的
 * IOException；daemon 不可达 / 镜像缺失由 docker CLI 以非零退出码 + stderr 表达，经 ShellTools
 * 的既有错误路径回传模型（信息完整、进程不崩）。启动期校验见 DockerBackendStartupCheck。
 *
 * <p>审计贯通（FR-008）：start 时向 {@link ToolExecutionContext} 置入 backend=docker 与容器 ID
 * 的<b>惰性读取器</b>——cidfile 由 docker 异步写入，审计发生在进程结束后，届时必然可读。清理由 ToolExecutor 的既有 finally 统一承担（与
 * agentName 同生命周期）。
 */
public final class DockerProcessStarter implements ProcessStarter {

  private final ExecutionBackendProperties props;
  private final WorkspacePathMapper mapper;
  private final CidfileProcessWrapper.ContainerKiller killer;

  /** 起 docker CLI 进程用的本地执行器（可注入测试桩）。 */
  private final ProcessStarter cliStarter;

  public DockerProcessStarter(
      ExecutionBackendProperties props,
      WorkspacePathMapper mapper,
      CidfileProcessWrapper.ContainerKiller killer) {
    this(props, mapper, killer, new LocalProcessStarter());
  }

  DockerProcessStarter(
      ExecutionBackendProperties props,
      WorkspacePathMapper mapper,
      CidfileProcessWrapper.ContainerKiller killer,
      ProcessStarter cliStarter) {
    this.props = Objects.requireNonNull(props, "props 不能为空");
    this.mapper = Objects.requireNonNull(mapper, "mapper 不能为空");
    this.killer = Objects.requireNonNull(killer, "killer 不能为空");
    this.cliStarter = Objects.requireNonNull(cliStarter, "cliStarter 不能为空");
  }

  @Override
  public Process start(List<String> command) throws IOException {
    Path cidFile = newCidFile();
    List<String> argv = DockerRunSpec.build(props, mapper, cidFile, command);
    Process cli;
    try {
      cli = cliStarter.start(argv);
    } catch (IOException e) {
      deleteCidFilesQuietly(cidFile);
      // CLI 缺失是环境问题而非命令问题——分类提示，不静默回落 local（FR-011）
      throw new IOException(
          "docker CLI 启动失败（检查 docker 已安装且在 PATH；oryxos.sandbox.execution.backend=docker）: "
              + e.getMessage(),
          e);
    }
    CidfileProcessWrapper wrapper = new CidfileProcessWrapper(cli, cidFile, killer);
    ToolExecutionContext.setExecution(
        "docker", () -> CidfileProcessWrapper.readContainerId(cidFile));
    // 容器退出后清理 cid 临时文件（--rm 已清理容器本体，这里只清本进程的临时文件）
    wrapper.onExit().whenComplete((process, throwable) -> deleteCidFilesQuietly(cidFile));
    return wrapper;
  }

  /** 生成唯一且不存在的 cidfile 路径——docker 要求 cidfile 不预先存在（防容器 ID 混用）， 故用临时目录承载而非 createTempFile。 */
  private static Path newCidFile() throws IOException {
    return Files.createTempDirectory("oryxos-exec-").resolve("container.cid");
  }

  private static void deleteCidFilesQuietly(Path cidFile) {
    try {
      Files.deleteIfExists(cidFile);
      Path parent = cidFile.getParent();
      if (parent != null) { // createTempDirectory 产物必有父，防御相对路径场景
        Files.deleteIfExists(parent);
      }
    } catch (IOException e) {
      // 清理失败只留垃圾文件不留错误——tmpdir 由操作系统治理
    }
  }
}
