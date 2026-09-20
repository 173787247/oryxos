package io.oryxos.core.flow;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Result of executing one Flow node (046 / #468). */
public record FlowNodeOutcome(FlowStepState state, Map<String, Object> outputs, String error) {

  public FlowNodeOutcome {
    state = Objects.requireNonNull(state, "state");
    outputs = outputs == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(outputs));
    error = error == null || error.isBlank() ? null : error.strip();
    if (state == FlowStepState.FAILED && error == null) {
      error = "node failed";
    }
  }

  public static FlowNodeOutcome succeeded(Map<String, Object> outputs) {
    return new FlowNodeOutcome(FlowStepState.SUCCEEDED, outputs, null);
  }

  public static FlowNodeOutcome failed(String error) {
    return new FlowNodeOutcome(FlowStepState.FAILED, Map.of(), error);
  }

  public static FlowNodeOutcome waiting(Map<String, Object> partialOutputs) {
    return new FlowNodeOutcome(FlowStepState.WAITING, partialOutputs, null);
  }

  public boolean waiting() {
    return state == FlowStepState.WAITING;
  }

  public boolean succeeded() {
    return state == FlowStepState.SUCCEEDED;
  }

  public boolean failed() {
    return state == FlowStepState.FAILED;
  }
}
