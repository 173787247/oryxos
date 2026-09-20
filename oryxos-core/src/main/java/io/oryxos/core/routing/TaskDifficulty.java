package io.oryxos.core.routing;

public enum TaskDifficulty {
  LOW,
  MEDIUM,
  HIGH;

  public static TaskDifficulty parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    return TaskDifficulty.valueOf(raw.trim().toUpperCase());
  }
}
