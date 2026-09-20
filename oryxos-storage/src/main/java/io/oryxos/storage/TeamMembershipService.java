package io.oryxos.storage;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.transaction.annotation.Transactional;

/**
 * 持久化团队成员管理（#535）：add / remove / list。本地用户必须已存在；重复 add/remove 幂等。
 *
 * <p>是否注入 {@code Principal.teamIds} 由 web 侧 {@code
 * oryxos.web.rbac.durable-team-memberships-enabled} 决定——本服务本身无开关。
 */
public class TeamMembershipService {

  private static final int MAX_USERNAME = 64;
  private static final int MAX_TEAM_ID = 128;
  private static final char SPACE = ' ';

  private final TeamMembershipRepository repository;
  private final WebUserRepository userRepository;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "repository/userRepository 均为 Spring 注入共享单例，构造注入存同一引用正是意图。")
  public TeamMembershipService(
      TeamMembershipRepository repository, WebUserRepository userRepository) {
    this.repository = repository;
    this.userRepository = userRepository;
  }

  /** 列出用户的团队 id（有序、去重）；用户不存在时返回空集（不抛）。 */
  @Transactional(readOnly = true)
  public Set<String> listTeamIds(String username) {
    if (username == null || username.isBlank()) {
      return Set.of();
    }
    String clean = username.strip();
    List<TeamMembership> rows = repository.findByUsernameOrderByTeamIdAsc(clean);
    LinkedHashSet<String> out = new LinkedHashSet<>();
    for (TeamMembership row : rows) {
      if (row.getTeamId() != null && !row.getTeamId().isBlank()) {
        out.add(row.getTeamId());
      }
    }
    return Set.copyOf(out);
  }

  /** 加入团队；用户必须已存在；已存在成员关系则幂等成功。 */
  @Transactional(rollbackFor = Exception.class)
  public void add(String username, String teamId) {
    String cleanUser = requireUsername(username);
    String cleanTeam = requireTeamId(teamId);
    if (!userRepository.existsByUsername(cleanUser)) {
      throw new IllegalArgumentException("user '" + cleanUser + "' not found");
    }
    if (repository.existsByUsernameAndTeamId(cleanUser, cleanTeam)) {
      return;
    }
    TeamMembership row = new TeamMembership();
    row.setUsername(cleanUser);
    row.setTeamId(cleanTeam);
    repository.save(row);
  }

  /** 移除成员关系；不存在则幂等成功。 */
  @Transactional(rollbackFor = Exception.class)
  public void remove(String username, String teamId) {
    String cleanUser = requireUsername(username);
    String cleanTeam = requireTeamId(teamId);
    repository.deleteByUsernameAndTeamId(cleanUser, cleanTeam);
  }

  private static String requireUsername(String username) {
    if (username == null || username.isBlank()) {
      throw new IllegalArgumentException("username must not be empty");
    }
    String clean = username.strip();
    if (clean.length() > MAX_USERNAME) {
      throw new IllegalArgumentException("username must be <= " + MAX_USERNAME + " chars");
    }
    if (clean.indexOf(SPACE) >= 0) {
      throw new IllegalArgumentException("username must not contain spaces");
    }
    return clean;
  }

  private static String requireTeamId(String teamId) {
    if (teamId == null || teamId.isBlank()) {
      throw new IllegalArgumentException("teamId must not be empty");
    }
    String clean = teamId.strip();
    if (clean.length() > MAX_TEAM_ID) {
      throw new IllegalArgumentException("teamId must be <= " + MAX_TEAM_ID + " chars");
    }
    return clean;
  }
}
