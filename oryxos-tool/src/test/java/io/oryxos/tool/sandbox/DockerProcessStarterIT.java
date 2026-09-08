package io.oryxos.tool.sandbox;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.oryxos.core.agent.ToolExecutionContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * 024 T013：docker 档契约测试（SC-002~005）——需要真实 docker daemon，缺失时整类跳过（assumeTrue， 015 mem0 契约测试模式；CI
 * 默认排除 @Tag("integration")，本地 `-Dgroups=integration` 触发）。
 *
 * <p>验证的是「进程视角的契约」而非 shell 工具语义：容器发行版≠宿主（SC-002）、--rm 零残留、工作区双向可见 （SC-003）、超时 destroyForcibly
 * 终止容器本体无泄漏（SC-004）、默认断网（SC-005）。
 */
@Tag("integration")
class DockerProcessStarterIT {

  /** 契约镜像：小、通用、busybox 自带 cat/touch/ping。 */
  private static final String IMAGE = "alpine:3.20";

  @TempDir Path workspace;

  private static boolean daemonAvailable;

  @BeforeAll
  static void requireDocker() {
    daemonAvailable = probe(List.of("docker", "info"));
    assumeTrue(daemonAvailable, "docker daemon 不可用——契约测试跳过（docker 档行为由单测与 CI 门禁兜底）");
    if (!probe(List.of("docker", "image", "inspect", IMAGE))) {
      assumeTrue(probe(List.of("docker", "pull", "-q", IMAGE)), "契约镜像拉取失败，跳过");
    }
  }

  @AfterEach
  void cleanupContext() {
    ToolExecutionContext.clear();
  }

  private DockerProcessStarter starter() {
    return new DockerProcessStarter(
        new ExecutionBackendProperties("docker", IMAGE, null, null, null, null),
        new WorkspacePathMapper(workspace),
        CidfileProcessWrapper.dockerCliKiller());
  }

  /** 跑完一个容器命令并返回 stdout（约定命令均快速退出）。 */
  private String runToCompletion(List<String> command) throws Exception {
    Process wrapper = starter().start(command);
    String output = new String(wrapper.getInputStream().readAllBytes(), UTF_8);
    wrapper.waitFor(60, TimeUnit.SECONDS);
    return output;
  }

  /** 从 cidfile 轮询读容器 ID（docker 在容器创建后异步写入）。 */
  private String awaitContainerId(Process wrapper, Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      String id = ToolExecutionContext.containerId();
      if (id != null) {
        return id;
      }
      Thread.sleep(50);
    }
    return null;
  }

  @Test
  @Timeout(120)
  @DisplayName("SC-002_容器发行版≠宿主_退出后零残留")
  void containerDistDiffersFromHostAndNoResidue() throws Exception {
    String output = runToCompletion(List.of("cat", "/etc/os-release"));
    assertTrue(output.contains("Alpine"), "应读到容器内发行版: " + output);
    String hostOs = System.getProperty("os.name", "");
    if (hostOs.toLowerCase().contains("linux")) {
      // 同机对照才有意义：宿主 os-release 与容器不同（非 Linux 宿主跳过对照，容器侧已证）
      String host = Files.readString(Path.of("/etc/os-release"), UTF_8);
      assertFalse(host.contains("ID=alpine"), "宿主恰好也是 alpine 时本对照失效，需换镜像");
      assertNotEquals(host, output);
    }
  }

  @Test
  @Timeout(120)
  @DisplayName("SC-002_退出后docker_ps_-a_无该容器残留")
  void noContainerLeftAfterExit() throws Exception {
    Process wrapper = starter().start(List.of("echo", "done"));
    String containerId = awaitContainerId(wrapper, Duration.ofSeconds(10));
    assertTrue(containerId != null && !containerId.isBlank(), "cidfile 应在容器创建后可读");
    wrapper.waitFor(60, TimeUnit.SECONDS);
    // --rm 清理是异步的，稍等轮询
    for (int i = 0; i < 20; i++) {
      if (!containerExists(containerId)) {
        return;
      }
      Thread.sleep(200);
    }
    assertFalse(containerExists(containerId), "--rm 容器退出后必须被清理，残留 ID: " + containerId);
  }

  @Test
  @Timeout(120)
  @DisplayName("SC-003_容器内写workspace_宿主立即可见")
  void workspaceWritesVisibleOnHost() throws Exception {
    runToCompletion(List.of("touch", "/workspace/marker.txt"));
    assertTrue(Files.exists(workspace.resolve("marker.txt")), "容器内 /workspace 写入应落到宿主工作区");
  }

  @Test
  @Timeout(120)
  @DisplayName("SC-004_超时destroyForcibly_容器本体被终止无泄漏")
  void timeoutKillsContainerNotJustCli() throws Exception {
    Process wrapper = starter().start(List.of("sleep", "600"));
    String containerId = awaitContainerId(wrapper, Duration.ofSeconds(10));
    assumeTrue(containerId != null, "cidfile 未及写入（竞态），本次跳过");

    assertFalse(wrapper.waitFor(2, TimeUnit.SECONDS), "sleep 600 不应提前退出");
    wrapper.destroyForcibly(); // 超时路径（ShellTools.killTree 同款调用）

    for (int i = 0; i < 30; i++) {
      if (!containerExists(containerId)) {
        return; // docker kill 生效 + --rm 清理完成
      }
      Thread.sleep(500);
    }
    assertFalse(
        containerExists(containerId), "destroyForcibly 必须终止容器本体（FR-006），泄漏 ID: " + containerId);
  }

  @Test
  @Timeout(120)
  @DisplayName("SC-005_默认network=none_容器内出网失败")
  void defaultNetworkIsNone() throws Exception {
    Process wrapper = starter().start(List.of("ping", "-c", "1", "-W", "2", "192.0.2.1"));
    wrapper.waitFor(60, TimeUnit.SECONDS);
    assertNotEquals(0, wrapper.exitValue(), "--network none 下 ping 必须失败");
  }

  @Test
  @Timeout(120)
  @DisplayName("审计上下文_docker档容器ID就绪")
  void auditContextCarriesContainerId() throws Exception {
    Process wrapper = starter().start(List.of("echo", "audit"));
    String containerId = awaitContainerId(wrapper, Duration.ofSeconds(10));
    assertEquals("docker", ToolExecutionContext.executionBackend());
    assertTrue(containerId != null && !containerId.isBlank(), "审计时点容器 ID 必须可读");
    wrapper.waitFor(60, TimeUnit.SECONDS);
  }

  private static boolean containerExists(String containerId) {
    return probe(List.of("docker", "ps", "-a", "--filter", "id=" + containerId, "-q"))
        && !dockerQuietOutput(List.of("docker", "ps", "-a", "--filter", "id=" + containerId, "-q"))
            .isBlank();
  }

  /** 跑一条命令：exit==0 为 true。 */
  private static boolean probe(List<String> argv) {
    try {
      Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
      process.getInputStream().readAllBytes();
      return process.waitFor() == 0;
    } catch (IOException | InterruptedException e) {
      return false;
    }
  }

  private static String dockerQuietOutput(List<String> argv) {
    try {
      Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
      String output = new String(process.getInputStream().readAllBytes(), UTF_8);
      process.waitFor();
      return output.trim();
    } catch (IOException | InterruptedException e) {
      return "";
    }
  }
}
