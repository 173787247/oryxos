package io.oryxos.storage;

import io.oryxos.core.policy.OrgParentLookup;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/** 组织目录管理（#554 / #566）：create / rename / list / delete / ensure / setParent。不驱动授权裁决。 */
public class OrganizationCatalogService {

  private static final int MAX_ORG_ID = 128;
  private static final int MAX_DISPLAY = 255;
  private static final char SPACE = ' ';

  private final OrganizationRepository repository;
  private final TeamRepository teamRepository;

  /** setParent 环检测上行深度；默认 {@link OrgParentLookup#MAX_ORG_ANCESTOR_DEPTH}。 */
  private final int maxOrgAncestorDepth;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "repository 为 Spring 注入共享单例，构造注入存同一引用正是意图。")
  public OrganizationCatalogService(
      OrganizationRepository repository, TeamRepository teamRepository) {
    this(repository, teamRepository, OrgParentLookup.MAX_ORG_ANCESTOR_DEPTH);
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "repository 为 Spring 注入共享单例，构造注入存同一引用正是意图。")
  public OrganizationCatalogService(
      OrganizationRepository repository, TeamRepository teamRepository, int maxOrgAncestorDepth) {
    this.repository = repository;
    this.teamRepository = teamRepository;
    this.maxOrgAncestorDepth =
        maxOrgAncestorDepth < 1 ? OrgParentLookup.MAX_ORG_ANCESTOR_DEPTH : maxOrgAncestorDepth;
  }

  @Transactional(readOnly = true)
  public List<Organization> list() {
    return repository.findAllByOrderByOrgIdAsc();
  }

  @Transactional(readOnly = true)
  public Optional<Organization> find(String orgId) {
    if (orgId == null || orgId.isBlank()) {
      return Optional.empty();
    }
    return repository.findByOrgId(orgId.strip());
  }

  /** 幂等确保目录行存在。已存在则原样返回；不存在则创建，displayName 空则回落为 orgId。 */
  @Transactional(rollbackFor = Exception.class)
  public Organization ensure(String orgId) {
    return ensure(orgId, null);
  }

  /** 同 {@link #ensure(String)}；可传入展示名（空则回落 orgId）。已存在不改名。 */
  @Transactional(rollbackFor = Exception.class)
  public Organization ensure(String orgId, String displayName) {
    String cleanId = requireOrgId(orgId);
    Optional<Organization> existing = repository.findByOrgId(cleanId);
    if (existing.isPresent()) {
      return existing.get();
    }
    Organization row = new Organization();
    row.setOrgId(cleanId);
    row.setDisplayName(resolveDisplayName(displayName, cleanId));
    return repository.save(row);
  }

  /** 创建目录行；已存在则抛 IllegalArgumentException。displayName 空则回落为 orgId。 */
  @Transactional(rollbackFor = Exception.class)
  public Organization create(String orgId, String displayName) {
    String cleanId = requireOrgId(orgId);
    if (repository.existsByOrgId(cleanId)) {
      throw new IllegalArgumentException("org '" + cleanId + "' already exists");
    }
    Organization row = new Organization();
    row.setOrgId(cleanId);
    row.setDisplayName(resolveDisplayName(displayName, cleanId));
    return repository.save(row);
  }

  /** 改展示名；组织必须已存在。 */
  @Transactional(rollbackFor = Exception.class)
  public Organization rename(String orgId, String displayName) {
    String cleanId = requireOrgId(orgId);
    Organization row =
        repository
            .findByOrgId(cleanId)
            .orElseThrow(() -> new IllegalArgumentException("org '" + cleanId + "' not found"));
    if (displayName == null || displayName.isBlank()) {
      throw new IllegalArgumentException("displayName must not be empty");
    }
    row.setDisplayName(truncate(displayName.strip(), MAX_DISPLAY));
    row.setUpdatedAt(Instant.now());
    return repository.save(row);
  }

  /** 设置父组织；{@code parentOrgId} 空/空白则清空。非空时父组织必须已在目录中，且不得等于自身；有界上行检测环（#573）。 */
  @Transactional(rollbackFor = Exception.class)
  public Organization setParent(String orgId, String parentOrgId) {
    String cleanId = requireOrgId(orgId);
    Organization row =
        repository
            .findByOrgId(cleanId)
            .orElseThrow(() -> new IllegalArgumentException("org '" + cleanId + "' not found"));
    if (parentOrgId == null || parentOrgId.isBlank()) {
      row.setParentOrgId(null);
    } else {
      String cleanParent = parentOrgId.strip();
      if (cleanParent.equals(cleanId)) {
        throw new IllegalArgumentException("org '" + cleanId + "' cannot be its own parent");
      }
      if (!repository.existsByOrgId(cleanParent)) {
        throw new IllegalArgumentException("org '" + cleanParent + "' not found");
      }
      rejectParentCycle(cleanId, cleanParent);
      row.setParentOrgId(cleanParent);
    }
    row.setUpdatedAt(Instant.now());
    return repository.save(row);
  }

  /**
   * 删除目录行；不存在则幂等成功。先清空引用本 org 的 {@code teams.org_id} 与子组织 {@code parent_org_id}（镜像 PG ON DELETE SET
   * NULL）。
   */
  @Transactional(rollbackFor = Exception.class)
  public void delete(String orgId) {
    String cleanId = requireOrgId(orgId);
    for (Team team : teamRepository.findByOrgIdOrderByTeamIdAsc(cleanId)) {
      team.setOrgId(null);
      team.setUpdatedAt(Instant.now());
      teamRepository.save(team);
    }
    for (Organization child : repository.findByParentOrgIdOrderByOrgIdAsc(cleanId)) {
      child.setParentOrgId(null);
      child.setUpdatedAt(Instant.now());
      repository.save(child);
    }
    repository.findByOrgId(cleanId).ifPresent(repository::delete);
  }

  /**
   * 沿 proposedParent 的 parent_org_id 有界上行；若路径含 orgId 则拒（A→B 再 B→A 等）。深度取构造注入的 {@code
   * maxOrgAncestorDepth}（默认 {@link OrgParentLookup#MAX_ORG_ANCESTOR_DEPTH}）。
   */
  private void rejectParentCycle(String orgId, String proposedParentId) {
    String current = proposedParentId;
    for (int depth = 0; depth < maxOrgAncestorDepth; depth++) {
      Optional<Organization> node = repository.findByOrgId(current);
      if (node.isEmpty()) {
        return;
      }
      String next = node.get().getParentOrgId();
      if (next == null || next.isBlank()) {
        return;
      }
      String cleanNext = next.strip();
      if (orgId.equals(cleanNext)) {
        throw new IllegalArgumentException(
            "org '" + orgId + "' parent would create a cycle via '" + proposedParentId + "'");
      }
      if (cleanNext.equals(current)) {
        return;
      }
      current = cleanNext;
    }
  }

  private static String requireOrgId(String orgId) {
    if (orgId == null || orgId.isBlank()) {
      throw new IllegalArgumentException("orgId must not be empty");
    }
    String clean = orgId.strip();
    if (clean.length() > MAX_ORG_ID) {
      throw new IllegalArgumentException("orgId must be <= " + MAX_ORG_ID + " chars");
    }
    if (clean.indexOf(SPACE) >= 0) {
      throw new IllegalArgumentException("orgId must not contain spaces");
    }
    return clean;
  }

  private static String resolveDisplayName(String displayName, String orgId) {
    if (displayName == null || displayName.isBlank()) {
      return orgId;
    }
    return truncate(displayName.strip(), MAX_DISPLAY);
  }

  private static String truncate(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max);
  }
}
