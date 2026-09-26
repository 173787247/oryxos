package io.oryxos.tool.builtin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Gate for built-in {@code delegate_agent} (default on). */
@ConfigurationProperties(prefix = "oryxos.tool.delegate-agent")
public record DelegateAgentProperties(
    @DefaultValue("true") boolean enabled, @DefaultValue("3") int maxDepth) {

  public DelegateAgentProperties {
    if (maxDepth <= 0) {
      maxDepth = 3;
    }
    if (maxDepth > 8) {
      maxDepth = 8;
    }
  }
}
