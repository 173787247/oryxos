package io.oryxos.storage;

import io.oryxos.core.agent.ToolInvocationAuditor;
import io.oryxos.core.agent.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ToolInvocationAuditor 的 JPA 实现。
 *
 * <p>审计写入失败时 fail-closed：记录错误并抛出异常，防止主链路在没有审计记录的情况下返回工具结果。
 */
public class JpaToolInvocationAuditor implements ToolInvocationAuditor {

  private static final Logger LOG = LoggerFactory.getLogger(JpaToolInvocationAuditor.class);

  private final ToolInvocationRepository repository;

  public JpaToolInvocationAuditor(ToolInvocationRepository repository) {
    this.repository = repository;
  }

  @Override
  public void record(
      String sessionId,
      String profileName,
      String toolName,
      String inputJson,
      String resultJson,
      boolean success,
      String errorMessage,
      long durationMs) {
    record(
        sessionId,
        profileName,
        toolName,
        inputJson,
        resultJson,
        success,
        errorMessage,
        null,
        durationMs);
  }

  @Override
  public void record(
      String sessionId,
      String profileName,
      String toolName,
      String inputJson,
      String resultJson,
      boolean success,
      String errorMessage,
      String blockedBy,
      long durationMs) {
    record(
        sessionId,
        profileName,
        toolName,
        inputJson,
        resultJson,
        success,
        errorMessage,
        blockedBy,
        null,
        null,
        durationMs);
  }

  /**
   * 真实写入（024 起为最下层实现）：executionBackend 缺省归一化为 {@code "local"}——FR-008「新调用恒写值」： 旧签名路径写
   * local（语义本就为真），历史行保持 NULL（≡ local，D4 查询层兼容）。
   */
  @Override
  public void record(
      String sessionId,
      String profileName,
      String toolName,
      String inputJson,
      String resultJson,
      boolean success,
      String errorMessage,
      String blockedBy,
      String executionBackend,
      String containerId,
      long durationMs) {
    try {
      ToolInvocation record = new ToolInvocation();
      record.setSessionId(sessionId);
      record.setProfileName(profileName);
      record.setToolName(toolName);
      record.setInputJson(inputJson);
      record.setResultJson(resultJson);
      record.setSuccess(success);
      record.setErrorMessage(errorMessage);
      record.setBlockedBy(blockedBy);
      record.setExecutionBackend(executionBackend == null ? "local" : executionBackend);
      record.setContainerId(containerId);
      // 021：trace 走环境读取而非参数传递——Auditor 接口零改动（R2 红线），未开启上下文时为 null
      record.setTraceId(TraceContext.current());
      record.setDurationMs(durationMs);
      repository.save(record);
    } catch (RuntimeException e) {
      LOG.error("tool_invocations 审计写入失败: {}", sanitize(e.getMessage()));
      throw new IllegalStateException("tool_invocations 审计写入失败", e);
    }
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
