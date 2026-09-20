package io.oryxos.core.routing;

import java.util.List;

public interface RoutingDecisionStore {
  void append(RoutingDecision decision);

  List<RoutingDecision> findByRunId(String runId);

  List<RoutingDecision> recent(int limit);
}
