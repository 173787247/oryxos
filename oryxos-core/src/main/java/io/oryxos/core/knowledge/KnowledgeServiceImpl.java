package io.oryxos.core.knowledge;

import io.oryxos.core.knowledge.model.DocumentState;
import io.oryxos.core.knowledge.model.DocumentStatus;
import io.oryxos.core.knowledge.model.KnowledgeBaseInfo;
import io.oryxos.core.knowledge.model.KnowledgeHit;
import io.oryxos.core.knowledge.model.KnowledgeQuery;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 知识门面默认实现：检索范围 = 发起 Agent 的有效绑定（FR-004），按各库清单的 backend 声明路由插件， 跨库/跨后端融合取全局 top-K（FR-020 /
 * Clarify-Q2）。列表投影供管理台 / CLI / REST 共用。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "bindings/registry 为装配层注入的共享单例，构造注入存同一引用正是意图。")
public class KnowledgeServiceImpl implements KnowledgeService {

  private static final Logger LOG = LoggerFactory.getLogger(KnowledgeServiceImpl.class);

  private final Path knowledgeRoot;
  private final KnowledgeBindingService bindings;
  private final KnowledgeBackendRegistry registry;

  public KnowledgeServiceImpl(
      Path knowledgeRoot, KnowledgeBindingService bindings, KnowledgeBackendRegistry registry) {
    this.knowledgeRoot = knowledgeRoot.toAbsolutePath().normalize();
    this.bindings = bindings;
    this.registry = registry;
  }

  @Override
  public List<KnowledgeHit> retrieveForAgent(
      String agentName, String query, Integer topK, String kbNameOrNull) {
    List<BoundKnowledgeDescriptor> bound = bindings.inspect(agentName).bindings();
    if (bound.isEmpty()) {
      throw new IllegalArgumentException("当前 Agent 未绑定任何知识库；请先在管理面完成绑定再检索");
    }
    Set<String> boundNames = new LinkedHashSet<>();
    bound.forEach(binding -> boundNames.add(binding.name()));
    List<String> targets;
    if (kbNameOrNull == null || kbNameOrNull.isBlank()) {
      targets = List.copyOf(boundNames);
    } else if (boundNames.contains(kbNameOrNull)) {
      targets = List.of(kbNameOrNull);
    } else {
      throw new IllegalArgumentException(
          "知识库不存在或未绑定当前 Agent: " + kbNameOrNull + "（已绑定: " + String.join("、", boundNames) + "）");
    }
    int limit = topK == null || topK <= 0 ? KnowledgeQuery.DEFAULT_TOP_K : topK;
    // 按后端声明分组路由（同一后端一次查询，内部已做跨库融合），跨后端再统一融合取全局 top-K
    Map<String, List<String>> byBackend = new LinkedHashMap<>();
    for (String kbName : targets) {
      String backend = KnowledgeManifest.read(knowledgeRoot.resolve(kbName)).backend();
      byBackend.computeIfAbsent(backend, key -> new ArrayList<>()).add(kbName);
    }
    List<KnowledgeHit> all = new ArrayList<>();
    for (Map.Entry<String, List<String>> group : byBackend.entrySet()) {
      KnowledgeBackend backend =
          registry
              .byName(group.getKey())
              .orElseThrow(() -> new IllegalArgumentException("知识库后端未注册: " + group.getKey()));
      all.addAll(backend.retrieve(new KnowledgeQuery(query, limit, group.getValue())));
    }
    return all.stream()
        .sorted(Comparator.comparingDouble(KnowledgeHit::score).reversed())
        .limit(limit)
        .toList();
  }

  @Override
  public List<KnowledgeBaseInfo> listBases() {
    if (!Files.isDirectory(knowledgeRoot, LinkOption.NOFOLLOW_LINKS)) {
      return List.of();
    }
    List<KnowledgeBaseInfo> bases = new ArrayList<>();
    try (Stream<Path> dirs = Files.list(knowledgeRoot)) {
      dirs.filter(Files::isDirectory)
          .sorted()
          .forEach(
              dir -> {
                try {
                  bases.add(describe(KnowledgeManifest.read(dir)));
                } catch (RuntimeException e) {
                  // 非法目录不注册、告警、不影响其他库（US4 场景 3）
                  LOG.warn(
                      "跳过非法知识库目录 {}: {}",
                      sanitize(String.valueOf(dir.getFileName())),
                      sanitize(e.getMessage()));
                }
              });
    } catch (IOException e) {
      throw new UncheckedIOException("扫描知识库根目录失败: " + knowledgeRoot, e);
    }
    return List.copyOf(bases);
  }

  private KnowledgeBaseInfo describe(KnowledgeManifest manifest) {
    List<DocumentStatus> statuses =
        registry
            .byName(manifest.backend())
            .filter(backend -> backend.capabilities().status())
            .flatMap(KnowledgeBackend::admin)
            .map(admin -> admin.status(manifest.name()))
            .orElse(List.of());
    int chunkCount = statuses.stream().mapToInt(DocumentStatus::chunkCount).sum();
    Instant lastIndexedAt =
        statuses.stream()
            .map(DocumentStatus::indexedAt)
            .filter(indexedAt -> indexedAt != null)
            .max(Comparator.naturalOrder())
            .orElse(null);
    return new KnowledgeBaseInfo(
        manifest.name(),
        manifest.description(),
        manifest.backend(),
        statuses.size(),
        chunkCount,
        aggregateStatus(statuses),
        lastIndexedAt);
  }

  private static String aggregateStatus(List<DocumentStatus> statuses) {
    if (statuses.isEmpty()) {
      return "空";
    }
    if (statuses.stream().anyMatch(status -> status.state() == DocumentState.FAILED)) {
      return "失败";
    }
    boolean inProgress =
        statuses.stream()
            .anyMatch(
                status ->
                    status.state() == DocumentState.PENDING
                        || status.state() == DocumentState.INDEXING);
    return inProgress ? "索引中" : "就绪";
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
