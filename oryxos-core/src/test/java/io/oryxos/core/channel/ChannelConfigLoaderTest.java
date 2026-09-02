package io.oryxos.core.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.testing.SymlinkAssumptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 017 R6：channels.yaml 双读法、占位符保留落盘、点名校验。 */
class ChannelConfigLoaderTest {

  @TempDir Path tempDir;

  private Path configFile() {
    return tempDir.resolve("channels.yaml");
  }

  private void write(String yaml) throws Exception {
    Files.writeString(configFile(), yaml);
  }

  @Test
  @DisplayName("文件缺失 = 零渠道，启动照常")
  void missingFileMeansEmpty() {
    assertEquals(List.of(), new ChannelConfigLoader(configFile()).loadRaw());
  }

  @Test
  @DisplayName("loadRaw 保留 ${ENV} 字面量；save 回写后字面量原样落盘且权限收紧 rw-------")
  void rawKeepsPlaceholdersAndSaveRestrictsPermission() throws Exception {
    SymlinkAssumptions.assumePosixSupported();
    write(
        """
        channels:
          - name: ops-feishu
            type: feishu
            app_id: ${FEISHU_APP_ID}
            app_secret: ${FEISHU_APP_SECRET}
            agent: ops-agent
        """);
    ChannelConfigLoader loader = new ChannelConfigLoader(configFile());
    List<ChannelConfig> raw = loader.loadRaw();
    assertEquals(1, raw.size());
    assertEquals("${FEISHU_APP_SECRET}", raw.get(0).appSecret());
    assertTrue(raw.get(0).enabled());

    loader.save(raw);
    String written = Files.readString(configFile());
    assertTrue(written.contains("${FEISHU_APP_SECRET}"));
    Set<PosixFilePermission> perms = Files.getPosixFilePermissions(configFile());
    assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms);
  }

  @Test
  @DisplayName("resolve：环境变量存在则解析；不存在保留原样并被凭证校验点名拒绝")
  void resolveAndCredentialValidation() throws Exception {
    write(
        """
        channels:
          - name: ops-feishu
            type: feishu
            app_id: ${PATH}
            app_secret: ${ORYXOS_TEST_SURELY_MISSING_ENV}
            agent: ops-agent
        """);
    ChannelConfigLoader loader = new ChannelConfigLoader(configFile());
    ChannelConfig resolved = loader.load().get(0);
    assertEquals(System.getenv("PATH"), resolved.appId()); // 存在的环境变量被解析
    assertTrue(resolved.appSecret().contains("${")); // 缺失的保留字面量

    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, resolved::validateCredentialsResolved);
    assertTrue(e.getMessage().contains("ops-feishu"));
    assertTrue(e.getMessage().contains("app_secret"));
  }

  @Test
  @DisplayName("结构非法点名报错：渠道名重复 / 缺字段 / name 字符集非法")
  void shapeValidation() throws Exception {
    write(
        """
        channels:
          - name: dup
            type: feishu
            app_id: a
            app_secret: b
            agent: x
          - name: dup
            type: feishu
            app_id: a
            app_secret: b
            agent: x
        """);
    IllegalArgumentException dup =
        assertThrows(
            IllegalArgumentException.class, () -> new ChannelConfigLoader(configFile()).loadRaw());
    assertTrue(dup.getMessage().contains("dup"));

    write(
        """
        channels:
          - name: no-agent
            type: feishu
            app_id: a
            app_secret: b
        """);
    IllegalArgumentException missing =
        assertThrows(
            IllegalArgumentException.class, () -> new ChannelConfigLoader(configFile()).loadRaw());
    assertTrue(missing.getMessage().contains("no-agent"));

    write(
        """
        channels:
          - name: "bad name!"
            type: feishu
            app_id: a
            app_secret: b
            agent: x
        """);
    assertThrows(
        IllegalArgumentException.class, () -> new ChannelConfigLoader(configFile()).loadRaw());
  }

  @Test
  @DisplayName("enabled: false 被读出（停用渠道保留配置）")
  void disabledEntry() throws Exception {
    write(
        """
        channels:
          - name: off-chan
            type: feishu
            app_id: a
            app_secret: b
            agent: x
            enabled: false
        """);
    assertEquals(false, new ChannelConfigLoader(configFile()).loadRaw().get(0).enabled());
  }

  @Test
  @DisplayName("enabled 接受 yes/on/1 等常见真值，非法值 fail-loud")
  void enabledCoercion() throws Exception {
    write(
        """
        channels:
          - name: quoted-yes
            type: feishu
            app_id: a
            app_secret: b
            agent: x
            enabled: "yes"
          - name: quoted-on
            type: feishu
            app_id: a
            app_secret: b
            agent: x
            enabled: "on"
          - name: numeric-one
            type: feishu
            app_id: a
            app_secret: b
            agent: x
            enabled: 1
        """);
    List<ChannelConfig> configs = new ChannelConfigLoader(configFile()).loadRaw();
    assertEquals(3, configs.size());
    assertTrue(configs.get(0).enabled());
    assertTrue(configs.get(1).enabled());
    assertTrue(configs.get(2).enabled());

    write(
        """
        channels:
          - name: bad-enabled
            type: feishu
            app_id: a
            app_secret: b
            agent: x
            enabled: maybe
        """);
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class, () -> new ChannelConfigLoader(configFile()).loadRaw());
    assertTrue(e.getMessage().contains("bad-enabled"));
    assertTrue(e.getMessage().contains("enabled"));
  }

  @Test
  @DisplayName("YAML 1.1 布尔词 yes/on 不得被 String.valueOf 改成 true（须加引号）")
  void rejectsYamlBooleanWordsAsStringFields() throws Exception {
    write(
        """
        channels:
          - name: yes
            type: feishu
            app_id: a
            app_secret: b
            agent: ops
        """);
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class, () -> new ChannelConfigLoader(configFile()).loadRaw());
    assertTrue(e.getMessage().contains("字符串") || e.getMessage().contains("Boolean"));

    write(
        """
        channels:
          - name: ops
            type: feishu
            app_id: a
            app_secret: b
            agent: on
        """);
    assertThrows(
        IllegalArgumentException.class, () -> new ChannelConfigLoader(configFile()).loadRaw());

    write(
        """
        channels:
          - name: "yes"
            type: feishu
            app_id: a
            app_secret: b
            agent: "on"
        """);
    List<ChannelConfig> ok = new ChannelConfigLoader(configFile()).loadRaw();
    assertEquals(1, ok.size());
    assertEquals("yes", ok.get(0).name());
    assertEquals("on", ok.get(0).agent());
  }
}
