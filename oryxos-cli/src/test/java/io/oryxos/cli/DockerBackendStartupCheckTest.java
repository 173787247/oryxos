package io.oryxos.cli;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.tool.sandbox.ExecutionBackendProperties;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 024 T011：docker 档启动校验——local 档零检查、CLI/daemon/镜像三级 fail loud（镜像缺失可经预拉取自愈）。 探针注入桩（镜像
 * ToolPolicyStartupCheckTest 的注入式模式）。
 */
class DockerBackendStartupCheckTest {

  /** 记录调用 + 按命令前缀回放结果的桩探针。 */
  private static final class StubProbe implements DockerBackendStartupCheck.CommandProbe {
    final List<List<String>> calls = new java.util.ArrayList<>();
    final Map<String, String> failures = new HashMap<>(); // 命令首词组 → 失败诊断（null=通过）

    @Override
    public String probe(List<String> argv) {
      calls.add(argv);
      String key = String.join(" ", argv);
      for (Map.Entry<String, String> failure : failures.entrySet()) {
        if (key.startsWith(failure.getKey())) {
          return failure.getValue();
        }
      }
      return null;
    }
  }

  private static ExecutionBackendProperties dockerProps() {
    return new ExecutionBackendProperties("docker", "alpine:3.20", null, null, null, null);
  }

  private static ExecutionBackendProperties localProps() {
    return new ExecutionBackendProperties(null, null, null, null, null, null);
  }

  @Test
  @DisplayName("local档_零检查零开销")
  void localBackendSkipsEntirely() {
    StubProbe probe = new StubProbe();
    new DockerBackendStartupCheck(localProps(), probe).run(null);
    assertTrue(probe.calls.isEmpty(), "local 档不得发起任何探测");
  }

  @Test
  @DisplayName("docker档_全通过_无异常")
  void allHealthyPasses() {
    StubProbe probe = new StubProbe();
    assertDoesNotThrow(() -> new DockerBackendStartupCheck(dockerProps(), probe).run(null));
    assertTrue(probe.calls.size() >= 3, "至少探测 CLI/daemon/镜像三项");
  }

  @Test
  @DisplayName("CLI缺失_fail_loud含PATH提示")
  void missingCliFailsLoud() {
    StubProbe probe = new StubProbe();
    probe.failures.put("docker --version", "CreateProcess error=2");
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> new DockerBackendStartupCheck(dockerProps(), probe).run(null));
    assertTrue(e.getMessage().contains("PATH"));
  }

  @Test
  @DisplayName("daemon不可达_fail_loud含排障")
  void daemonDownFailsLoud() {
    StubProbe probe = new StubProbe();
    probe.failures.put("docker info", "Cannot connect to the Docker daemon");
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> new DockerBackendStartupCheck(dockerProps(), probe).run(null));
    assertTrue(e.getMessage().contains("daemon"));
  }

  @Test
  @DisplayName("镜像缺失_预拉取成功_自愈通过")
  void missingImageHealsViaPull() {
    StubProbe probe = new StubProbe();
    probe.failures.put("docker image inspect", "No such image");
    assertDoesNotThrow(() -> new DockerBackendStartupCheck(dockerProps(), probe).run(null));
    assertTrue(probe.calls.stream().anyMatch(argv -> argv.contains("pull")), "inspect 失败后必须尝试预拉取");
  }

  @Test
  @DisplayName("镜像缺失且拉取失败_fail_loud含镜像名")
  void missingImagePullFailureFailsLoud() {
    StubProbe probe = new StubProbe();
    probe.failures.put("docker image inspect", "No such image");
    probe.failures.put("docker pull", "manifest unknown");
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> new DockerBackendStartupCheck(dockerProps(), probe).run(null));
    assertTrue(e.getMessage().contains("alpine:3.20"));
  }

  @Test
  @DisplayName("探针IOException_包装为启动失败")
  void probeIoErrorWraps() {
    DockerBackendStartupCheck.CommandProbe ioProbe =
        argv -> {
          throw new IOException("broken pipe");
        };
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> new DockerBackendStartupCheck(dockerProps(), ioProbe).run(null));
    assertEquals("docker 执行后端启动校验失败: broken pipe", e.getMessage());
  }
}
