package io.oryxos.core.workspace.versioned;

import java.util.Locale;

/** Asset domains covered by the versioned source (#473). Directory name == wire value. */
public enum VersionedAssetKind {
  AGENTS("agents"),
  SKILLS("skills"),
  KNOWLEDGE("knowledge");

  private final String directory;

  VersionedAssetKind(String directory) {
    this.directory = directory;
  }

  public String directory() {
    return directory;
  }

  /** ASCII wire values only (agents|skills|knowledge); Locale.ROOT fold. */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification = "Wire kinds are ASCII literals; Locale.ROOT case fold is intentional.")
  public static VersionedAssetKind parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("kind required");
    }
    String key = raw.strip().toLowerCase(Locale.ROOT);
    for (VersionedAssetKind kind : values()) {
      if (kind.directory.equals(key)) {
        return kind;
      }
    }
    throw new IllegalArgumentException("unsupported kind: " + raw);
  }
}
