package io.oryxos.cli;

import io.oryxos.core.policy.ToolPolicyService;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.storage.ToolPolicyRule;
import io.oryxos.storage.ToolPolicyServiceImpl;
import io.oryxos.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * 启动校验（020-tool-policy，FR-008/FR-011）：扫描存量策略规则，两类异常只 WARN 不阻断（镜像 018 ApiKeyStartupCheck
 * 的口径——异常策略是「配置待清理」不是「坏状态」，且 MCP 工具可能后接入）：
 *
 * <ol>
 *   <li>规则 pattern 指向未知目标（既不是已注册工具、也不是已连接 MCP server 的通配）或 agent_name 指向不存在的 Agent；
 *   <li>策略导致某 Agent 的有效工具集全空（Agent 仍可纯对话运行，但大概率非管理员本意）。
 * </ol>
 *
 * <p>放 oryxos-cli：检查同时需要 ToolRegistry（oryxos-tool）与 ProfileRegistry/策略（core/storage）， 而 web 不依赖
 * tool 模块——由 {@code OryxOsRuntime} 以 {@code @Bean + @ConditionalOnWebApplication(SERVLET)} 装配，仅
 * serve/gateway 模式运行，CLI 管理命令不受影响。
 */
public class ToolPolicyStartupCheck implements ApplicationRunner {

  private static final Logger LOG = LoggerFactory.getLogger(ToolPolicyStartupCheck.class);

  private static final String WILDCARD_SUFFIX = ":*";

  private final ToolPolicyService policy;
  private final ProfileRegistry profileRegistry;
  private final ToolRegistry toolRegistry;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "三者均为 Spring 注入的共享单例，构造注入存同一引用正是意图（镜像 ApiKeyStartupCheck 模式）。")
  public ToolPolicyStartupCheck(
      ToolPolicyService policy, ProfileRegistry profileRegistry, ToolRegistry toolRegistry) {
    this.policy = policy;
    this.profileRegistry = profileRegistry;
    this.toolRegistry = toolRegistry;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!(policy instanceof ToolPolicyServiceImpl store)) {
      LOG.debug("Tool policy store 未装配为 JPA 实现，启动检查跳过");
      return;
    }
    for (ToolPolicyRule rule : store.loadAll()) {
      warnUnknownTargets(rule);
    }
    warnEmptyEffectiveSets();
  }

  private void warnUnknownTargets(ToolPolicyRule rule) {
    String pattern = rule.getPattern();
    if (pattern != null && pattern.endsWith(WILDCARD_SUFFIX)) {
      String server = pattern.substring(0, pattern.length() - WILDCARD_SUFFIX.length());
      if (!toolRegistry.mcpToolOwners().containsValue(server)) {
        LOG.warn("工具策略规则 #{} 的通配 {} 未匹配任何已连接的 MCP server（可能尚未接入或已卸载）", rule.getId(), s(pattern));
      }
    } else if (pattern != null && !toolRegistry.contains(pattern)) {
      LOG.warn("工具策略规则 #{} 指向未注册的工具 {}（可能拼写有误或工具已移除）", rule.getId(), s(pattern));
    }
    String agent = rule.getAgentName();
    if (agent != null && profileRegistry.get(agent).isEmpty()) {
      LOG.warn("工具策略规则 #{} 指向不存在的 Agent {}", rule.getId(), s(agent));
    }
  }

  private void warnEmptyEffectiveSets() {
    for (Profile profile : profileRegistry.all()) {
      if (profile.tools().isEmpty()) {
        continue; // 本就未声明工具的 Agent 不告警
      }
      if (policy.filterAllowed(profile.name(), profile.tools()).isEmpty()) {
        LOG.warn(
            "工具策略使 Agent {} 的有效工具集为空（声明 {} 个工具全部被禁）——Agent 将以纯对话方式运行",
            s(profile.name()),
            profile.tools().size());
      }
    }
  }

  /** 日志参数消毒：去掉换行，防日志伪造。 */
  private static String s(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
