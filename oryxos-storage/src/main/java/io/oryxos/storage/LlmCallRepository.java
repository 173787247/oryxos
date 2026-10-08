package io.oryxos.storage;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** llm_calls 的写入通道；核心阶段只写不查，按 session 查询仅供测试与后续扩展。 */
public interface LlmCallRepository extends JpaRepository<LlmCall, Long> {

  List<LlmCall> findBySessionId(String sessionId);

  List<LlmCall> findByCreatedAtBetween(java.time.Instant from, java.time.Instant to);

  /** 021：单轮全链路回放——按 trace 取本轮全部 LLM 调用（走 idx_llm_calls_trace）。 */
  List<LlmCall> findByTraceId(String traceId);

  /** 成本对账（#476）：按 trace 在库里求和，不为一条 trace 把整张 llm_calls 读进内存。 */
  @Query("select coalesce(sum(c.costMicros), 0) from LlmCall c where c.traceId = :traceId")
  long sumCostMicrosByTraceId(@Param("traceId") String traceId);
}
