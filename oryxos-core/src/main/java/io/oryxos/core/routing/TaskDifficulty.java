package io.oryxos.core.routing;

import java.util.Locale;

@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "DM_CONVERT_CASE",
    justification = "Enum token parse uses Locale.ROOT deliberately")
public enum TaskDifficulty {
  LOW,
  MEDIUM,
  HIGH;

  public static TaskDifficulty parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    return TaskDifficulty.valueOf(raw.trim().toUpperCase(Locale.ROOT));
  }
}
