package io.oryxos.core.cost;

import io.oryxos.core.agent.ToolInvocationAuditor;
import java.util.Objects;

/** Decorates ToolInvocationAuditor: after write, append tool cost to ledger when enabled. */
public final class CostAwareToolInvocationAuditor implements ToolInvocationAuditor {

  private final ToolInvocationAuditor delegate;
  private final CostLedgerService ledger;

  public CostAwareToolInvocationAuditor(ToolInvocationAuditor delegate, CostLedgerService ledger) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.ledger = ledger;
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
    delegate.record(
        sessionId, profileName, toolName, inputJson, resultJson, success, errorMessage, durationMs);
    after(sessionId, profileName, toolName, durationMs);
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
    delegate.record(
        sessionId,
        profileName,
        toolName,
        inputJson,
        resultJson,
        success,
        errorMessage,
        blockedBy,
        durationMs);
    after(sessionId, profileName, toolName, durationMs);
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
      String executionBackend,
      String containerId,
      long durationMs) {
    delegate.record(
        sessionId,
        profileName,
        toolName,
        inputJson,
        resultJson,
        success,
        errorMessage,
        blockedBy,
        executionBackend,
        containerId,
        durationMs);
    after(sessionId, profileName, toolName, durationMs);
  }

  private void after(String sessionId, String profileName, String toolName, long durationMs) {
    if (ledger == null || !ledger.isEnabled()) {
      return;
    }
    ledger.recordTool(sessionId, profileName, toolName, durationMs);
  }
}
