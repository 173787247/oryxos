package io.oryxos.cli.command;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 写端的失败纪律与告警内容：文件已落盘/归档后，版本号总线出问题只能告警，不能把命令变成失败。 */
class WorkspaceVersionBumpTest {

  @Test
  @DisplayName("总线连接失败只告警不抛：写路径已经生效，通知失败不得让命令报错")
  void bumpFailureDoesNotThrow() {
    WorkspaceVersionBump bump =
        new WorkspaceVersionBump() {
          @Override
          protected Connection connect() throws SQLException {
            throw new SQLException("database is locked");
          }
        };
    assertDoesNotThrow(() -> bump.bump("agents"));
  }

  @Test
  @DisplayName("SQLite 数据文件尚未创建：静默跳过（总线没有消费者）")
  void missingDatabaseSkipsSilently() {
    WorkspaceVersionBump bump =
        new WorkspaceVersionBump() {
          @Override
          protected Connection connect() {
            return null;
          }
        };
    assertDoesNotThrow(() -> bump.bump("agents"));
  }

  @Test
  @DisplayName("数据库配置解析失败只告警不抛：配置错误也不得让已生效的写命令失败")
  void configParseFailureWarnsButDoesNotThrow() {
    PrintStream original = System.err;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      new WorkspaceVersionBump() {
        @Override
        protected Connection connect() {
          throw new LightDbConfigException(
              "解析 config/application.yml 失败（第 3 行第 5 列）：mapping values are not allowed"
                  + " here；轻命令无法确定数据库，拒绝回落到内置 SQLite。");
        }
      }.bump("agents");
    } finally {
      System.setErr(original);
    }
    String warning = captured.toString(StandardCharsets.UTF_8);
    assertTrue(warning.contains("版本号递增失败"), "应走既有告警路径: " + warning);
    assertTrue(warning.contains("拒绝回落到内置 SQLite"), "告警应带上配置错误原因: " + warning);
  }

  @Test
  @DisplayName("告警必须带可执行的兜底下一步（refresh / 重启），不能只说失败")
  void failureWarningCarriesBreakGlassHint() {
    PrintStream original = System.err;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      new WorkspaceVersionBump() {
        @Override
        protected Connection connect() throws SQLException {
          throw new SQLException("database is locked");
        }
      }.bump("agents");
    } finally {
      System.setErr(original);
    }
    String warning = captured.toString(StandardCharsets.UTF_8);
    assertTrue(warning.contains("/api/v1/workspace/refresh"), "必须给出可执行的刷新入口: " + warning);
    assertTrue(warning.contains("重启"), "必须给出重启兜底: " + warning);
  }
}
