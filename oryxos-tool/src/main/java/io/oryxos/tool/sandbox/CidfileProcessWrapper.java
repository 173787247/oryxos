package io.oryxos.tool.sandbox;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * docker CLI 进程的 destroy 联动包装（024 FR-006）：杀掉 docker CLI 及其进程树<b>够不到容器</b>—— 容器由 daemon 派生，与 CLI
 * 无父子关系。本包装在 destroy/destroyForcibly 时先按 {@code --cidfile} 回读容器 ID 并执行 {@code docker
 * kill}（终止真实执行本体，ProcessStarter 契约），再终止 CLI 进程。
 *
 * <p>竞态兜底（plan 风险 1）：容器极快退出或 cidfile 尚未写入时读不到 ID——只杀 CLI； {@code docker kill} 失败（容器已不存在）按成功处理，不阻断
 * CLI 清理。
 */
public final class CidfileProcessWrapper extends Process {
  private static final Logger LOG = LoggerFactory.getLogger(CidfileProcessWrapper.class);

  /** docker kill 子进程的等待上限（秒）——超时强杀 kill 进程并报错。 */
  private static final int KILL_TIMEOUT_SECONDS = 5;

  /** 容器终止执行器：生产实现跑 {@code docker kill <id>}，单测注入记录桩。 */
  @FunctionalInterface
  public interface ContainerKiller {
    void kill(String containerId) throws IOException;
  }

  private final Process cli;
  private final Path cidFile;
  private final ContainerKiller killer;

  public CidfileProcessWrapper(Process cli, Path cidFile, ContainerKiller killer) {
    this.cli = Objects.requireNonNull(cli, "cli 不能为空");
    this.cidFile = Objects.requireNonNull(cidFile, "cidFile 不能为空");
    this.killer = Objects.requireNonNull(killer, "killer 不能为空");
  }

  /**
   * 生产用 killer：起 {@code docker kill} 子进程并短暂等待（退出码忽略——容器已不存在也算达成）。 命名类而非 lambda：lambda 编译成 synthetic
   * 方法，方法上的注解盖不住 SpotBugs 告警 （ShellTools.startProcess 同款教训）。
   */
  public static ContainerKiller dockerCliKiller() {
    return new DockerCliKiller();
  }

  private static final class DockerCliKiller implements ContainerKiller {

    @Override
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
        value = "COMMAND_INJECTION",
        justification =
            "containerId 取自本进程创建的 --cidfile（docker daemon 写入的十六进制 ID），非用户输入；"
                + "以 argv 形式传给 docker CLI、不经 shell 解释（LocalProcessStarter 同款先例）")
    public void kill(String containerId) throws IOException {
      Process kill = new ProcessBuilder("docker", "kill", containerId).start();
      try {
        if (!kill.waitFor(KILL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          kill.destroyForcibly();
          throw new IOException("docker kill 超时未返回: " + containerId);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        kill.destroyForcibly();
        throw new IOException("docker kill 被中断: " + containerId, e);
      }
    }
  }

  @Override
  public void destroy() {
    killContainerThen(Process::destroy);
  }

  @Override
  public Process destroyForcibly() {
    killContainerThen(Process::destroyForcibly);
    return this;
  }

  private void killContainerThen(Consumer<Process> cliKill) {
    String containerId = readContainerId();
    if (containerId != null) {
      try {
        killer.kill(containerId);
      } catch (IOException | RuntimeException e) {
        // 容器大概率已自行退出（No such container）；kill 失败不阻断 CLI 清理（FR-011 语义在启动层，
        // 此处是清理路径，宁吞勿漏杀 CLI）
        LOG.warn(
            "docker kill 容器 {} 未成功（按已退出处理）: {}", sanitize(containerId), sanitize(e.getMessage()));
      }
    }
    cliKill.accept(cli);
  }

  private String readContainerId() {
    return readContainerId(cidFile);
  }

  /**
   * 读 cidfile 中的容器 ID（包级静态，DockerProcessStarter 的审计惰性读取器共用）： 文件缺失/空白/读失败均返回
   * null（竞态兜底：容器未及创建即被终止，或早已退出）。
   */
  static String readContainerId(Path cidFile) {
    try {
      if (!Files.exists(cidFile)) {
        return null; // 竞态兜底：容器未及创建即被终止，或早已退出
      }
      String id = Files.readString(cidFile).trim();
      return id.isEmpty() ? null : id;
    } catch (IOException e) {
      LOG.warn(
          "读取 cidfile {} 失败（按无容器处理）: {}", sanitize(cidFile.toString()), sanitize(e.getMessage()));
      return null;
    }
  }

  // ---- 以下全部委托 CLI 进程：流 / 等待 / 句柄（descendants/children 经 toHandle 生效于 CLI 树）----
  @Override
  public OutputStream getOutputStream() {
    return cli.getOutputStream();
  }

  @Override
  public InputStream getInputStream() {
    return cli.getInputStream();
  }

  @Override
  public InputStream getErrorStream() {
    return cli.getErrorStream();
  }

  @Override
  public int waitFor() throws InterruptedException {
    return cli.waitFor();
  }

  @Override
  public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
    return cli.waitFor(timeout, unit);
  }

  @Override
  public int exitValue() {
    return cli.exitValue();
  }

  @Override
  public boolean isAlive() {
    return cli.isAlive();
  }

  @Override
  public ProcessHandle toHandle() {
    return cli.toHandle();
  }

  /** cidfile 内容与异常消息进日志前的净化（防日志伪造，JpaToolInvocationAuditor 同款）。 */
  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
