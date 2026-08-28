package io.oryxos.core.fs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdminConfigFileGuardTest {

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
    assertDoesNotThrow(() -> AdminConfigFileGuard.rejectMutation(null));
    assertDoesNotThrow(() -> AdminConfigFileGuard.rejectMutation("  "));
  }
}
