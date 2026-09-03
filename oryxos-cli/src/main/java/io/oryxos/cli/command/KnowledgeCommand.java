package io.oryxos.cli.command;

import io.oryxos.core.knowledge.KnowledgeManifest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Command;

/**
 * 轻命令（FR-021）：知识库概览——目录读清单（文件系统是库实体的事实源），纯 JDBC 只读聚合 knowledge_documents（索引状态是派生数据），零 Spring，与
 * provider/tool/session list 同族。
 */
@Command(
    name = "knowledge",
    description = "知识库相关操作",
    mixinStandardHelpOptions = true,
    subcommands = KnowledgeCommand.ListCommand.class)
public class KnowledgeCommand implements Runnable {

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }

  @Command(name = "list", description = "列出知识库", mixinStandardHelpOptions = true)
  static class ListCommand implements Runnable {

    private static final Path KNOWLEDGE_DIR = Path.of(".oryxos", "knowledge");

    /** indexStats 结果各下标：0=文档数 1=片段数 2=失败数 3=进行中数。 */
    private static final int STAT_COUNT = 4;

    private static final int STAT_FAILED = 2;
    private static final int STAT_IN_PROGRESS = 3;

    @Override
    public void run() {
      if (!Files.isDirectory(KNOWLEDGE_DIR)) {
        System.out.println("暂无知识库（" + KNOWLEDGE_DIR + " 尚未创建，可先 oryxos init）。");
        return;
      }
      List<KnowledgeManifest> manifests = readManifests();
      if (manifests.isEmpty()) {
        System.out.println("暂无知识库。");
        return;
      }
      System.out.printf("%-20s %-8s %6s %6s %-6s %s%n", "名称", "后端", "文档数", "片段数", "状态", "描述");
      for (KnowledgeManifest manifest : manifests) {
        long[] stats = indexStats(manifest.name());
        System.out.printf(
            "%-20s %-8s %6d %6d %-6s %s%n",
            manifest.name(),
            manifest.backend(),
            stats[0],
            stats[1],
            status(stats),
            manifest.description());
      }
    }

    private static List<KnowledgeManifest> readManifests() {
      List<KnowledgeManifest> manifests = new ArrayList<>();
      try (DirectoryStream<Path> dirs =
          Files.newDirectoryStream(KNOWLEDGE_DIR, Files::isDirectory)) {
        for (Path dir : dirs) {
          try {
            manifests.add(KnowledgeManifest.read(dir));
          } catch (RuntimeException e) {
            System.err.println("跳过非法知识库目录 " + dir.getFileName() + ": " + e.getMessage());
          }
        }
      } catch (IOException e) {
        throw new UncheckedIOException("扫描知识库目录失败", e);
      }
      manifests.sort(java.util.Comparator.comparing(KnowledgeManifest::name));
      return manifests;
    }

    /** [文档数, 片段数, 失败数, 进行中数]；SQLite 数据文件尚未生成时全 0（远程后端库也落这里）。 */
    private static long[] indexStats(String kbName) {
      long[] stats = new long[STAT_COUNT];
      // 与重命令读同一份 config/application.yml——两边看到的必须是同一个库（025：SQLite 或 PG）
      LightDbConfig db = LightDbConfig.load();
      if (db.sqliteFileMissing()) {
        return stats;
      }
      String sql =
          "SELECT count(*), coalesce(sum(chunk_count),0),"
              + " sum(CASE WHEN status='FAILED' THEN 1 ELSE 0 END),"
              + " sum(CASE WHEN status IN ('PENDING','INDEXING') THEN 1 ELSE 0 END)"
              + " FROM knowledge_documents WHERE kb_name = ?";
      try (Connection conn = db.connect();
          PreparedStatement stmt = conn.prepareStatement(sql)) {
        stmt.setString(1, kbName);
        try (ResultSet rs = stmt.executeQuery()) {
          if (rs.next()) {
            for (int i = 0; i < STAT_COUNT; i++) {
              stats[i] = rs.getLong(i + 1);
            }
          }
        }
      } catch (SQLException e) {
        System.err.println("查询索引状态失败: " + e.getMessage());
      }
      return stats;
    }

    private static String status(long[] stats) {
      if (stats[0] == 0) {
        return "空";
      }
      if (stats[STAT_FAILED] > 0) {
        return "失败";
      }
      return stats[STAT_IN_PROGRESS] > 0 ? "索引中" : "就绪";
    }
  }
}
