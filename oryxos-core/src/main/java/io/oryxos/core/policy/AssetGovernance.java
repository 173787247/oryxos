package io.oryxos.core.policy;

import java.util.Locale;
import java.util.Objects;

/**
 * 资产治理模型（041 / #463）：Agent/Skill/Knowledge 落在 {@code GOVERNANCE.yml}；渠道嵌在 {@code channels.yaml} 的
 * {@code governance:} 块。字段相同，不承载凭证。
 *
 * <p>缺文件或字段全空视为「未设治理」——装饰器不得额外拒绝（存量兼容）。{@code riskLevel} 与 {@code version} 本刀只承载展示/审计，不参与裁决。
 *
 * <p>{@code teamOwner}：WORKSPACE 可见性的团队 id（与 OIDC groups / {@code Principal.teamIds} 对齐）。仅在 {@code
 * workspace-team-acl} 开启且本字段非空时参与裁决；缺省不另拒。
 *
 * <p>{@code orgOwner}：WORKSPACE 可见性的组织 id（与 {@code teams.org_id} 对齐）。仅在 {@code workspace-org-acl}
 * 开启且本字段非空时参与裁决；缺省不另拒。
 */
public record AssetGovernance(
    String owner,
    String version,
    Visibility visibility,
    String riskLevel,
    Health health,
    String teamOwner,
    String orgOwner) {

  /** 可见范围：PRIVATE 仅 owner/ADMIN；WORKSPACE 在 team/org-acl 关或无对应 owner 时不另拒；PUBLIC 不另拒。 */
  public enum Visibility {
    PRIVATE,
    WORKSPACE,
    PUBLIC
  }

  /** 生命周期健康态：OFFLINE 一律拒绝；其余本刀不拦截。 */
  public enum Health {
    ACTIVE,
    DEPRECATED,
    OFFLINE
  }

  /** 无团队/组织归属的便捷构造——既有 5 参调用点保持编译。 */
  public AssetGovernance(
      String owner, String version, Visibility visibility, String riskLevel, Health health) {
    this(owner, version, visibility, riskLevel, health, null, null);
  }

  /** 无组织归属的便捷构造——既有 6 参（含 teamOwner）调用点保持编译。 */
  public AssetGovernance(
      String owner,
      String version,
      Visibility visibility,
      String riskLevel,
      Health health,
      String teamOwner) {
    this(owner, version, visibility, riskLevel, health, teamOwner, null);
  }

  /** 空元数据：无 GOVERNANCE.yml 或解析后无有效字段。 */
  public static AssetGovernance empty() {
    return new AssetGovernance(null, null, null, null, null, null, null);
  }

  /** 是否携带任何可裁决/展示字段（缺文件时为 false）。 */
  public boolean isPresent() {
    return (owner != null && !owner.isBlank())
        || (version != null && !version.isBlank())
        || visibility != null
        || (riskLevel != null && !riskLevel.isBlank())
        || health != null
        || (teamOwner != null && !teamOwner.isBlank())
        || (orgOwner != null && !orgOwner.isBlank());
  }

  /** 解析可见性；未知 token 返回 null（不因脏数据误拒）。 */
  public static Visibility parseVisibility(String raw) {
    return parseEnum(Visibility.class, raw);
  }

  /** 解析健康态；未知 token 返回 null。 */
  public static Health parseHealth(String raw) {
    return parseEnum(Health.class, raw);
  }

  private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Enum.valueOf(type, raw.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  @Override
  public String toString() {
    return "AssetGovernance{owner="
        + Objects.toString(owner, "")
        + ", visibility="
        + visibility
        + ", health="
        + health
        + ", teamOwner="
        + Objects.toString(teamOwner, "")
        + ", orgOwner="
        + Objects.toString(orgOwner, "")
        + "}";
  }
}
