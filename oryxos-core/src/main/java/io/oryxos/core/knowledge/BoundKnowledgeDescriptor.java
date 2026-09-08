package io.oryxos.core.knowledge;

import java.nio.file.Path;

/**
 * 一条有效的 Agent 知识库绑定（渐进披露只消费 name + description，FR-005）。
 *
 * @param name 库名
 * @param description 库描述
 * @param linkPath Agent 本地绑定链接的绝对路径
 * @param targetDir 公共知识库目录的真实路径
 */
public record BoundKnowledgeDescriptor(
    String name, String description, Path linkPath, Path targetDir) {}
