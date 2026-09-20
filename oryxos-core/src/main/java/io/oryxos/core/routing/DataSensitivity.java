package io.oryxos.core.routing;

import java.util.Locale;

@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "DM_CONVERT_CASE",
    justification = "Enum token parse uses Locale.ROOT deliberately")
public enum DataSensitivity {
  NORMAL,
  SENSITIVE;

  public static DataSensitivity parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    return DataSensitivity.valueOf(raw.trim().toUpperCase(Locale.ROOT));
  }
}
