package io.oryxos.core.routing;

public enum DataSensitivity {
  NORMAL,
  SENSITIVE;

  public static DataSensitivity parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    return DataSensitivity.valueOf(raw.trim().toUpperCase());
  }
}
