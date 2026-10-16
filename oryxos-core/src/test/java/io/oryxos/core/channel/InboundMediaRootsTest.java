package io.oryxos.core.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InboundMediaRootsTest {

  private static final String CHANNEL = "ops-wecom";
  private static final String TEMP_PREFIX = "oryxos-wecom-media-";

  @TempDir Path workspaceRoot;

  @Test
  @DisplayName("落盘根取注入的工作区根，与 cwd 无关")
  void resolvesUnderInjectedWorkspaceRoot() {
    Path mediaRoot = InboundMediaRoots.forChannel(workspaceRoot, CHANNEL, TEMP_PREFIX);

    assertEquals(workspaceRoot.resolve("inbound-media").resolve(CHANNEL), mediaRoot);
    assertTrue(mediaRoot.startsWith(workspaceRoot), "媒体目录必须落在注入的根之下：" + mediaRoot);
    assertTrue(Files.isDirectory(mediaRoot), "媒体目录必须已创建：" + mediaRoot);
    // 注入的根是临时目录，按 cwd 拼出来的根不可能与它相同 —— 这条断言正是缺陷形态的反例。
    assertNotEquals(
        Path.of(System.getProperty("user.dir"), ".oryxos", "inbound-media", CHANNEL), mediaRoot);
  }

  @Test
  @DisplayName("渠道名只作末级目录名：穿越形态仍在根之下")
  void channelNameCannotEscapeWorkspaceRoot() {
    Path mediaRoot = InboundMediaRoots.forChannel(workspaceRoot, "../..", TEMP_PREFIX);

    assertTrue(mediaRoot.startsWith(workspaceRoot), "渠道名不得把媒体目录带出根：" + mediaRoot);
    assertTrue(Files.isDirectory(mediaRoot));
  }

  @Test
  @DisplayName("首选目录建不出来时回退临时目录，且同一渠道复用同一目录")
  void fallsBackWithoutCreatingADirectoryPerCall() throws IOException {
    // 用一个普通文件冒充工作区根：createDirectories(<文件>/inbound-media/...) 必然 IOException。
    Path notADirectory = Files.createFile(workspaceRoot.resolve("workspace-root-is-a-file"));

    Path first = InboundMediaRoots.forChannel(notADirectory, CHANNEL, TEMP_PREFIX);
    Path second = InboundMediaRoots.forChannel(notADirectory, CHANNEL, TEMP_PREFIX);

    assertTrue(first.startsWith(Path.of(System.getProperty("java.io.tmpdir"))), "回退位置：" + first);
    assertEquals(CHANNEL, first.getFileName().toString());
    assertTrue(Files.isDirectory(first));
    assertEquals(first, second, "同一渠道的回退目录必须稳定，不能每次调用新建一个随机临时目录");
  }
}
