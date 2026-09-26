package io.oryxos.tool.builtin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Product switch for {@link ExecuteCodeTools} ({@code oryxos.tool.execute-code.*}). */
@ConfigurationProperties(prefix = "oryxos.tool.execute-code")
public record ExecuteCodeProperties(boolean enabled) {

  public ExecuteCodeProperties {
    // record compact ctor — enabled defaults false when unbound
  }

  public static ExecuteCodeProperties disabled() {
    return new ExecuteCodeProperties(false);
  }
}
