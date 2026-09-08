package io.oryxos.tool.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 024 T007：destroy 联动 docker kill 的四个分支——有 ID 则 kill、无 ID 只杀 CLI、kill 抛异常不阻断 CLI
 * 清理、流与等待委托。FR-006（超时必须终止容器本体）的机制核心。
 */
class CidfileProcessWrapperTest {

  @TempDir Path tempDir;

  /** 记录型 killer：可断言调用并可注入故障。 */
  private static final class RecordingKiller implements CidfileProcessWrapper.ContainerKiller {
    final List<String> killed = new ArrayList<>();
    boolean fail;

    @Override
    public void kill(String containerId) throws IOException {
      if (fail) {
        throw new IOException("No such container: " + containerId);
      }
      killed.add(containerId);
    }
  }

  /** 最小 CLI 假进程：只记录 destroy/destroyForcibly，其余桩实现。 */
  private static final class FakeCliProcess extends Process {
    boolean destroyed;
    boolean forciblyDestroyed;

    @Override
    public OutputStream getOutputStream() {
      return OutputStream.nullOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return InputStream.nullInputStream();
    }

    @Override
    public InputStream getErrorStream() {
      return InputStream.nullInputStream();
    }

    @Override
    public int waitFor() {
      return 0;
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) {
      return true;
    }

    @Override
    public int exitValue() {
      return 0;
    }

    @Override
    public void destroy() {
      destroyed = true;
    }

    @Override
    public Process destroyForcibly() {
      forciblyDestroyed = true;
      return this;
    }
  }

  @Test
  @DisplayName("destroy_有容器ID_先docker_kILL再杀CLI")
  void destroyKillsContainerThenCli() throws IOException {
    Path cidFile = tempDir.resolve("cid");
    Files.writeString(cidFile, "abc123def\n");
    RecordingKiller killer = new RecordingKiller();
    FakeCliProcess cli = new FakeCliProcess();

    new CidfileProcessWrapper(cli, cidFile, killer).destroy();

    assertEquals(List.of("abc123def"), killer.killed);
    assertTrue(cli.destroyed);
    assertFalse(cli.forciblyDestroyed);
  }

  @Test
  @DisplayName("destroyForcibly_有容器ID_同样联动kill")
  void destroyForciblyKillsContainerThenCli() throws IOException {
    Path cidFile = tempDir.resolve("cid");
    Files.writeString(cidFile, "abc123def");
    RecordingKiller killer = new RecordingKiller();
    FakeCliProcess cli = new FakeCliProcess();

    new CidfileProcessWrapper(cli, cidFile, killer).destroyForcibly();

    assertEquals(List.of("abc123def"), killer.killed);
    assertTrue(cli.forciblyDestroyed);
  }

  @Test
  @DisplayName("cidfile缺失_竞态兜底_只杀CLI不调kill")
  void missingCidfileFallsBackToCliOnly() {
    Path cidFile = tempDir.resolve("never-written");
    RecordingKiller killer = new RecordingKiller();
    FakeCliProcess cli = new FakeCliProcess();

    new CidfileProcessWrapper(cli, cidFile, killer).destroyForcibly();

    assertTrue(killer.killed.isEmpty());
    assertTrue(cli.forciblyDestroyed);
  }

  @Test
  @DisplayName("cidfile空白_等价缺失")
  void blankCidfileTreatedAsMissing() throws IOException {
    Path cidFile = tempDir.resolve("cid");
    Files.writeString(cidFile, "   ");
    RecordingKiller killer = new RecordingKiller();

    new CidfileProcessWrapper(new FakeCliProcess(), cidFile, killer).destroy();

    assertTrue(killer.killed.isEmpty());
  }

  @Test
  @DisplayName("docker_kill抛异常_不阻断CLI清理")
  void killerFailureStillKillsCli() throws IOException {
    Path cidFile = tempDir.resolve("cid");
    Files.writeString(cidFile, "abc123def");
    RecordingKiller killer = new RecordingKiller();
    killer.fail = true;
    FakeCliProcess cli = new FakeCliProcess();

    new CidfileProcessWrapper(cli, cidFile, killer).destroyForcibly();

    assertTrue(cli.forciblyDestroyed);
  }

  @Test
  @DisplayName("流与等待委托CLI进程")
  void delegatesStreamsAndWaiting() throws Exception {
    FakeCliProcess cli = new FakeCliProcess();
    CidfileProcessWrapper wrapper =
        new CidfileProcessWrapper(cli, tempDir.resolve("cid"), new RecordingKiller());

    assertTrue(wrapper.waitFor(1, TimeUnit.SECONDS));
    assertEquals(0, wrapper.exitValue());
    assertFalse(wrapper.isAlive()); // FakeCliProcess 未覆写 isAlive，默认 false
  }
}
