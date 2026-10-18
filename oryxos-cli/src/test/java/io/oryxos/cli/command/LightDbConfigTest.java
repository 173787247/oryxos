package io.oryxos.cli.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 轻命令 datasource 解析的失败口径：外部配置不存在 → 回落内置 SQLite（既有行为）；存在但读不出/解析失败/结构非法 → 抛 {@link
 * LightDbConfigException}，绝不静默换库；合法配置（SQLite / PG）正常解析。
 */
class LightDbConfigTest {

  @TempDir Path tmp;

  @Test
  @DisplayName("配置文件不存在_回落内置 SQLite 默认库（既有行为不变）")
  void missingConfigFileFallsBackToDefaultSqlite() {
    LightDbConfig config = LightDbConfig.load(tmp.resolve("config/application.yml"));

    assertTrue(config.isSqlite(), "缺省档是 SQLite");
    assertEquals("oryxos.db", config.describe(), "默认库仍是 CWD 下的 oryxos.db");
  }

  @Test
  @DisplayName("空配置文件_没有 datasource 覆盖_回落默认（不算配置错误）")
  void emptyConfigFileFallsBackToDefaultSqlite() throws Exception {
    Path configFile = writeConfig("");

    LightDbConfig config = LightDbConfig.load(configFile);

    assertEquals("oryxos.db", config.describe());
  }

  @Test
  @DisplayName("配置存在但没有 datasource 段_回落默认（重命令此时也用打包默认，同一个库）")
  void configWithoutDatasourceSectionFallsBackToDefault() throws Exception {
    Path configFile = writeConfig("spring:\n  application:\n    name: oryxos\n");

    LightDbConfig config = LightDbConfig.load(configFile);

    assertEquals("oryxos.db", config.describe());
  }

  @Test
  @DisplayName("坏 YAML_抛明确错误并给出文件与行列_不回落默认库")
  void brokenYamlFailsLoudWithFileAndPosition() throws Exception {
    Path configFile =
        writeConfig(
            """
            spring:
              datasource:
                url: jdbc:sqlite:x.db
                  username: broken-indent
            """);

    LightDbConfigException error =
        assertThrows(LightDbConfigException.class, () -> LightDbConfig.load(configFile));

    String message = error.getMessage();
    assertTrue(message.contains(configFile.toString()), "要点名是哪个文件: " + message);
    assertTrue(message.matches("(?s).*第 \\d+ 行第 \\d+ 列.*"), "要给出 YAML 的行/列定位: " + message);
    assertTrue(message.contains("拒绝回落到内置 SQLite"), "要写明不会回落默认库: " + message);
  }

  @Test
  @DisplayName("spring.datasource 不是映射_抛明确错误")
  void malformedDatasourceShapeFailsLoud() throws Exception {
    Path configFile = writeConfig("spring:\n  datasource: jdbc:sqlite:oops.db\n");

    LightDbConfigException error =
        assertThrows(LightDbConfigException.class, () -> LightDbConfig.load(configFile));

    assertTrue(error.getMessage().contains("spring.datasource 不是映射"), error.getMessage());
    assertTrue(error.getMessage().contains(configFile.toString()), error.getMessage());
  }

  @Test
  @DisplayName("显式空 url_抛明确错误（空串会覆盖打包默认，重命令同样起不来）")
  void blankDatasourceUrlFailsLoud() throws Exception {
    Path configFile = writeConfig("spring:\n  datasource:\n    url: \"\"\n");

    LightDbConfigException error =
        assertThrows(LightDbConfigException.class, () -> LightDbConfig.load(configFile));

    assertTrue(error.getMessage().contains("spring.datasource.url 不是非空字符串"), error.getMessage());
  }

  @Test
  @DisplayName("合法 SQLite 配置_解析占位符并补 busy_timeout（真连临时库验证）")
  void legalSqliteConfigParsesAndConnectsWithBusyTimeout() throws Exception {
    Path dbFile = tmp.resolve("legal.db");
    Path configFile =
        writeConfig(
            "spring:\n"
                + "  datasource:\n"
                + "    url: jdbc:sqlite:${ORYXOS_TEST_DB_FILE_XYZ:"
                + dbFile
                + "}\n");

    LightDbConfig config = LightDbConfig.load(configFile);

    assertEquals(dbFile.toString(), config.describe(), "占位符里的默认值应被解析成 url");
    assertTrue(config.sqliteFileMissing(), "还没连，文件应不存在");
    try (Connection conn = config.connect()) {
      assertEquals(5000, pragmaBusyTimeout(conn), "SQLite 档必须补 busy_timeout=5000");
    }
    assertFalse(config.sqliteFileMissing(), "连接后临时库文件应已创建");
  }

  @Test
  @DisplayName("合法 PG 配置_解析占位符_不补 SQLite 参数")
  void legalPostgresConfigParsesAndResolvesPlaceholders() throws Exception {
    Path configFile =
        writeConfig(
            "spring:\n"
                + "  datasource:\n"
                + "    url: jdbc:postgresql://${ORYXOS_TEST_PG_HOST_XYZ:db.internal}:5432/oryxos\n"
                + "    username: ${ORYXOS_TEST_PG_USER_XYZ:oryx}\n"
                + "    password: ${ORYXOS_TEST_PG_PASS_XYZ:secret}\n");

    LightDbConfig config = LightDbConfig.load(configFile);

    assertFalse(config.isSqlite());
    assertEquals("jdbc:postgresql://db.internal:5432/oryxos", config.describe());
    assertFalse(config.describe().contains("busy_timeout"), "PG 档不得追加 SQLite 连接参数");
  }

  private Path writeConfig(String content) throws Exception {
    Path configFile = tmp.resolve("config").resolve("application.yml");
    Files.createDirectories(configFile.getParent());
    Files.writeString(configFile, content);
    return configFile;
  }

  private static int pragmaBusyTimeout(Connection conn) throws Exception {
    try (Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("PRAGMA busy_timeout")) {
      assertTrue(rs.next(), "PRAGMA busy_timeout 应返回一行");
      return rs.getInt(1);
    }
  }
}
