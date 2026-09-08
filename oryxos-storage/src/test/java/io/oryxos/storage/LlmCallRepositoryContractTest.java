package io.oryxos.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxos.core.agent.TraceContext;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 课件《第16节》验收 harness：LlmCallRepositoryTest——建表必须走手工 schema.sql。 */
@org.springframework.transaction.annotation.Transactional
abstract class LlmCallRepositoryContractTest {

  @Autowired LlmCallRepository repository;

  @Test
  void 手工建表脚本_成功记录写读完整() {
    LlmCall call = new LlmCall();
    call.setSessionId("s-1");
    call.setProvider("deepseek");
    call.setModel("deepseek-chat");
    call.setPromptTokens(120);
    call.setCompletionTokens(80);
    call.setTotalTokens(200);
    call.setSuccess(true);
    call.setDurationMs(1234L);

    repository.saveAndFlush(call);

    LlmCall loaded = repository.findBySessionId("s-1").get(0);
    assertEquals("deepseek", loaded.getProvider());
    assertEquals(200, loaded.getTotalTokens());
    assertTrue(loaded.isSuccess());
    assertNotNull(loaded.getCreatedAt());
  }

  @Test
  void 失败记录_errorMessage列真实存在且完整读回() {
    LlmCall call = new LlmCall();
    call.setSessionId("s-2");
    call.setProvider("kimi");
    call.setModel("moonshot-v1");
    call.setSuccess(false);
    call.setErrorMessage("connect timeout");
    call.setDurationMs(3000L);

    repository.saveAndFlush(call);

    LlmCall loaded = repository.findBySessionId("s-2").get(0);
    assertEquals(false, loaded.isSuccess());
    assertEquals("connect timeout", loaded.getErrorMessage()); // success/error_message 两列真实存在
  }

  @Test
  void trace上下文开启_落库自动携带traceId_接口零改动() {
    JpaLlmCallAuditor auditor = new JpaLlmCallAuditor(repository);
    String traceId;
    try (TraceContext.Scope scope = TraceContext.openIfAbsent()) {
      traceId = scope.traceId();
      // record 签名不变（R2 红线）：trace 由 Jpa 实现从环境自读
      auditor.record("s-trace", "agent", "deepseek", "m", null, null, true, null, 10L);
    }
    LlmCall loaded = repository.findByTraceId(traceId).get(0);
    assertEquals("s-trace", loaded.getSessionId());
    assertEquals(traceId, loaded.getTraceId());
  }

  @Test
  void trace上下文未开启_traceId为null且写入照常_旧行为等价() {
    JpaLlmCallAuditor auditor = new JpaLlmCallAuditor(repository);
    auditor.record("s-no-trace", "agent", "deepseek", "m", null, null, true, null, 10L);

    LlmCall loaded = repository.findBySessionId("s-no-trace").get(0);
    assertEquals(null, loaded.getTraceId()); // 未开启上下文=现状等价，可空列不阻断写入
    assertTrue(loaded.isSuccess());
  }

  @Test
  void 审计写入失败_必须阻断主链路() {
    LlmCallRepository broken = mock(LlmCallRepository.class);
    when(broken.save(any())).thenThrow(new RuntimeException("db locked"));
    JpaLlmCallAuditor auditor = new JpaLlmCallAuditor(broken);

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                auditor.record(
                    "s-3", "agent", "deepseek", "m", null, null, true, null, List.of().size()));
    assertTrue(error.getMessage().contains("llm_calls"));
    assertEquals("db locked", error.getCause().getMessage());
  }
}
