package io.oryxos.web.config;

import io.oryxos.core.auth.Principal;
import io.oryxos.core.auth.Role;
import io.oryxos.core.policy.AssetAwareAuthorizationServiceImpl;
import io.oryxos.core.policy.AssetGovernanceStore;
import io.oryxos.core.policy.AuthorizationService;
import io.oryxos.core.policy.RoleBasedAuthorizationServiceImpl;
import io.oryxos.core.policy.TeamOrgLookup;
import io.oryxos.storage.Team;
import io.oryxos.storage.TeamCatalogService;
import io.oryxos.web.security.AssetBindGuard;
import io.oryxos.web.security.RbacEnforcer;
import io.oryxos.web.security.RuntimeAgentGuard;
import io.oryxos.web.security.SessionOrgIdsCache;
import io.oryxos.web.security.SessionTeamIdsCache;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 授权装配（039-identity-authorization）：决定容器里注入的是「全允」还是「按角色裁决」。
 *
 * <p>为什么默认必须是 {@link AuthorizationService#ALLOW_ALL}：未启用授权时行为要与引入本层之前逐字节一致 （018 SC-001）。把它做成 Bean
 * 而不是在各调用点判 flag，是为了让「有没有授权层」只有一个事实来源—— 调用点只调 {@code decide}，不需要知道开关状态，也就不可能某个调用点漏判。
 *
 * <p>启用授权时，未自带角色的主体回落到 {@link RoleMappingProperties} 配置默认值：管理台账号与 API Key 的 {@code default-*-roles}
 * 均默认<b>空</b>（无角色即拒绝）。角色已落库后靠账号自身 roles + {@link io.oryxos.web.security.RbacStartupCheck} 保证至少一个
 * ADMIN，避免治理面锁死。
 */
@Configuration
@EnableConfigurationProperties({
  WebRbacProperties.class,
  RoleMappingProperties.class,
  WebAssetGovernanceProperties.class
})
public class AuthorizationConfig {

  private static final Logger LOG = LoggerFactory.getLogger(AuthorizationConfig.class);

  private static final String LOG_ALLOW_ALL = "RBAC 未启用：授权决策点注入全允实现（行为与引入授权层前一致）";

  private static final String LOG_ROLE_BASED =
      "RBAC 已启用：授权决策点注入角色矩阵实现（userRoles={}, apiKeyRoles={}, denyAnonymous={}）";

  @Bean
  SessionTeamIdsCache sessionTeamIdsCache() {
    return new SessionTeamIdsCache();
  }

  /** #560：session 组织声明（由 teamIds × teams.org_id 派生）。 */
  @Bean
  SessionOrgIdsCache sessionOrgIdsCache() {
    return new SessionOrgIdsCache();
  }

  /** #535：session 团队 ∪（可选）持久化 team_memberships。 */
  @Bean
  io.oryxos.web.security.PrincipalTeamIdsMerger principalTeamIdsMerger(
      WebRbacProperties properties,
      ObjectProvider<io.oryxos.storage.TeamMembershipService> memberships) {
    return new io.oryxos.web.security.PrincipalTeamIdsMerger(
        properties, memberships.getIfAvailable());
  }

  /**
   * 授权决策点。
   *
   * @param properties 授权开关与默认拒绝策略
   * @param roleProperties 默认角色配置
   */
  @Bean
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification =
          "日志仅记录枚举 Role 集合与 boolean 开关；角色名来自配置绑定后经 parseRoles 归一为 Role 枚举，"
              + "无法携带 CR/LF（镜像 ApiKeyService 的 SuppressFBWarnings 模式）。")
  AuthorizationService authorizationService(
      WebRbacProperties properties,
      RoleMappingProperties roleProperties,
      WebAssetGovernanceProperties assetGovernance,
      org.springframework.beans.factory.ObjectProvider<AssetGovernanceStore> governanceStore,
      ObjectProvider<TeamCatalogService> teamCatalog) {
    if (!properties.isEnabled()) {
      LOG.info(LOG_ALLOW_ALL);
      return AuthorizationService.ALLOW_ALL;
    }
    Set<Role> userRoles = parseRoles(roleProperties.getDefaultUserRoles());
    Set<Role> apiKeyRoles = parseRoles(roleProperties.getDefaultApiKeyRoles());
    LOG.info(LOG_ROLE_BASED, userRoles, apiKeyRoles, properties.isDenyAnonymous());
    AuthorizationService roleBased = new RoleBasedAuthorizationServiceImpl(userRoles, apiKeyRoles);
    // 资产门禁只在「RBAC 已开且资产治理开」时包一层；否则仍是纯角色矩阵（或上方的 ALLOW_ALL）。
    if (assetGovernance == null || !assetGovernance.isEnabled()) {
      return roleBased;
    }
    AssetGovernanceStore store = governanceStore.getIfAvailable();
    if (store == null) {
      LOG.warn("资产治理已启用但未装配 AssetGovernanceStore，跳过资产门禁");
      return roleBased;
    }
    TeamOrgLookup orgLookup = teamOrgLookupBean(teamCatalog);
    return new AssetAwareAuthorizationServiceImpl(
        roleBased,
        store,
        true,
        assetGovernance.isWorkspaceTeamAclEnabled(),
        assetGovernance.isWorkspaceOrgAclEnabled(),
        orgLookup);
  }

  /** #558 / #560：把 teamId 映射到 teams.org_id；目录 Bean 缺失时恒 empty。 */
  @Bean
  TeamOrgLookup teamOrgLookup(ObjectProvider<TeamCatalogService> teamCatalog) {
    return teamOrgLookupBean(teamCatalog);
  }

  private static TeamOrgLookup teamOrgLookupBean(ObjectProvider<TeamCatalogService> teamCatalog) {
    return teamId -> {
      if (teamId == null || teamId.isBlank()) {
        return Optional.empty();
      }
      TeamCatalogService catalog = teamCatalog.getIfAvailable();
      if (catalog == null) {
        return Optional.empty();
      }
      Optional<Team> row = catalog.find(teamId.strip());
      if (row.isEmpty()) {
        return Optional.empty();
      }
      String orgId = row.get().getOrgId();
      if (orgId == null || orgId.isBlank()) {
        return Optional.empty();
      }
      return Optional.of(orgId.strip());
    };
  }

  /** 绑定/调用点的薄封装：内部仍只调 {@link AuthorizationService#decide}。 */
  @Bean
  AssetBindGuard assetBindGuard(AuthorizationService authorizationService) {
    return new AssetBindGuard(authorizationService);
  }

  /** 运行时开跑 Agent：同一 decide + PrincipalContext 载体（#503）。 */
  @Bean
  RuntimeAgentGuard runtimeAgentGuard(AssetBindGuard assetBindGuard) {
    return new RuntimeAgentGuard(assetBindGuard);
  }

  /**
   * 039 / #531：把钟推 AgentScheduler 的运行时主体设为 API Key 档，角色取 {@code default-api-key-roles} （默认空；RBAC 关时
   * ToolExecutor 仍走 ALLOW_ALL）。
   */
  @Bean
  InitializingBean wireSchedulerRunPrincipal(
      ObjectProvider<io.oryxos.core.agent.AgentScheduler> agentScheduler,
      RoleMappingProperties roleProperties) {
    return () ->
        agentScheduler.ifAvailable(
            scheduler ->
                scheduler.setRunPrincipal(
                    Principal.apiKey(
                        "scheduler",
                        "scheduler",
                        parseRoles(roleProperties.getDefaultApiKeyRoles()))));
  }

  /**
   * RBAC 强制点：由认证门（{@code ApiKeyAuthFilter}）持有，授权只有一处实现。
   *
   * <p>本刀只接在 {@code /api/v1|v2/*}、{@code /actuator/*} 这条既有门上（作用域说明见 {@link RbacEnforcer}）。
   */
  @Bean
  RbacEnforcer rbacEnforcer(
      AuthorizationService authorizationService,
      WebRbacProperties properties,
      io.oryxos.storage.AuthzEventRecorder authzEventRecorder) {
    return new RbacEnforcer(authorizationService, properties, authzEventRecorder);
  }

  /** 角色名解析：大小写不敏感、去空白；无法识别的角色名直接忽略并告警，不阻断启动（配置写错不应导致服务起不来）。 */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification =
          "告警日志中的角色名来自部署方 YAML/环境变量配置（非请求体）；非法名仅用于启动诊断，"
              + "且随后被忽略不进入授权矩阵（镜像既有 CRLF_INJECTION_LOGS 落案模式）。")
  private static Set<Role> parseRoles(Set<String> raw) {
    Set<Role> parsed = new LinkedHashSet<>();
    if (raw == null) {
      return parsed;
    }
    for (String name : raw) {
      if (name == null || name.isBlank()) {
        continue;
      }
      try {
        parsed.add(Role.valueOf(name.strip().toUpperCase(java.util.Locale.ROOT)));
      } catch (IllegalArgumentException ex) {
        LOG.warn("忽略无法识别的角色名：{}（可选值 VIEWER/EDITOR/ADMIN）", name);
      }
    }
    return parsed;
  }
}
