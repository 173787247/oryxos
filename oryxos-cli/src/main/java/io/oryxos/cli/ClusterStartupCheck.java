package io.oryxos.cli;

import io.oryxos.core.cluster.ClusterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 多副本误配 fail-fast（026 R8，仿 ProviderStartupCheck）：cluster.enabled=true 时校验组件组合——
 * 仅单机可用的组件（本地嵌入式库、本地文件记忆档、内存知识库）配上多副本必翻车（共享卷丢写/ 状态不共享），配置了却带病运行比启动失败更危险。SmartInitializingSingleton
 * 保证在端口打开前失败。
 */
@Component
public class ClusterStartupCheck implements SmartInitializingSingleton {

  private static final Logger LOG = LoggerFactory.getLogger(ClusterStartupCheck.class);

  private final ClusterProperties cluster;
  private final String datasourceUrl;
  private final String memoryBackend;
  private final String knowledgeStore;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "ClusterProperties 是 Spring 共享配置 Bean，本就不应防御性拷贝。")
  public ClusterStartupCheck(
      ClusterProperties cluster,
      @Value("${spring.datasource.url:jdbc:sqlite:oryxos.db}") String datasourceUrl,
      @Value("${memory.backend:markdown}") String memoryBackend,
      @Value("${knowledge.store:sqlite}") String knowledgeStore) {
    this.cluster = cluster;
    this.datasourceUrl = datasourceUrl;
    this.memoryBackend = memoryBackend;
    this.knowledgeStore = knowledgeStore;
  }

  @Override
  public void afterSingletonsInstantiated() {
    if (!cluster.isEnabled()) {
      return; // 单机档零校验零变化
    }
    if (datasourceUrl.startsWith("jdbc:sqlite:")) {
      throw new IllegalStateException(
          "oryxos.cluster.enabled=true 与 SQLite 数据库不兼容：SQLite 是单机档（单文件库多副本会坏库）。"
              + "请把 spring.datasource.url 指向共享 PostgreSQL（url + username + password 三项即可，"
              + "库类型自动识别），或关闭 oryxos.cluster.enabled");
    }
    if ("markdown".equals(memoryBackend)) {
      throw new IllegalStateException(
          "oryxos.cluster.enabled=true 与 memory.backend=markdown 不兼容：本地文件记忆档在多副本"
              + "并发追加下会丢写。请改为 memory.backend=sqlite（记忆落共享库）或 mem0，"
              + "或关闭 oryxos.cluster.enabled");
    }
    if ("memory".equals(knowledgeStore)) {
      throw new IllegalStateException(
          "oryxos.cluster.enabled=true 与 knowledge.store=memory 不兼容：内存知识库在副本间不共享。"
              + "请改为 knowledge.store=sqlite（知识落共享库），或关闭 oryxos.cluster.enabled");
    }
    LOG.info(
        "多副本模式启用: instance={} leaseTtl={} heartbeat={}",
        cluster.effectiveInstanceId(),
        cluster.getLeaseTtl(),
        cluster.effectiveHeartbeatInterval());
  }
}
