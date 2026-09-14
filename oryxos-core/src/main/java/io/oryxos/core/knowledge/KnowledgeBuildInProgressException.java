package io.oryxos.core.knowledge;

/** 027：知识库索引重建已由其他副本认领执行中——本次触发不重复构建（恰好一次）。 web 层映射 409，message 面向管理员可读（提示稍后重试或等待当前构建完成）。 */
public class KnowledgeBuildInProgressException extends RuntimeException {

  public KnowledgeBuildInProgressException(String message) {
    super(message);
  }
}
