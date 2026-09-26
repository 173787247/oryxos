package io.oryxos.core.task;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code oryxos.task.team.*} — Direction I MVP switches. */
@ConfigurationProperties(prefix = "oryxos.task.team")
public record TeamTaskProperties(boolean enabled, String coordinator, int maxSubtasks) {

  public TeamTaskProperties {
    coordinator =
        coordinator == null || coordinator.isBlank() ? "coordinator" : coordinator.strip();
    maxSubtasks = maxSubtasks <= 0 ? 4 : Math.min(maxSubtasks, 16);
  }

  public static TeamTaskProperties disabled() {
    return new TeamTaskProperties(false, "coordinator", 4);
  }
}
