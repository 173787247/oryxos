package io.oryxos.core.flow;

/**
 * Runs one Agent turn for a Flow {@link FlowNodeType#AGENT} node. Boot/runtime injects this (e.g.
 * {@code AgentService#processStateless}); core Flow tests keep the default echo handler when the
 * runner is absent.
 */
@FunctionalInterface
public interface FlowAgentRunner {

  /**
   * @param agentName profile / agent directory name ({@link FlowNode#ref()})
   * @param userMessage composed from node input ports
   * @return assistant reply text
   */
  String run(String agentName, String userMessage);
}
