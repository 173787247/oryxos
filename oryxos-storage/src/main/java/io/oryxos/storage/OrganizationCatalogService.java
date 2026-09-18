package io.oryxos.storage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/** 组织目录管理（#554）：create / rename / list / delete / ensure。不驱动授权裁决。 */
public class OrganizationCatalogService {

  private static final int MAX_ORG_ID = 128;
  private static final int MAX_DISPLAY = 255;
  private static final char SPACE = ' ';

  private final OrganizationRepository repository;
  private final TeamRepository teamRepository;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "repository 为 Spring 注入共享单例，构造注入存同一引用正是意图。")
  public OrganizationCatalogService(
      OrganizationRepository repository, TeamRepository teamRepository) {
    this.repository = repository;
    this.teamRepository = teamRepository;
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

  /** 删除目录行；不存在则幂等成功。先清空引用本 org 的 {@code teams.org_id}（镜像 PG ON DELETE SET NULL）。 */
  @Transactional(rollbackFor = Exception.class)
  public void delete(String orgId) {
    String cleanId = requireOrgId(orgId);
    for (Team team : teamRepository.findByOrgIdOrderByTeamIdAsc(cleanId)) {
      team.setOrgId(null);
      team.setUpdatedAt(Instant.now());
      teamRepository.save(team);
    }
    repository.findByOrgId(cleanId).ifPresent(repository::delete);
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
