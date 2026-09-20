package io.oryxos.core.routing;

import java.util.Locale;

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
