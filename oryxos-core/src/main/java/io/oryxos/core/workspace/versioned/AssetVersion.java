package io.oryxos.core.workspace.versioned;

/** Published content version metadata. */
public record AssetVersion(
    VersionedAssetKind kind, String assetId, long version, String contentHash) {}
