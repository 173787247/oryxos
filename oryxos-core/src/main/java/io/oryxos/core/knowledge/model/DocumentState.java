package io.oryxos.core.knowledge.model;

/** 文档索引状态机（Clarify-Q3 两段式上传）：同步校验失败不进状态机，入口即拒绝。 */
public enum DocumentState {
  /** 已通过同步校验，等待后台索引。 */
  PENDING,
  /** 后台虚拟线程切分/向量化中。 */
  INDEXING,
  /** 索引完成，可被检索命中。 */
  READY,
  /** 索引失败，携带可读原因，可重试。 */
  FAILED
}
