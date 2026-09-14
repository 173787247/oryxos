package io.oryxos.core.policy;

/**
 * 授权动作（039-identity-authorization）：主体「想做什么」的受控词表。
 *
 * <p>为什么用枚举而不是字符串：动作是授权矩阵的横轴，一旦允许自由字符串，就会出现 {@code "manage_agent"} / {@code "MANAGE_AGENTS"} /
 * {@code "agent.manage"} 三种写法各判一次， 而漏判的那一种默认是放行——授权系统里最危险的形态不是判错，是没判。枚举让编译器承担词表收敛。
 *
 * <p>与既有 {@link ToolPolicyService} 的关系：那是「Agent 能不能用某个工具」的减法层，主体是 Agent 名； 本枚举是「谁能不能做这件事」的授权层，主体是
 * {@link io.oryxos.core.auth.Principal}。两者正交、叠加生效， 不要互相替代。
 *
 * <p>命名口径：{@code READ_*} 为只读，{@code MANAGE_*} 含增删改；{@code RUN_AGENT} 单列，因为「让 Agent 干活」 与「改 Agent
 * 定义」是两种风险。
 */
public enum Action {

  /** 只读工作区（列 Agent、会话、资源清单）。 */
  READ_WORKSPACE,

  /** 管理工作区本身（改名、迁移、删除这类影响边界整体状态的动作）。 */
  MANAGE_WORKSPACE,

  /** 触发 Agent 执行（跑一轮会话 / 无状态调用）。不含修改 Agent 定义。 */
  RUN_AGENT,

  /** 管理 Agent：创建、改 AGENT.md、删。 */
  MANAGE_AGENTS,

  /** 管理知识库：导入、重建索引、删文档。 */
  MANAGE_KNOWLEDGE,

  /** 管理 Skill：安装、改 SKILL.md、删、绑定到 Agent。 */
  MANAGE_SKILLS,

  /** 管理渠道：启停、改渠道配置。 */
  MANAGE_CHANNELS,

  /** 管理治理策略：工具策略、沙箱白名单这类「约束别人的规则」。 */
  MANAGE_POLICIES,

  /** 读审计数据（llm_calls / tool_invocations / 执行历史）。 */
  READ_AUDIT,

  /** 管理会话：查看、归档、删除会话。 */
  MANAGE_SESSIONS,

  /** 管理成员：把人/Key 加入边界并授予角色。 */
  MANAGE_MEMBERS
}
