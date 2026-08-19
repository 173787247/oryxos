package io.oryxos.core.knowledge;

import java.nio.file.Path;

/**
 * 某知识库被一个 Agent 引用的事实（删除保护 FR-011 的证据行）。
 *
 * @param agentName Agent 名（AGENT.md frontmatter name，取不到时用目录名）
 * @param state 引用方状态
 * @param directoryName Agent 目录名
 * @param linkPath 绑定链接绝对路径
 */
public record KnowledgeReference(
    String agentName, AgentState state, String directoryName, Path linkPath) {

  /** 引用方 Agent 状态。 */
  public enum AgentState {
    ACTIVE,
    ARCHIVED
  }
}
