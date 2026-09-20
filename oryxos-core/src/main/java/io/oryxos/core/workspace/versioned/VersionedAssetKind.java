package io.oryxos.core.workspace.versioned;

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

  public static VersionedAssetKind parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("kind required");
    }
    String key = raw.strip().toLowerCase(java.util.Locale.ROOT);
    for (VersionedAssetKind kind : values()) {
      if (kind.directory.equals(key)) {
        return kind;
      }
    }
    throw new IllegalArgumentException("unsupported kind: " + raw);
  }
}
