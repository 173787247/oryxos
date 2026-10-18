package io.oryxos.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/** 第17节 harness 补充：ToolInvocationRepositoryTest——建表必须走手工 schema.sql（同 16 节口径）。 */
@org.springframework.transaction.annotation.Transactional
abstract class ToolInvocationRepositoryContractTest {

  @Autowired ToolInvocationRepository repository;

  @Test
  @DisplayName("手工建表脚本_成功记录写读完整")
  void manualSchemaPersistsSuccessRecordCompletely() {
    ToolInvocation invocation = new ToolInvocation();
    invocation.setSessionId("s-1");
    invocation.setToolName("http_get");
    invocation.setInputJson("{\"url\":\"https://wttr.in\"}");
    invocation.setResultJson("晴，28°C");
    invocation.setSuccess(true);
    invocation.setDurationMs(345L);

    repository.saveAndFlush(invocation);

    ToolInvocation loaded = repository.findBySessionId("s-1").get(0);
    assertEquals("http_get", loaded.getToolName());
    assertEquals("晴，28°C", loaded.getResultJson());
    assertTrue(loaded.isSuccess());
    assertNotNull(loaded.getCreatedAt());
  }

  @Test
  @DisplayName("失败记录_success与error_message两列真实存在且完整读回")
  void failureRecordKeepsSuccessFlagAndErrorMessage() {
    ToolInvocation invocation = new ToolInvocation();
    invocation.setSessionId("s-2");
    invocation.setToolName("shell");
    invocation.setInputJson("{\"command\":\"uptime\"}");
    invocation.setSuccess(false);
    invocation.setErrorMessage("命令不在白名单");
    invocation.setDurationMs(12L);

    repository.saveAndFlush(invocation);

    ToolInvocation loaded = repository.findBySessionId("s-2").get(0);
    assertFalse(loaded.isSuccess());
    assertEquals("命令不在白名单", loaded.getErrorMessage()); // 失败也要记，事后可查
  }

  @Test
  @DisplayName("审计写入失败_必须阻断工具结果返回")
  void auditFailureStopsToolResultFromReturning() {
    ToolInvocationRepository broken = mock(ToolInvocationRepository.class);
    when(broken.save(any())).thenThrow(new RuntimeException("db locked"));
    JpaToolInvocationAuditor auditor = new JpaToolInvocationAuditor(broken);

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> auditor.record("s-3", "agent", "http_get", "{}", null, true, null, 1L));
    assertTrue(error.getMessage().contains("tool_invocations"));
    assertEquals("db locked", error.getCause().getMessage());
  }

  @Test
  @DisplayName("016 聚合：窗口内 count/成功率原料/平均耗时一行算完；空窗口归一")
  void auditTotalsAggregateTheWindow() {
    Instant now = Instant.now();
    saveInvocation("agg-1", "read_file", null, null, true, 10);
    saveInvocation("agg-2", "shell", "policy", "local", false, 50);
    saveInvocation("agg-3", "http_get", null, "docker", true, 30);
    repository.flush();

    ToolAuditTotals totals = repository.auditTotals(now.minusSeconds(3600), now.plusSeconds(3600));
    assertEquals(3L, totals.totalCount().longValue());
    assertEquals(2L, totals.successCount().longValue());
    assertEquals(30.0, totals.avgDurationMs(), 0.0001);

    ToolAuditTotals empty = repository.auditTotals(Instant.EPOCH, Instant.EPOCH.plusSeconds(1));
    assertEquals(0L, empty.totalCount().longValue());
    assertEquals(null, empty.successCount());
    assertEquals(null, empty.avgDurationMs());
  }

  @Test
  @DisplayName("016 分组：按工具名 GROUP BY，count 降序")
  void auditGroupsByNameGroupsAndCounts() {
    Instant now = Instant.now();
    saveInvocation("g-1", "read_file", null, null, true, 10);
    saveInvocation("g-2", "read_file", null, null, false, 10);
    saveInvocation("g-3", "shell", null, null, true, 10);
    repository.flush();

    List<ToolAuditGroup> groups =
        repository.auditGroupsByName(now.minusSeconds(3600), now.plusSeconds(3600));

    assertEquals(2, groups.size());
    assertEquals("read_file", groups.get(0).key());
    assertEquals(2L, groups.get(0).totalCount().longValue());
    assertEquals(1L, groups.get(0).successCount().longValue());
    assertEquals("shell", groups.get(1).key());
  }

  @Test
  @DisplayName("016 明细：窗口 + 两个过滤 + 排序 + 截断都在 SQL")
  void findRecentInWindowFiltersAndLimits() {
    Instant now = Instant.now();
    saveInvocation("r-1", "read_file", "policy", "local", false, 10);
    saveInvocation("r-2", "shell", null, "docker", true, 10);
    saveInvocation("r-3", "http_get", null, null, true, 10);
    repository.flush();
    Instant from = now.minusSeconds(3600);
    Instant to = now.plusSeconds(3600);

    assertEquals(
        3, repository.findRecentInWindow(from, to, null, null, PageRequest.of(0, 10)).size());
    assertEquals(
        2,
        repository.findRecentInWindow(from, to, null, null, PageRequest.of(0, 2)).size(),
        "LIMIT 由 SQL 执行");
    assertEquals(
        1, repository.findRecentInWindow(from, to, "policy", null, PageRequest.of(0, 10)).size());
    assertEquals(
        1, repository.findRecentInWindow(from, to, null, "docker", PageRequest.of(0, 10)).size());
    assertEquals(
        0,
        repository.findRecentInWindow(from, to, "policy", "docker", PageRequest.of(0, 10)).size(),
        "两个过滤同时生效");

    List<ToolInvocation> all =
        repository.findRecentInWindow(from, to, null, null, PageRequest.of(0, 10));
    assertTrue(all.get(0).getId() > all.get(all.size() - 1).getId(), "窗口内按 createdAt/id 倒序");
    assertTrue(
        repository
            .findRecentInWindow(
                Instant.EPOCH, Instant.EPOCH.plusSeconds(1), null, null, PageRequest.of(0, 10))
            .isEmpty(),
        "窗口外的行不得出现");
  }

  private void saveInvocation(
      String sessionId,
      String toolName,
      String blockedBy,
      String executionBackend,
      boolean success,
      long durationMs) {
    ToolInvocation invocation = new ToolInvocation();
    invocation.setSessionId(sessionId);
    invocation.setToolName(toolName);
    invocation.setSuccess(success);
    invocation.setDurationMs(durationMs);
    invocation.setBlockedBy(blockedBy);
    invocation.setExecutionBackend(executionBackend);
    repository.saveAndFlush(invocation);
  }
}
