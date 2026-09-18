package io.oryxos.web.controller.dto;

/** 两版治理快照的统一 diff（#544）。 */
public record AssetGovernanceRevisionDiffView(
    long fromId, long toId, String fromVersionLabel, String toVersionLabel, String unifiedDiff) {}
