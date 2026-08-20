package io.oryxos.core.memory;

/**
 * 记忆后端的检索能力三态（015 FR-009）——门面按此路由 recall，不猜实现：
 *
 * <ul>
 *   <li>{@link #KEYWORD}：后端只会关键词匹配，引擎补时间路（两路，即降级态的常态化）；
 *   <li>{@link #HYBRID_BUILTIN}：底座引擎跑三路加权融合（语义路读 memory_vectors）；
 *   <li>{@link #DELEGATED}：后端自带语义检索（如 mem0），recall 直通、底座不建索引。
 * </ul>
 *
 * <p>分区语义（core/archival）不在此声明——它是必选能力，无法兑现的实现在装配期被可读拒绝（Clarify-R2）。
 */
public enum MemoryRecallCapability {
  KEYWORD,
  HYBRID_BUILTIN,
  DELEGATED
}
