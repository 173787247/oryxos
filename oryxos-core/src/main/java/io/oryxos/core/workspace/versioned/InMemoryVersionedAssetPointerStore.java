package io.oryxos.core.workspace.versioned;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory pointer store for unit tests (shared map = multi-reader same authority). */
public final class InMemoryVersionedAssetPointerStore implements VersionedAssetPointerStore {

  private final Map<String, List<Entry>> versions = new ConcurrentHashMap<>();
  private final Map<String, Active> active = new ConcurrentHashMap<>();

  @Override
  public synchronized long nextVersion(VersionedAssetKind kind, String assetId) {
    List<Entry> list = versions.computeIfAbsent(key(kind, assetId), ignored -> new ArrayList<>());
    long max = list.stream().mapToLong(Entry::version).max().orElse(0L);
    return max + 1;
  }

  @Override
  public synchronized void recordVersion(
      VersionedAssetKind kind, String assetId, long version, String contentHash, String actor) {
    versions
        .computeIfAbsent(key(kind, assetId), ignored -> new ArrayList<>())
        .add(new Entry(version, contentHash));
  }

  @Override
  public synchronized boolean hasVersion(VersionedAssetKind kind, String assetId, long version) {
    return versions.getOrDefault(key(kind, assetId), List.of()).stream()
        .anyMatch(e -> e.version == version);
  }

  @Override
  public synchronized List<Long> listVersions(VersionedAssetKind kind, String assetId) {
    return versions.getOrDefault(key(kind, assetId), List.of()).stream()
        .map(Entry::version)
        .sorted(Comparator.reverseOrder())
        .toList();
  }

  @Override
  public OptionalLong activeVersion(VersionedAssetKind kind, String assetId) {
    Active row = active.get(key(kind, assetId));
    return row == null ? OptionalLong.empty() : OptionalLong.of(row.activeVersion);
  }

  @Override
  public OptionalLong previousVersion(VersionedAssetKind kind, String assetId) {
    Active row = active.get(key(kind, assetId));
    if (row == null || row.previousVersion == null) {
      return OptionalLong.empty();
    }
    return OptionalLong.of(row.previousVersion);
  }

  @Override
  public synchronized boolean activate(
      VersionedAssetKind kind, String assetId, long targetVersion, String actor) {
    if (!hasVersion(kind, assetId, targetVersion)) {
      return false;
    }
    String k = key(kind, assetId);
    Active current = active.get(k);
    Long previous = current == null ? null : current.activeVersion;
    if (current != null && current.activeVersion == targetVersion) {
      return true;
    }
    active.put(k, new Active(targetVersion, previous));
    return true;
  }

  private static String key(VersionedAssetKind kind, String assetId) {
    return kind.directory() + "/" + assetId;
  }

  private record Entry(long version, String contentHash) {}

  private record Active(long activeVersion, Long previousVersion) {}
}
