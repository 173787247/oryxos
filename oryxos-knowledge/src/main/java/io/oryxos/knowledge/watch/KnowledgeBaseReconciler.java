package io.oryxos.knowledge.watch;

import io.oryxos.core.knowledge.KnowledgeBackendRegistry;
import io.oryxos.core.knowledge.KnowledgeManifest;
import io.oryxos.knowledge.index.KnowledgeIndexService;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 知识库目录 → 索引对账的唯一边界：单库对账、启动全库对账、库删除清理。热加载（{@link KnowledgeWatcher}）与启动对账
 * 共用同一份语义，避免两条路径各写一遍清单过滤（非法清单 / 远程后端跳过）与告警口径。
 */
public final class KnowledgeBaseReconciler {

  private static final Logger LOG = LoggerFactory.getLogger(KnowledgeBaseReconciler.class);

  private final KnowledgeIndexService indexService;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "indexService 为装配层注入的共享单例，构造注入存同一引用正是意图（镜像 KnowledgeWatcher 的既有抑制）。")
  public KnowledgeBaseReconciler(KnowledgeIndexService indexService) {
    this.indexService = indexService;
  }

  /**
   * 启动全库对账（FR-010）：停机期间的增改删、以及被重启打断的 PENDING/INDEXING 都在此收敛。
   *
   * <p>调用方必须让它独立于监听设施的成功与否——inotify 额度耗尽时 WatchService 建不起来，但「重启后的首轮对账」不能跟着 跳过，否则那行 PENDING
   * 没有任何在飞任务，也没有别的机制把它拉回终态。单个库失败只告警，不阻断其余库。
   */
  public void reconcileAll(Path knowledgeDir) {
    if (!Files.isDirectory(knowledgeDir)) {
      return; // 没有知识库目录 = 没有待对账的库（集群档新工作区启动时会走到这里）
    }
    try (DirectoryStream<Path> children =
        Files.newDirectoryStream(knowledgeDir, Files::isDirectory)) {
      for (Path child : children) {
        reconcile(child, true);
      }
    } catch (IOException e) {
      LOG.warn("知识库启动对账失败: {}", sanitize(e.getMessage()));
    }
  }

  /** 单库对账：非法清单 / 远程后端跳过并告警，不拖垮调用方（US4 场景 3）。 */
  public void reconcile(Path kbDir, boolean afterRestart) {
    String name = String.valueOf(kbDir.getFileName());
    try {
      if (!Files.isDirectory(kbDir)) {
        return;
      }
      KnowledgeManifest manifest = KnowledgeManifest.read(kbDir); // 非法清单在此拦截 → WARN 不注册
      if (!KnowledgeBackendRegistry.LOCAL.equals(manifest.backend())) {
        return; // 远程后端库无本地索引，无需对账
      }
      indexService.reconcile(name, afterRestart);
    } catch (RuntimeException e) {
      LOG.warn("知识库目录 {} 对账跳过：{}", sanitize(name), sanitize(e.getMessage()));
    }
  }

  /** 库目录被删：索引与片段一并清（SC-006：删除的内容不再被命中）。 */
  public void removeBase(Path kbDir) {
    String name = String.valueOf(kbDir.getFileName());
    try {
      indexService.deleteBase(name);
    } catch (RuntimeException e) {
      LOG.warn("清理知识库 {} 索引失败：{}", sanitize(name), sanitize(e.getMessage()));
    }
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
