package io.oryxos.core.knowledge;

import io.oryxos.core.fs.RealPathBoundary;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent 知识库绑定的文件系统协调者（FR-002）：{@code agents/<agent>/knowledge/<kb>} 指向 {@code
 * ../../../knowledge/<kb>} 的受控相对软连接是绑定的唯一事实来源，AGENT.md frontmatter 不声明知识库。范式与 {@code
 * AgentSkillBindingService} 同构——同一分类学、同一真实路径门禁。
 */
public class KnowledgeBindingService {

  private static final Logger LOG = LoggerFactory.getLogger(KnowledgeBindingService.class);
  private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_-]+");
  private static final String AGENT_FILE = "AGENT.md";
  private static final String LINKS_DIR = "knowledge";

  private final Path agentsDir;
  private final Path archiveDir;
  private final Path knowledgeDir;

  public KnowledgeBindingService(Path oryxosRoot) {
    Path root = oryxosRoot.toAbsolutePath().normalize();
    this.agentsDir = root.resolve("agents");
    this.archiveDir = root.resolve("archive");
    this.knowledgeDir = root.resolve(LINKS_DIR);
  }

  /** 创建固定相对链接；重复绑定同一有效库幂等。 */
  public synchronized BoundKnowledgeDescriptor bind(String agentName, String kbName) {
    String agent = safe(agentName, "Agent");
    String kb = safe(kbName, "知识库");
    Path agentDir = requireAgent(agent);
    requireKnowledgeBase(kb);
    Path linksDir = requireRealLinksDir(agentDir);
    Path link = linksDir.resolve(kb);
    if (!Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
      try {
        Files.createDirectories(linksDir);
        Files.createSymbolicLink(link, expectedTarget(kb));
      } catch (IOException e) {
        throw new UncheckedIOException("创建 Agent 知识库绑定失败: " + agent + "/" + kb, e);
      }
    }
    return inspect(agent).bindings().stream()
        .filter(binding -> binding.name().equals(kb))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("知识库绑定位置被无效条目占用或未通过校验: " + link));
  }

  /** 只删除受控固定链接；不存在幂等。 */
  public synchronized void unbind(String agentName, String kbName) {
    String agent = safe(agentName, "Agent");
    String kb = safe(kbName, "知识库");
    Path link = requireRealLinksDir(requireAgent(agent)).resolve(kb);
    if (!Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    requireControlledLink(link, kb);
    try {
      Files.delete(link);
    } catch (IOException e) {
      throw new UncheckedIOException("解绑 Agent 知识库失败: " + agent + "/" + kb, e);
    }
  }

  /** 整体替换一个 Agent 的绑定集合；I/O 失败回滚本次新增，损坏绑定先修复再替换。 */
  public synchronized KnowledgeBindingInspection replaceBindings(
      String agentName, List<String> desiredKbs) {
    String agent = safe(agentName, "Agent");
    requireAgent(agent);
    List<String> desired = normalizedNames(desiredKbs);
    desired.forEach(this::requireKnowledgeBase);
    KnowledgeBindingInspection before = inspect(agent);
    if (!before.issues().isEmpty()) {
      throw new IllegalArgumentException("Agent 存在损坏的知识库绑定，修复后才能整体替换: " + agent);
    }
    Set<String> current = new LinkedHashSet<>();
    before.bindings().forEach(binding -> current.add(binding.name()));
    if (current.equals(new LinkedHashSet<>(desired))) {
      return before;
    }
    List<String> created = new ArrayList<>();
    try {
      for (String kb : desired) {
        if (!current.contains(kb)) {
          bind(agent, kb);
          created.add(kb);
        }
      }
      for (String kb : current) {
        if (!desired.contains(kb)) {
          unbind(agent, kb);
        }
      }
      return inspect(agent);
    } catch (RuntimeException e) {
      for (String kb : created) {
        try {
          unbind(agent, kb);
        } catch (RuntimeException cleanupFailure) {
          LOG.warn("回滚知识库绑定失败: {}/{}", sanitize(agent), sanitize(kb));
        }
      }
      throw e;
    }
  }

  /** 每次返回新的稳定快照；无缓存参与。 */
  public KnowledgeBindingInspection inspect(String agentName) {
    String agent = safe(agentName, "Agent");
    Path agentDir = agentsDir.resolve(agent);
    if (!Files.isDirectory(agentDir, LinkOption.NOFOLLOW_LINKS)) {
      return new KnowledgeBindingInspection(List.of(), List.of());
    }
    KnowledgeBindingIssue.AgentState state =
        Files.isRegularFile(agentDir.resolve(AGENT_FILE))
            ? KnowledgeBindingIssue.AgentState.ACTIVE
            : KnowledgeBindingIssue.AgentState.INVALID;
    return inspectDirectory(agent, agentDir, state);
  }

  /** 找出活跃与归档 Agent 中对某库的全部受控引用（含 dangling），供删除保护。 */
  public synchronized List<KnowledgeReference> references(String kbName) {
    String kb = safe(kbName, "知识库");
    List<KnowledgeReference> references = new ArrayList<>();
    collectReferences(agentsDir, KnowledgeReference.AgentState.ACTIVE, kb, references, false);
    collectReferences(archiveDir, KnowledgeReference.AgentState.ARCHIVED, kb, references, true);
    return references.stream()
        .sorted(
            Comparator.comparing(KnowledgeReference::agentName)
                .thenComparing(reference -> reference.state().name())
                .thenComparing(KnowledgeReference::directoryName))
        .toList();
  }

  /** 删除保护（FR-011）：仍被引用则抛 {@link KnowledgeReferencedException}，不制造悬空软连接。 */
  public synchronized void ensureDeletable(String kbName) {
    List<KnowledgeReference> refs = references(kbName);
    if (!refs.isEmpty()) {
      throw new KnowledgeReferencedException(kbName, refs);
    }
  }

  /** 全工作区巡检：活跃 + 归档 Agent 的全部绑定项，稳定排序。 */
  public synchronized List<KnowledgeBindingIssue> reconcile() {
    List<KnowledgeBindingIssue> issues = new ArrayList<>();
    scanContainer(agentsDir, KnowledgeBindingIssue.AgentState.ACTIVE, false, issues);
    scanContainer(archiveDir, KnowledgeBindingIssue.AgentState.ARCHIVED, true, issues);
    return issues.stream()
        .sorted(
            Comparator.comparing(KnowledgeBindingIssue::agentName)
                .thenComparing(issue -> issue.agentState().name())
                .thenComparing(KnowledgeBindingIssue::entryName)
                .thenComparing(issue -> issue.type().name()))
        .toList();
  }

  private KnowledgeBindingInspection inspectDirectory(
      String agent, Path agentDir, KnowledgeBindingIssue.AgentState state) {
    List<BoundKnowledgeDescriptor> valid = new ArrayList<>();
    List<KnowledgeBindingIssue> issues = new ArrayList<>();
    Path linksDir = agentDir.resolve(LINKS_DIR);
    if (!Files.isDirectory(linksDir, LinkOption.NOFOLLOW_LINKS)) {
      return new KnowledgeBindingInspection(valid, issues);
    }
    if (state == KnowledgeBindingIssue.AgentState.INVALID) {
      issues.add(
          issue(
              agent,
              state,
              LINKS_DIR,
              linksDir,
              KnowledgeBindingIssue.Type.INVALID_TARGET,
              "Agent 目录缺少有效 AGENT.md"));
      return new KnowledgeBindingInspection(valid, issues);
    }
    try (Stream<Path> entries = Files.list(linksDir)) {
      entries.sorted().forEach(entry -> inspectEntry(agent, state, entry, valid, issues));
    } catch (IOException e) {
      issues.add(
          issue(
              agent,
              state,
              LINKS_DIR,
              linksDir,
              KnowledgeBindingIssue.Type.INVALID_TARGET,
              "无法读取 Agent knowledge 目录: " + e.getMessage()));
    }
    return new KnowledgeBindingInspection(
        valid.stream().sorted(Comparator.comparing(BoundKnowledgeDescriptor::name)).toList(),
        issues);
  }

  private void inspectEntry(
      String agent,
      KnowledgeBindingIssue.AgentState state,
      Path entry,
      List<BoundKnowledgeDescriptor> valid,
      List<KnowledgeBindingIssue> issues) {
    String entryName = String.valueOf(entry.getFileName());
    if (!SAFE_NAME.matcher(entryName).matches() || !Files.isSymbolicLink(entry)) {
      issues.add(
          issue(
              agent,
              state,
              entryName,
              entry,
              KnowledgeBindingIssue.Type.INVALID_TARGET,
              "绑定项必须是安全命名的相对软连接"));
      return;
    }
    if (!validateLexicalTarget(agent, state, entryName, entry, issues)) {
      return;
    }
    Path targetReal = resolveRealTarget(agent, state, entryName, entry, issues);
    if (targetReal == null) {
      return;
    }
    inspectManifest(agent, state, entryName, entry, targetReal, valid, issues);
  }

  private boolean validateLexicalTarget(
      String agent,
      KnowledgeBindingIssue.AgentState state,
      String entryName,
      Path entry,
      List<KnowledgeBindingIssue> issues) {
    Path rawTarget;
    try {
      rawTarget = Files.readSymbolicLink(entry);
    } catch (IOException e) {
      issues.add(
          issue(
              agent,
              state,
              entryName,
              entry,
              KnowledgeBindingIssue.Type.INVALID_TARGET,
              "无法读取软连接: " + e.getMessage()));
      return false;
    }
    if (rawTarget.isAbsolute()) {
      issues.add(
          issue(agent, state, entryName, entry, KnowledgeBindingIssue.Type.ESCAPED, "绑定必须使用相对软连接"));
      return false;
    }
    Path parent = entry.getParent();
    Path lexicalTarget =
        parent == null ? null : parent.resolve(rawTarget).toAbsolutePath().normalize();
    if (lexicalTarget == null
        || !lexicalTarget.startsWith(knowledgeDir)
        || !rawTarget.equals(expectedTarget(entryName))) {
      KnowledgeBindingIssue.Type type;
      if (lexicalTarget == null || !lexicalTarget.startsWith(knowledgeDir)) {
        type = KnowledgeBindingIssue.Type.ESCAPED;
      } else if (!entryName.equals(String.valueOf(rawTarget.getFileName()))) {
        type = KnowledgeBindingIssue.Type.NAME_MISMATCH;
      } else {
        type = KnowledgeBindingIssue.Type.INVALID_TARGET;
      }
      issues.add(
          issue(
              agent,
              state,
              entryName,
              entry,
              type,
              "绑定目标不是固定相对路径 ../../../knowledge/" + entryName));
      return false;
    }
    if (!Files.exists(entry)) {
      issues.add(
          issue(agent, state, entryName, entry, KnowledgeBindingIssue.Type.DANGLING, "绑定目标不存在"));
      return false;
    }
    return true;
  }

  private Path resolveRealTarget(
      String agent,
      KnowledgeBindingIssue.AgentState state,
      String entryName,
      Path entry,
      List<KnowledgeBindingIssue> issues) {
    try {
      Path targetReal = entry.toRealPath();
      RealPathBoundary.requireWithin(knowledgeDir, targetReal);
      return targetReal;
    } catch (RuntimeException | IOException e) {
      issues.add(
          issue(
              agent,
              state,
              entryName,
              entry,
              KnowledgeBindingIssue.Type.ESCAPED,
              "绑定真实目标越过知识库根或无法解析"));
      return null;
    }
  }

  private void inspectManifest(
      String agent,
      KnowledgeBindingIssue.AgentState state,
      String entryName,
      Path entry,
      Path targetReal,
      List<BoundKnowledgeDescriptor> valid,
      List<KnowledgeBindingIssue> issues) {
    if (!Files.isDirectory(targetReal)
        || !Files.isRegularFile(targetReal.resolve(KnowledgeManifest.FILE))) {
      issues.add(
          issue(
              agent,
              state,
              entryName,
              entry,
              KnowledgeBindingIssue.Type.INVALID_TARGET,
              "绑定目标不是含 " + KnowledgeManifest.FILE + " 的目录"));
      return;
    }
    if (!entryName.equals(String.valueOf(targetReal.getFileName()))) {
      issues.add(
          issue(
              agent,
              state,
              entryName,
              entry,
              KnowledgeBindingIssue.Type.NAME_MISMATCH,
              "链接名与知识库目录名不一致"));
      return;
    }
    try {
      KnowledgeManifest manifest = KnowledgeManifest.read(targetReal);
      valid.add(
          new BoundKnowledgeDescriptor(
              manifest.name(),
              manifest.description(),
              entry.toAbsolutePath().normalize(),
              targetReal));
    } catch (RuntimeException e) {
      KnowledgeBindingIssue.Type type =
          e.getMessage() != null && e.getMessage().contains("不一致")
              ? KnowledgeBindingIssue.Type.NAME_MISMATCH
              : KnowledgeBindingIssue.Type.INVALID_TARGET;
      issues.add(issue(agent, state, entryName, entry, type, e.getMessage()));
    }
  }

  private void scanContainer(
      Path container,
      KnowledgeBindingIssue.AgentState expectedState,
      boolean skipReservedDirs,
      List<KnowledgeBindingIssue> issues) {
    if (!Files.isDirectory(container, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try (Stream<Path> dirs = Files.list(container)) {
      dirs.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
          .filter(path -> !skipReservedDirs || !"skills".equals(String.valueOf(path.getFileName())))
          .sorted()
          .forEach(
              dir -> {
                String name = String.valueOf(dir.getFileName());
                KnowledgeBindingIssue.AgentState state =
                    Files.isRegularFile(dir.resolve(AGENT_FILE))
                        ? expectedState
                        : KnowledgeBindingIssue.AgentState.INVALID;
                issues.addAll(inspectDirectory(name, dir, state).issues());
              });
    } catch (IOException e) {
      throw new UncheckedIOException("扫描 Agent 知识库绑定失败: " + container, e);
    }
  }

  private void collectReferences(
      Path container,
      KnowledgeReference.AgentState state,
      String kb,
      List<KnowledgeReference> output,
      boolean skipReservedDirs) {
    if (!Files.isDirectory(container, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try (Stream<Path> dirs = Files.list(container)) {
      dirs.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
          .filter(path -> !skipReservedDirs || !"skills".equals(String.valueOf(path.getFileName())))
          .sorted()
          .forEach(
              dir -> {
                Path link = dir.resolve(LINKS_DIR).resolve(kb);
                if (!Files.isSymbolicLink(link)) {
                  return;
                }
                try {
                  if (!Files.readSymbolicLink(link).equals(expectedTarget(kb))) {
                    return;
                  }
                } catch (IOException e) {
                  return;
                }
                String directoryName = String.valueOf(dir.getFileName());
                output.add(
                    new KnowledgeReference(
                        agentName(dir, directoryName),
                        state,
                        directoryName,
                        link.toAbsolutePath().normalize()));
              });
    } catch (IOException e) {
      throw new UncheckedIOException("扫描知识库引用失败: " + container, e);
    }
  }

  private static String agentName(Path directory, String fallback) {
    Path file = directory.resolve(AGENT_FILE);
    if (!Files.isRegularFile(file)) {
      return fallback;
    }
    try {
      for (String line : Files.readAllLines(file)) {
        String stripped = line.strip();
        if (stripped.startsWith("name:")) {
          String name = stripped.substring("name:".length()).strip();
          return name.isBlank() ? fallback : name;
        }
      }
    } catch (IOException | RuntimeException e) {
      return fallback;
    }
    return fallback;
  }

  /** knowledge/ 绑定目录若被替换为越界软连接，写操作前拒绝（与 Skill skills/ 同款门禁）。 */
  private Path requireRealLinksDir(Path agentDir) {
    Path linksDir = agentDir.resolve(LINKS_DIR);
    if (Files.exists(linksDir, LinkOption.NOFOLLOW_LINKS)
        && !RealPathBoundary.isWithin(agentDir, linksDir)) {
      throw new IllegalArgumentException(
          "Agent knowledge/ 目录真实路径越界（疑似被替换为符号链接），拒绝操作: " + sanitize(linksDir.toString()));
    }
    return linksDir;
  }

  private Path requireAgent(String agent) {
    Path dir = agentsDir.resolve(agent);
    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)
        || !Files.isRegularFile(dir.resolve(AGENT_FILE))) {
      throw new IllegalArgumentException("Agent 不存在或定义无效: " + agent);
    }
    RealPathBoundary.requireWithin(agentsDir, dir);
    return dir;
  }

  private Path requireKnowledgeBase(String kb) {
    Path dir = knowledgeDir.resolve(kb);
    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("知识库不存在: " + kb);
    }
    RealPathBoundary.requireWithin(knowledgeDir, dir);
    KnowledgeManifest manifest = KnowledgeManifest.read(dir);
    if (!kb.equals(manifest.name())) {
      throw new IllegalArgumentException("知识库 name 与目录不一致: " + kb);
    }
    return dir.toAbsolutePath().normalize();
  }

  private static void requireControlledLink(Path link, String kb) {
    if (!Files.isSymbolicLink(link)) {
      throw new IllegalArgumentException("绑定位置不是软连接，拒绝删除: " + link);
    }
    try {
      if (!Files.readSymbolicLink(link).equals(expectedTarget(kb))) {
        throw new IllegalArgumentException("绑定不是受控固定链接，拒绝删除: " + link);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("读取绑定失败: " + link, e);
    }
  }

  private static List<String> normalizedNames(List<String> names) {
    if (names == null) {
      return List.of();
    }
    return names.stream().map(name -> safe(name, "知识库")).distinct().sorted().toList();
  }

  private static Path expectedTarget(String kb) {
    return Path.of("..", "..", "..", LINKS_DIR, kb);
  }

  private static KnowledgeBindingIssue issue(
      String agent,
      KnowledgeBindingIssue.AgentState state,
      String entry,
      Path path,
      KnowledgeBindingIssue.Type type,
      String message) {
    return new KnowledgeBindingIssue(
        agent, state, entry, path.toAbsolutePath().normalize(), type, sanitize(message));
  }

  private static String safe(String name, String kind) {
    if (name == null || !SAFE_NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("非法 " + kind + " 名（只允许字母/数字/下划线/连字符）: " + name);
    }
    return name;
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
