package io.oryxos.core.routing;

import java.util.Locale;

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
