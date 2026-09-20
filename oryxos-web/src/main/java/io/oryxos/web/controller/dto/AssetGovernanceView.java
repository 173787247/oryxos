package io.oryxos.web.controller.dto;

import io.oryxos.core.policy.AssetGovernance;

/** 治理侧车的读写视图（041）。字段可空：空值表示未设，不表示拒绝。 */
public record AssetGovernanceView(
    String owner,
    String version,
    String visibility,
    String riskLevel,
    String health,
    String teamOwner,
    String orgOwner) {

  /** 无 teamOwner/orgOwner 的便捷构造——既有 5 参 JSON/测试保持兼容。 */
  public AssetGovernanceView(
      String owner, String version, String visibility, String riskLevel, String health) {
    this(owner, version, visibility, riskLevel, health, null, null);
  }

  /** 无 orgOwner 的便捷构造——既有 6 参 JSON/测试保持兼容。 */
  public AssetGovernanceView(
      String owner,
      String version,
      String visibility,
      String riskLevel,
      String health,
      String teamOwner) {
    this(owner, version, visibility, riskLevel, health, teamOwner, null);
  }

  public static AssetGovernanceView from(AssetGovernance governance) {
    AssetGovernance source = governance == null ? AssetGovernance.empty() : governance;
    return new AssetGovernanceView(
        source.owner(),
        source.version(),
        source.visibility() == null ? null : source.visibility().name(),
        source.riskLevel(),
        source.health() == null ? null : source.health().name(),
        source.teamOwner(),
        source.orgOwner());
  }

  public AssetGovernance toModel() {
    return new AssetGovernance(
        blankToNull(owner),
        blankToNull(version),
        AssetGovernance.parseVisibility(visibility),
        blankToNull(riskLevel),
        AssetGovernance.parseHealth(health),
        blankToNull(teamOwner),
        blankToNull(orgOwner));
  }

  private static String blankToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.strip();
  }
}
