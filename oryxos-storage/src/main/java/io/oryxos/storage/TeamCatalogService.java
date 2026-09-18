package io.oryxos.storage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/** 团队目录管理（#539 / #554）：create / rename / list / delete / setOrg。不强制成员关系引用本表——catalog 是可选展示元数据。 */
public class TeamCatalogService {

  private static final int MAX_TEAM_ID = 128;
  private static final int MAX_DISPLAY = 255;
  private static final char SPACE = ' ';

  private final TeamRepository repository;
  private final OrganizationRepository organizationRepository;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "repository 为 Spring 注入共享单例，构造注入存同一引用正是意图。")
  public TeamCatalogService(
      TeamRepository repository, OrganizationRepository organizationRepository) {
    this.repository = repository;
    this.organizationRepository = organizationRepository;
  }

  @Transactional(readOnly = true)
  public List<Team> list() {
    return repository.findAllByOrderByTeamIdAsc();
  }

  @Transactional(readOnly = true)
  public Optional<Team> find(String teamId) {
    if (teamId == null || teamId.isBlank()) {
      return Optional.empty();
    }
    return repository.findByTeamId(teamId.strip());
  }

  /** 幂等确保目录行存在（OIDC JIT / #552）。已存在则原样返回；不存在则创建，displayName 空则回落为 teamId。 */
  @Transactional(rollbackFor = Exception.class)
  public Team ensure(String teamId) {
    return ensure(teamId, null);
  }

  /** 同 {@link #ensure(String)}；可传入展示名（空则回落 teamId）。已存在不改名。 */
  @Transactional(rollbackFor = Exception.class)
  public Team ensure(String teamId, String displayName) {
    String cleanId = requireTeamId(teamId);
    Optional<Team> existing = repository.findByTeamId(cleanId);
    if (existing.isPresent()) {
      return existing.get();
    }
    Team row = new Team();
    row.setTeamId(cleanId);
    row.setDisplayName(resolveDisplayName(displayName, cleanId));
    return repository.save(row);
  }

  /** 创建目录行；已存在则抛 IllegalArgumentException。displayName 空则回落为 teamId。 */
  @Transactional(rollbackFor = Exception.class)
  public Team create(String teamId, String displayName) {
    String cleanId = requireTeamId(teamId);
    if (repository.existsByTeamId(cleanId)) {
      throw new IllegalArgumentException("team '" + cleanId + "' already exists");
    }
    Team row = new Team();
    row.setTeamId(cleanId);
    row.setDisplayName(resolveDisplayName(displayName, cleanId));
    return repository.save(row);
  }

  /** 改展示名；团队必须已存在。 */
  @Transactional(rollbackFor = Exception.class)
  public Team rename(String teamId, String displayName) {
    String cleanId = requireTeamId(teamId);
    Team row =
        repository
            .findByTeamId(cleanId)
            .orElseThrow(() -> new IllegalArgumentException("team '" + cleanId + "' not found"));
    if (displayName == null || displayName.isBlank()) {
      throw new IllegalArgumentException("displayName must not be empty");
    }
    row.setDisplayName(truncate(displayName.strip(), MAX_DISPLAY));
    row.setUpdatedAt(Instant.now());
    return repository.save(row);
  }

  /** 设置所属组织；{@code orgId} 空/空白则清空。非空时组织必须已在目录中。不改 ACL / decide。 */
  @Transactional(rollbackFor = Exception.class)
  public Team setOrg(String teamId, String orgId) {
    String cleanId = requireTeamId(teamId);
    Team row =
        repository
            .findByTeamId(cleanId)
            .orElseThrow(() -> new IllegalArgumentException("team '" + cleanId + "' not found"));
    if (orgId == null || orgId.isBlank()) {
      row.setOrgId(null);
    } else {
      String cleanOrg = orgId.strip();
      if (!organizationRepository.existsByOrgId(cleanOrg)) {
        throw new IllegalArgumentException("org '" + cleanOrg + "' not found");
      }
      row.setOrgId(cleanOrg);
    }
    row.setUpdatedAt(Instant.now());
    return repository.save(row);
  }

  /** 删除目录行；不存在则幂等成功。不删 {@code team_memberships}——成员关系可继续用裸 team_id。 */
  @Transactional(rollbackFor = Exception.class)
  public void delete(String teamId) {
    String cleanId = requireTeamId(teamId);
    repository.findByTeamId(cleanId).ifPresent(repository::delete);
  }

  private static String requireTeamId(String teamId) {
    if (teamId == null || teamId.isBlank()) {
      throw new IllegalArgumentException("teamId must not be empty");
    }
    String clean = teamId.strip();
    if (clean.length() > MAX_TEAM_ID) {
      throw new IllegalArgumentException("teamId must be <= " + MAX_TEAM_ID + " chars");
    }
    if (clean.indexOf(SPACE) >= 0) {
      throw new IllegalArgumentException("teamId must not contain spaces");
    }
    return clean;
  }

  private static String resolveDisplayName(String displayName, String teamId) {
    if (displayName == null || displayName.isBlank()) {
      return teamId;
    }
    return truncate(displayName.strip(), MAX_DISPLAY);
  }

  private static String truncate(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max);
  }
}
