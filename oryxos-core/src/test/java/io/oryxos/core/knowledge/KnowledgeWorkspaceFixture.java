package io.oryxos.core.knowledge;

import io.oryxos.core.testing.SymlinkAssumptions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** 跨故事共享的知识库工作区测试夹具（T002）：临时目录内搭 agents/ + knowledge/ 与各种绑定形态。 */
public final class KnowledgeWorkspaceFixture {

  private KnowledgeWorkspaceFixture() {}

  /** 建出 .oryxos 形态的根：agents/ + knowledge/。 */
  public static Path workspace(Path root) {
    try {
      Files.createDirectories(root.resolve("agents"));
      Files.createDirectories(root.resolve("knowledge"));
      return root;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** 建一个合法知识库目录：KNOWLEDGE.md + 文档文件。 */
  public static Path knowledgeBase(
      Path root, String name, String description, Map<String, String> documents) {
    try {
      Path dir = Files.createDirectories(root.resolve("knowledge").resolve(name));
      Files.writeString(
          dir.resolve(KnowledgeManifest.FILE),
          "---\nname: " + name + "\ndescription: " + description + "\n---\n");
      for (Map.Entry<String, String> doc : documents.entrySet()) {
        Path file = dir.resolve(doc.getKey());
        Files.createDirectories(file.getParent());
        Files.writeString(file, doc.getValue());
      }
      return dir;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** 建一个最小合法 Agent 目录（含 AGENT.md）。 */
  public static Path agent(Path root, String name) {
    try {
      Path dir = Files.createDirectories(root.resolve("agents").resolve(name));
      Files.writeString(dir.resolve("AGENT.md"), "---\nname: " + name + "\n---\n任务指令");
      return dir;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** 手工建一条受控相对绑定链接（不经服务，供 GitOps 视角测试）。 */
  public static Path binding(Path root, String agentName, String kbName) {
    try {
      Path linksDir =
          Files.createDirectories(root.resolve("agents").resolve(agentName).resolve("knowledge"));
      Path link = linksDir.resolve(kbName);
      SymlinkAssumptions.createSymbolicLinkOrAssume(
          link, Path.of("..", "..", "..", "knowledge", kbName));
      return link;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** 建一条指定原始目标的绑定链接（用于构造绝对/越界/错名等非法形态）。 */
  public static Path rawBinding(Path root, String agentName, String entryName, Path target) {
    try {
      Path linksDir =
          Files.createDirectories(root.resolve("agents").resolve(agentName).resolve("knowledge"));
      Path link = linksDir.resolve(entryName);
      SymlinkAssumptions.createSymbolicLinkOrAssume(link, target);
      return link;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
