package io.oryxos.cli.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.knowledge.KnowledgeManifest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code oryxos knowledge list} 的退出码：索引状态查询失败必须非零；而「知识库目录不存在」「跳过非法知识库目录」 是信息与 WARN，不是错误，保持
 * 0——一个坏目录不该让整条 list 判失败。
 */
class KnowledgeCommandTest {

  @TempDir Path tmp;

  @Test
  @DisplayName("知识库目录不存在：退出 0")
  void missingKnowledgeDirExitsZero() {
    Result result = run(tmp.resolve("knowledge"), tmp.resolve("absent.db"));

    assertEquals(0, result.code(), result.err());
    assertTrue(result.out().contains("暂无知识库（"), result.out());
  }

  @Test
  @DisplayName("非法知识库目录只 WARN：仍列出合法库、退出 0")
  void invalidManifestIsWarnNotError() throws Exception {
    Path knowledge = tmp.resolve("knowledge");
    writeManifest(knowledge.resolve("ops"), "ops", "运营知识库");
    Files.createDirectories(knowledge.resolve("broken")); // 缺 KNOWLEDGE.md

    // 库文件不存在 → 统计全 0、不查库，把判据集中到「坏目录不致命」这一点上
    Result result = run(knowledge, tmp.resolve("absent.db"));

    assertEquals(0, result.code(), "WARN 不得把 list 判失败: " + result.err());
    assertTrue(result.err().contains("跳过非法知识库目录 broken"), result.err());
    assertTrue(result.out().contains("ops"), result.out());
  }

  @Test
  @DisplayName("索引状态查询失败：非零退出且 stderr 有信息")
  void indexQueryFailureExitsNonZero() throws Exception {
    Path knowledge = tmp.resolve("knowledge");
    writeManifest(knowledge.resolve("ops"), "ops", "运营知识库");
    Path broken = tmp.resolve("broken.db");
    Files.writeString(broken, "this is not a sqlite database");

    Result result = run(knowledge, broken);

    assertEquals(1, result.code(), "查询失败必须非零退出");
    assertTrue(result.err().contains("查询索引状态失败"), result.err());
  }

  private Result run(Path knowledgeDir, Path dbFile) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
    PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8);
    int code =
        new KnowledgeCommand.ListCommand(
                knowledgeDir, LightDbConfig.forUrl("jdbc:sqlite:" + dbFile), outStream, errStream)
            .call();
    outStream.flush();
    errStream.flush();
    return new Result(
        code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }

  private static void writeManifest(Path kbDir, String name, String description)
      throws IOException {
    Files.createDirectories(kbDir);
    Files.writeString(
        kbDir.resolve(KnowledgeManifest.FILE),
        "---\nname: " + name + "\ndescription: " + description + "\n---\n");
  }

  private record Result(int code, String out, String err) {}
}
