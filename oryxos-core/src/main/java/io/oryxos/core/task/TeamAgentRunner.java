package io.oryxos.core.task;

/** Runs one Agent turn for team orchestration (Direction I MVP). */
@FunctionalInterface
public interface TeamAgentRunner {
  String run(String agentName, String userMessage);
}
