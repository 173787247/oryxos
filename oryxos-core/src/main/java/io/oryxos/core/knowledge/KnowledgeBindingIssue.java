package io.oryxos.core.knowledge;

import java.nio.file.Path;

/**
 * 一条非法知识库绑定的巡检结论（FR-002：与 Skill 绑定同一分类学）。
 *
 * @param agentName Agent 名
 * @param agentState Agent 目录状态
 * @param entryName 绑定项名
 * @param path 绑定项绝对路径
 * @param type 非法类别
 * @param message 可读说明
 */
public record KnowledgeBindingIssue(
    String agentName,
    AgentState agentState,
    String entryName,
    Path path,
    Type type,
    String message) {

  /** 非法绑定类别。 */
  public enum Type {
    /** 目标不存在。 */
    DANGLING,
    /** 绝对链接 / 真实目标越出知识库根。 */
    ESCAPED,
    /** 不是受控软连接 / 目标不是合法知识库目录。 */
    INVALID_TARGET,
    /** 链接名与目录名或清单 name 不一致。 */
    NAME_MISMATCH
  }

  /** Agent 目录状态。 */
  public enum AgentState {
    ACTIVE,
    ARCHIVED,
    /** 缺少有效 AGENT.md 的目录。 */
    INVALID
  }
}
