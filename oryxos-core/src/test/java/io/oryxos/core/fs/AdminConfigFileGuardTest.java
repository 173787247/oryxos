package io.oryxos.core.fs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdminConfigFileGuardTest {

  @TempDir Path temp;

  @Test
  @DisplayName("拒绝 channels.yaml / mcp_servers.yaml 直写（大小写不敏感）")
  void rejectsReservedConfigFiles() {
    assertThrows(
        IllegalArgumentException.class,
        () -> AdminConfigFileGuard.rejectMutation(".oryxos/channels.yaml"));
    assertThrows(
        IllegalArgumentException.class, () -> AdminConfigFileGuard.rejectMutation("Channels.YAML"));
    assertThrows(
        IllegalArgumentException.class,
        () -> AdminConfigFileGuard.rejectMutation(".oryxos/mcp_servers.yaml"));
    assertThrows(
        IllegalArgumentException.class,
        () -> AdminConfigFileGuard.rejectMutation("MCP_SERVERS.yaml"));
  }

  @Test
  @DisplayName("拒绝经保留文件名建子路径（防目录占位）")
  void rejectsAncestorPathViaReservedName() {
    assertThrows(
        IllegalArgumentException.class,
        () -> AdminConfigFileGuard.rejectMutation(".oryxos/channels.yaml/child.txt"));
    assertThrows(
        IllegalArgumentException.class,
        () -> AdminConfigFileGuard.rejectMutation("mcp_servers.yaml/nested/x.yml"));
  }

  @Test
  @DisplayName("普通路径放行")
  void allowsOrdinaryPaths() {
    assertDoesNotThrow(() -> AdminConfigFileGuard.rejectMutation("agents/demo/notes.md"));
    assertDoesNotThrow(() -> AdminConfigFileGuard.rejectMutation("channels.yaml.bak"));
    assertDoesNotThrow(() -> AdminConfigFileGuard.rejectMutation((String) null));
    assertDoesNotThrow(() -> AdminConfigFileGuard.rejectMutation("  "));
    assertDoesNotThrow(() -> AdminConfigFileGuard.rejectMutation((Path) null));
  }

  @Test
  @DisplayName("软链叶子指向 channels.yaml 时拒绝")
  void rejectsSymlinkLeafToChannelsYaml() throws IOException {
    Path reserved = temp.resolve("channels.yaml");
    Files.writeString(reserved, "channels: []\n");
    Path alias = temp.resolve("alias.yaml");
    assumeCanSymlink(alias, reserved);

    assertThrows(IllegalArgumentException.class, () -> AdminConfigFileGuard.rejectMutation(alias));
    assertThrows(
        IllegalArgumentException.class,
        () -> AdminConfigFileGuard.rejectMutation(alias.toString()));
  }

  @Test
  @DisplayName("悬空软链目标词法为 mcp_servers.yaml 时也拒绝")
  void rejectsDanglingSymlinkNamedMcpServers() throws IOException {
    Path alias = temp.resolve("alias.yaml");
    assumeCanSymlink(alias, Path.of("mcp_servers.yaml"));

    assertThrows(IllegalArgumentException.class, () -> AdminConfigFileGuard.rejectMutation(alias));
  }

  @Test
  @DisplayName("读侧拒绝 channels.yaml / mcp_servers.yaml / oryxos.db 原文（大小写不敏感）")
  void rejectReadBlocksReservedFiles() {
    assertThrows(
        IllegalArgumentException.class, () -> AdminConfigFileGuard.rejectRead("channels.yaml"));
    assertThrows(
        IllegalArgumentException.class,
        () -> AdminConfigFileGuard.rejectRead(".oryxos/Channels.YAML"));
    assertThrows(
        IllegalArgumentException.class,
        () -> AdminConfigFileGuard.rejectRead(".oryxos/mcp_servers.yaml"));
    assertThrows(
        IllegalArgumentException.class, () -> AdminConfigFileGuard.rejectRead("ORYXOS.DB"));
  }

  @Test
  @DisplayName("读侧拒绝 SQLite 侧车/备份（-wal/-shm/-journal/.bak 同源数据）")
  void rejectReadBlocksSqliteSidecars() {
    assertThrows(
        IllegalArgumentException.class, () -> AdminConfigFileGuard.rejectRead("oryxos.db-wal"));
    assertThrows(
        IllegalArgumentException.class, () -> AdminConfigFileGuard.rejectRead("oryxos.db-shm"));
    assertThrows(
        IllegalArgumentException.class, () -> AdminConfigFileGuard.rejectRead("oryxos.db-journal"));
    assertThrows(
        IllegalArgumentException.class, () -> AdminConfigFileGuard.rejectRead("oryxos.db.bak"));
  }

  @Test
  @DisplayName("读侧普通路径放行；配置名 .bak 后缀不拦，DB 家族前缀仍拦")
  void rejectReadAllowsOrdinaryPaths() {
    assertDoesNotThrow(() -> AdminConfigFileGuard.rejectRead("agents/demo/notes.md"));
    assertDoesNotThrow(() -> AdminConfigFileGuard.rejectRead("channels.yaml.bak"));
    assertDoesNotThrow(() -> AdminConfigFileGuard.rejectRead("output/oryxos-report.md"));
    assertDoesNotThrow(() -> AdminConfigFileGuard.rejectRead((String) null));
    assertDoesNotThrow(() -> AdminConfigFileGuard.rejectRead("  "));
    assertDoesNotThrow(() -> AdminConfigFileGuard.rejectRead((Path) null));
  }

  @Test
  @DisplayName("写侧同步拒绝 oryxos.db 家族（自定义布局下防通用写入口毁库）")
  void rejectMutationBlocksDbFamily() {
    assertThrows(
        IllegalArgumentException.class, () -> AdminConfigFileGuard.rejectMutation("oryxos.db"));
    assertThrows(
        IllegalArgumentException.class,
        () -> AdminConfigFileGuard.rejectMutation(".oryxos/oryxos.db-wal"));
  }

  @Test
  @DisplayName("读侧拒绝经软链别名读 channels.yaml（词法 + 叶子目标双重）")
  void rejectReadBlocksSymlinkAliasToChannelsYaml() throws IOException {
    Path reserved = temp.resolve("channels.yaml");
    Files.writeString(reserved, "channels: []\n");
    Path alias = temp.resolve("alias.yaml");
    assumeCanSymlink(alias, reserved);

    assertThrows(IllegalArgumentException.class, () -> AdminConfigFileGuard.rejectRead(alias));
    assertThrows(
        IllegalArgumentException.class, () -> AdminConfigFileGuard.rejectRead(alias.toString()));
  }

  @Test
  @DisplayName("读侧拒绝悬空软链指向 mcp_servers.yaml")
  void rejectReadBlocksDanglingSymlinkToMcpServers() throws IOException {
    Path alias = temp.resolve("alias.yaml");
    assumeCanSymlink(alias, Path.of("mcp_servers.yaml"));

    assertThrows(IllegalArgumentException.class, () -> AdminConfigFileGuard.rejectRead(alias));
  }

  @Test
  @DisplayName("isReservedRead 判定与 rejectRead 同口径")
  void isReservedReadMatchesThrowingCheck() throws IOException {
    org.junit.jupiter.api.Assertions.assertTrue(
        AdminConfigFileGuard.isReservedRead(temp.resolve("mcp_servers.yaml")));
    org.junit.jupiter.api.Assertions.assertTrue(
        AdminConfigFileGuard.isReservedRead(temp.resolve("oryxos.db")));
    org.junit.jupiter.api.Assertions.assertFalse(
        AdminConfigFileGuard.isReservedRead(temp.resolve("agents/demo/notes.md")));
    org.junit.jupiter.api.Assertions.assertFalse(AdminConfigFileGuard.isReservedRead(null));

    Path reserved = temp.resolve("channels.yaml");
    Files.writeString(reserved, "channels: []\n");
    Path alias = temp.resolve("alias.yaml");
    try {
      Files.createSymbolicLink(alias, reserved);
    } catch (IOException | UnsupportedOperationException e) {
      assumeTrue(false, "当前环境无法创建软链: " + e.getMessage());
    }
    org.junit.jupiter.api.Assertions.assertTrue(AdminConfigFileGuard.isReservedRead(alias));
  }

  private static void assumeCanSymlink(Path link, Path target) {
    try {
      Files.createSymbolicLink(link, target);
    } catch (IOException | UnsupportedOperationException e) {
      assumeTrue(false, "当前环境无法创建软链: " + e.getMessage());
    }
  }
}
