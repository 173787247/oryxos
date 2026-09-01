package io.oryxos.storage;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** tool_invocations 的写入通道；按 session 查询供测试，按工具名 + 时间窗查询供知识库看板聚合（FR-023）。 */
public interface ToolInvocationRepository extends JpaRepository<ToolInvocation, Long> {

  List<ToolInvocation> findBySessionId(String sessionId);

  List<ToolInvocation> findByToolNameAndCreatedAtBetweenOrderByIdDesc(
      String toolName, java.time.Instant from, java.time.Instant to);

  List<ToolInvocation> findByCreatedAtBetween(java.time.Instant from, java.time.Instant to);

  /** 021：单轮全链路回放——按 trace 取本轮全部工具调用（走 idx_tool_invocations_trace）。 */
  List<ToolInvocation> findByTraceId(String traceId);
}
