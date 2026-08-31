package io.oryxos.web.controller.dto;

import io.oryxos.storage.ToolInvocation;
import java.time.Instant;

/** 工具调用明细（下钻）。 */
public record ToolInvocationView(
    Long id,
    String profileName,
    String toolName,
    boolean success,
    String blockedBy,
    long durationMs,
    Instant createdAt) {

  public static ToolInvocationView from(ToolInvocation t) {
    return new ToolInvocationView(
        t.getId(),
        t.getProfileName(),
        t.getToolName(),
        t.isSuccess(),
        t.getBlockedBy(),
        t.getDurationMs(),
        t.getCreatedAt());
  }
}
