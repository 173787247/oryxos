package io.oryxos.core.flow;

import java.util.Objects;

/** One static lint finding. */
public record FlowDiagnostic(Severity severity, String code, String path, String message) {

  public enum Severity {
    ERROR,
    WARNING
  }

  public FlowDiagnostic {
    severity = Objects.requireNonNull(severity, "severity");
    code = Objects.requireNonNull(code, "code").strip();
    path = path == null ? "" : path;
    message = Objects.requireNonNull(message, "message");
  }

  public static FlowDiagnostic error(String code, String path, String message) {
    return new FlowDiagnostic(Severity.ERROR, code, path, message);
  }

  public boolean isError() {
    return severity == Severity.ERROR;
  }
}
