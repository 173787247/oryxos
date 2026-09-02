package io.oryxos.tool.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 024 T006：docker argv 构造钉死——参数顺序、默认安全参数全在场（SC-005 的第一道防线）、 自定义覆写、工作区路径翻译、必填校验 fail loud。 */
class DockerRunSpecTest {

  @TempDir Path tempDir;

  private WorkspacePathMapper mapper() {
    return new WorkspacePathMapper(tempDir.resolve(".oryxos"));
  }

  private ExecutionBackendProperties props() {
    return new ExecutionBackendProperties(null, "python:3.12-alpine", null, null, null, null);
  }

  @Test
  @DisplayName("默认参数_完整argv顺序与安全参数钉死")
  void defaultArgvPinned() {
    // cidfile 以 Path.toString() 进 argv（随宿主平台分隔符），期望值同源构造保持平台中立
    Path cidFile = Path.of("/tmp", "cid");
    List<String> argv =
        DockerRunSpec.build(props(), mapper(), cidFile, List.of("python3", "main.py"));

    assertEquals(
        List.of(
            "docker",
            "run",
            "--rm",
            "--cidfile",
            cidFile.toString(),
            "-v",
            mapper().workspaceRoot() + ":/workspace",
            "--network",
            "none",
            "--memory",
            "512m",
            "--cpus",
            "1.0",
            "--read-only",
            "--tmpfs",
            "/tmp",
            "--user",
            "65534:65534",
            "python:3.12-alpine",
            "python3",
            "main.py"),
        argv);
  }

  @Test
  @DisplayName("自定义覆写_网络限额镜像生效_未覆写项保持默认")
  void overridesTakeEffect() {
    ExecutionBackendProperties props =
        new ExecutionBackendProperties(
            "docker", "alpine:3.20", "1g", "2.0", "default", "1000:1000");
    List<String> argv =
        DockerRunSpec.build(props, mapper(), Path.of("/tmp/cid"), List.of("sh", "-c", "ls"));

    assertTrue(argv.contains("--network"));
    assertEquals(argv.get(argv.indexOf("--network") + 1), "default");
    assertEquals(argv.get(argv.indexOf("--memory") + 1), "1g");
    assertEquals(argv.get(argv.indexOf("--cpus") + 1), "2.0");
    assertEquals(argv.get(argv.indexOf("--user") + 1), "1000:1000");
    assertEquals("alpine:3.20", argv.get(argv.indexOf("--user") + 2));
  }

  @Test
  @DisplayName("工作区路径参数_翻译为_/workspace")
  void workspaceArgsTranslated() {
    WorkspacePathMapper mapper = mapper();
    String script = mapper.workspaceRoot().resolve("scripts").resolve("run.py").toString();
    List<String> argv =
        DockerRunSpec.build(props(), mapper, Path.of("/tmp/cid"), List.of("python3", script));

    assertTrue(argv.contains("/workspace/scripts/run.py"));
    assertTrue(!argv.contains(script)); // 宿主路径不得漏进容器参数
  }

  @Test
  @DisplayName("非工作区路径_直通不翻译")
  void nonWorkspaceArgsPassThrough() {
    List<String> argv =
        DockerRunSpec.build(props(), mapper(), Path.of("/tmp/cid"), List.of("ls", "/etc"));

    assertTrue(argv.contains("/etc"));
  }

  @Test
  @DisplayName("镜像未配_fail_loud")
  void missingImageFailsLoud() {
    ExecutionBackendProperties noImage =
        new ExecutionBackendProperties("docker", null, null, null, null, null);
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> DockerRunSpec.build(noImage, mapper(), Path.of("/tmp/cid"), List.of("ls")));
    assertTrue(e.getMessage().contains("oryxos.sandbox.execution.image"));
  }

  @Test
  @DisplayName("空命令_fail_loud")
  void emptyCommandFailsLoud() {
    assertThrows(
        IllegalArgumentException.class,
        () -> DockerRunSpec.build(props(), mapper(), Path.of("/tmp/cid"), List.of()));
  }
}
