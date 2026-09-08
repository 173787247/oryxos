package io.oryxos.web.controller.dto;

import io.oryxos.storage.LlmCall;
import java.time.Instant;

/** LLM 调用明细（下钻）；traceId（021）为行级 trace 维度入口，旧行为 null。 */
public record LlmCallView(
    Long id,
    String profileName,
    String provider,
    String model,
    Integer promptTokens,
    Integer completionTokens,
    Integer totalTokens,
    Long costMicros,
    boolean success,
    long durationMs,
    Instant createdAt,
    String traceId) {

  public static LlmCallView from(LlmCall c) {
    return new LlmCallView(
        c.getId(),
        c.getProfileName(),
        c.getProvider(),
        c.getModel(),
        c.getPromptTokens(),
        c.getCompletionTokens(),
        c.getTotalTokens(),
        c.getCostMicros(),
        c.isSuccess(),
        c.getDurationMs(),
        c.getCreatedAt(),
        c.getTraceId());
  }
}
