package io.oryxos.core.knowledge.model;

/**
 * 后端插件能力声明（FR-006）：检索是必选契约不在此声明；管理操作逐项可选。 未声明能力的调用必须在入口被可读拒绝，规避 AgentScope
 * 「写操作运行时抛异常」的契约谎言（research D9）。
 *
 * @param createDelete 支持建库/删库
 * @param importDocs 支持文档导入
 * @param rebuild 支持重建索引
 * @param status 支持索引状态查询
 * @param rerank 精排能力位（声明后其结果直通流水线精排槽位，FR-004）
 */
public record KnowledgeCapabilities(
    boolean createDelete, boolean importDocs, boolean rebuild, boolean status, boolean rerank) {

  /** 内置本地后端：全管理能力，v1 无精排（research D10）。 */
  public static KnowledgeCapabilities localFull() {
    return new KnowledgeCapabilities(true, true, true, true, false);
  }

  /** 仅检索能力（典型远程只读后端 / 测试桩）。 */
  public static KnowledgeCapabilities retrieveOnly() {
    return new KnowledgeCapabilities(false, false, false, false, false);
  }
}
