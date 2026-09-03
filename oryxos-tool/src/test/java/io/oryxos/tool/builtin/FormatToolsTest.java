package io.oryxos.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FormatToolsTest {

  @Test
  @DisplayName("format_sql 输出 Markdown 表头与分隔线")
  void formatSqlMarkdown() {
    FormatTools tools = new FormatTools(mock(Sandbox.class));
    String out =
        tools.formatSql("{\"headers\":[\"a\",\"b\"],\"rows\":[[\"1\",\"2\"],[\"3\",\"4\"]]}");
    assertTrue(out.contains("| a | b |"));
    assertTrue(out.contains("| --- | --- |"));
    assertTrue(out.contains("| 1 | 2 |"));
  }

  @Test
  @DisplayName("export_excel 写前 enforce FILE_WRITE；拒绝时不写盘")
  void exportExcelEnforcesSandbox() {
    Sandbox sandbox = mock(Sandbox.class);
    doThrow(new IllegalStateException("path not allowed"))
        .when(sandbox)
        .enforce(any(SandboxAction.class));
    FormatTools tools = new FormatTools(sandbox);
    String out =
        tools.exportExcel(
            "{\"file_path\":\"C:\\\\tmp\\\\out.xlsx\",\"headers\":[\"a\"],\"rows\":[[\"1\"]]}");
    assertTrue(out.startsWith("导出失败"));
    verify(sandbox).enforce(any(SandboxAction.class));
  }
}
