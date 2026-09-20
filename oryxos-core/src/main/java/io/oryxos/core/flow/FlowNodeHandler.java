package io.oryxos.core.flow;

import java.util.Map;

/**
 * Pluggable node executor (046 / #468). Engine resolves inputs, then delegates here.
 *
 * <p>{@link FlowNodeType#HUMAN} / {@link FlowNodeType#APPROVAL} should typically return {@link
 * FlowNodeOutcome#waiting} until {@link FlowEngine#completeWaiting} supplies outputs (#469 owns
 * full HITL UX).
 */
@FunctionalInterface
public interface FlowNodeHandler {

  FlowNodeOutcome execute(FlowNode node, Map<String, Object> inputs, FlowRun run);
}
