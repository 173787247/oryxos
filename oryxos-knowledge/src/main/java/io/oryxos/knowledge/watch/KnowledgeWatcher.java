package io.oryxos.knowledge.watch;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

import io.oryxos.core.knowledge.KnowledgeBackendRegistry;
import io.oryxos.core.knowledge.KnowledgeManifest;
import io.oryxos.knowledge.index.KnowledgeIndexService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// GitOps 录入路径（FR-010 / US4）：实时监听 .oryxos/knowledge/，库目录/文档的增改删收敛到
// KnowledgeIndexService.reconcile（指纹去重，重复触发廉价幂等）。骨架照 WorkspaceWatcher 的
// 「补挂」思路：根目录盯子库目录增删，库目录树递归补挂（WatchService 本身非递归，嵌套子目录
// 不补挂则其中文档的变更永不投递）；启动先全量对账（US4 场景 5：停机期间的目录变更由对账收敛）。
// 基础设施守护线程，不把异步引进请求链路（不违反宪法七）。
/** 实时监听 {@code .oryxos/knowledge/}，把知识库目录变更收敛到索引对账。 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "indexService/executor 是装配层注入的共享单例，构造注入共享同一引用正是意图。")
public class KnowledgeWatcher {

  private static final Logger LOG = LoggerFactory.getLogger(KnowledgeWatcher.class);

  private final Path knowledgeDir;
  private final KnowledgeIndexService indexService;
  private final Executor watcherExecutor;

  /**
   * start() 创建、stop() 关闭（volatile：start 在启动线程写、stop 在 Spring 关闭线程读）。 start 失败时保持 null，stop 静默跳过。
   */
  private volatile WatchService watchService;

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
          watchTreeQuietly(watchService, child); // 含既有嵌套子目录：WatchService 是浅的，必须逐层补挂
          reconcileQuietly(child); // 启动对账：停机期间的增改删在此收敛（FR-010）
        }
      }
    } catch (IOException e) {
      LOG.warn("KnowledgeWatcher 启动失败，知识库热加载不可用: {}", sanitize(e.getMessage()));
      return;
    }
    this.watchService = watchService;
    watcherExecutor.execute(() -> loop(watchService));
  }

  /**
   * 关闭 WatchService 令监听循环退出（take() 抛 ClosedWatchServiceException）。 必须在所属执行器的 SmartLifecycle
   * 停止之前调用——执行器的 stop 回调要等「运行中任务数归零」 才触发（Spring 6.2 ExecutorLifecycleDelegate 语义），监听循环不退出就等满 30s
   * 超时（#332）。 装配层以 ContextClosedEvent 监听器触发（该事件先于生命周期停机发布）。
   */
  public void stop() {
    WatchService ws = watchService;
    if (ws != null) {
      try {
        ws.close();
      } catch (IOException e) {
        LOG.warn("关闭 KnowledgeWatcher 失败: {}", sanitize(e.getMessage()));
      }
    }
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
      } catch (java.nio.file.ClosedWatchServiceException e) {
        return; // stop() 关闭了服务：正常退出路径（#332）
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
  //  - 根目录事件：子库目录新增（递归补挂监听 + 对账）与删除（清索引）；
  //  - 库目录事件：新建子目录递归补挂（WatchService 非递归，不补挂则嵌套文档改动永不投递），
  //    任何文档/清单变更 → 整库对账（指纹去重使其廉价幂等）。
  /** 把一个目录事件收敛到库级对账/清理。 */
  void dispatch(WatchService watchService, Path dir, Path changed, WatchEvent.Kind<?> kind) {
    if (knowledgeDir.equals(dir)) {
      if (kind == ENTRY_CREATE && Files.isDirectory(changed, LinkOption.NOFOLLOW_LINKS)) {
        watchTreeQuietly(watchService, changed);
        reconcileQuietly(changed);
      } else if (kind == ENTRY_DELETE) {
        removeBase(changed);
      }
      return;
    }
    if (kind == ENTRY_CREATE && Files.isDirectory(changed, LinkOption.NOFOLLOW_LINKS)) {
      watchTreeQuietly(watchService, changed); // 嵌套子目录（可能自带更深层）：整棵补挂
    }
    reconcileQuietly(dir); // 库目录内任何变更 → 整库对账
  }

  /** 递归补挂一棵目录树；目录软链不跟随（与索引侧 NOFOLLOW 口径一致），单层失败只告警。 */
  private void watchTreeQuietly(WatchService watchService, Path root) {
    try (Stream<Path> walk = Files.walk(root)) {
      walk.filter(dir -> Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS))
          .forEach(dir -> watchDirQuietly(watchService, dir));
    } catch (IOException | UncheckedIOException e) {
      LOG.warn(
          "监听知识库目录树 {} 失败：{}",
          sanitize(String.valueOf(root.getFileName())),
          sanitize(e.getMessage()));
    }
  }

  /** 单测探针：目录是否已挂监听（真实事件时序不可测，钉注册行为）。 */
  boolean watching(Path dir) {
    return watchedDirs.containsValue(dir);
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
