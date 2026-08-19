package io.oryxos.knowledge.watch;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

import io.oryxos.core.knowledge.KnowledgeBackendRegistry;
import io.oryxos.core.knowledge.KnowledgeManifest;
import io.oryxos.knowledge.index.KnowledgeIndexService;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// GitOps 录入路径（FR-010 / US4）：实时监听 .oryxos/knowledge/，库目录/文档的增改删收敛到
// KnowledgeIndexService.reconcile（指纹去重，重复触发廉价幂等）。骨架照 WorkspaceWatcher 的
// 「非递归补挂」：根目录盯子库目录增删，每个库目录各自盯文档变更；启动先全量对账（US4 场景 5：
// 停机期间的目录变更由对账收敛）。基础设施守护线程，不把异步引进请求链路（不违反宪法七）。
/** 实时监听 {@code .oryxos/knowledge/}，把知识库目录变更收敛到索引对账。 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "indexService/executor 是装配层注入的共享单例，构造注入共享同一引用正是意图。")
public class KnowledgeWatcher {

  private static final Logger LOG = LoggerFactory.getLogger(KnowledgeWatcher.class);

  private final Path knowledgeDir;
  private final KnowledgeIndexService indexService;
  private final Executor watcherExecutor;

  /** {@link WatchKey} → 被监听目录（根目录或某个库目录），把事件解析回来源目录。 */
  private final Map<WatchKey, Path> watchedDirs = new ConcurrentHashMap<>();

  public KnowledgeWatcher(
      Path oryxosRoot, KnowledgeIndexService indexService, Executor watcherExecutor) {
    this.knowledgeDir = oryxosRoot.resolve("knowledge");
    this.indexService = indexService;
    this.watcherExecutor = watcherExecutor;
  }

  /** 装配层 {@code @Bean(initMethod="start")} 调用：启动对账 + 守护线程监听循环。 */
  public void start() {
    WatchService watchService;
    try {
      Files.createDirectories(knowledgeDir);
      watchService = knowledgeDir.getFileSystem().newWatchService();
      registerDir(watchService, knowledgeDir);
      try (DirectoryStream<Path> children =
          Files.newDirectoryStream(knowledgeDir, Files::isDirectory)) {
        for (Path child : children) {
          registerDir(watchService, child);
          reconcileQuietly(child); // 启动对账：停机期间的增改删在此收敛（FR-010）
        }
      }
    } catch (IOException e) {
      LOG.warn("KnowledgeWatcher 启动失败，知识库热加载不可用: {}", sanitize(e.getMessage()));
      return;
    }
    watcherExecutor.execute(() -> loop(watchService));
  }

  private void registerDir(WatchService watchService, Path dir) throws IOException {
    WatchKey key = dir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
    watchedDirs.put(key, dir);
  }

  private void loop(WatchService watchService) {
    while (true) {
      WatchKey key;
      try {
        key = watchService.take();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      Path dir = watchedDirs.get(key);
      if (dir != null) {
        for (WatchEvent<?> event : key.pollEvents()) {
          if (event.context() instanceof Path relative) {
            dispatch(watchService, dir, dir.resolve(relative), event.kind());
          }
        }
      }
      if (!key.reset()) {
        watchedDirs.remove(key);
        if (knowledgeDir.equals(dir)) {
          return; // 根目录不可用：整体退出监听线程
        }
        if (dir != null) {
          removeBase(dir); // 库目录被删：清索引
        }
      }
    }
  }

  // 把一个目录事件收敛到库级动作。包级可见供单测直接调（不依赖真实事件时序）。
  //  - 根目录事件：子库目录新增（补挂监听 + 对账）与删除（清索引）；
  //  - 库目录事件：任何文档/清单变更 → 整库对账（指纹去重使其廉价幂等，天然覆盖嵌套子目录文档）。
  /** 把一个目录事件收敛到库级对账/清理。 */
  void dispatch(WatchService watchService, Path dir, Path changed, WatchEvent.Kind<?> kind) {
    if (knowledgeDir.equals(dir)) {
      if (kind == ENTRY_CREATE && Files.isDirectory(changed)) {
        watchDirQuietly(watchService, changed);
        reconcileQuietly(changed);
      } else if (kind == ENTRY_DELETE) {
        removeBase(changed);
      }
      return;
    }
    reconcileQuietly(dir); // 库目录内任何变更 → 整库对账
  }

  private void watchDirQuietly(WatchService watchService, Path dir) {
    try {
      registerDir(watchService, dir);
    } catch (IOException e) {
      LOG.warn(
          "监听知识库目录 {} 失败：{}",
          sanitize(String.valueOf(dir.getFileName())),
          sanitize(e.getMessage()));
    }
  }

  /** 单库对账：非法清单 / 远程后端跳过并告警，不拖垮监听（US4 场景 3）。包级可见供单测直接调。 */
  void reconcileQuietly(Path kbDir) {
    String name = String.valueOf(kbDir.getFileName());
    try {
      if (!Files.isDirectory(kbDir)) {
        return;
      }
      KnowledgeManifest manifest = KnowledgeManifest.read(kbDir); // 非法清单在此拦截 → WARN 不注册
      if (!KnowledgeBackendRegistry.LOCAL.equals(manifest.backend())) {
        return; // 远程后端库无本地索引，无需对账
      }
      indexService.reconcile(name);
    } catch (RuntimeException e) {
      LOG.warn("知识库目录 {} 对账跳过：{}", sanitize(name), sanitize(e.getMessage()));
    }
  }

  /** 库目录被删：索引与片段一并清（SC-006：删除的内容不再被命中）。包级可见供单测直接调。 */
  void removeBase(Path kbDir) {
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
