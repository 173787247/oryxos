package io.oryxos.web.controller;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.fs.RealPathBoundary;
import io.oryxos.core.testing.SymlinkAssumptions;
import io.oryxos.web.GlobalExceptionHandler;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 课件《第30节》验收 harness：WorkspaceApiControllerTest——目录树 + 读文件 + 防目录穿越。 */
class WorkspaceApiControllerTest {

  @TempDir Path oryxosRoot;
  private MockMvc mvc;
  private WorkspaceApiController controller;
  private io.oryxos.core.agent.AgentLifecycleService lifecycle;

  @BeforeEach
  void setUp() throws IOException {
    Path agent = Files.createDirectories(oryxosRoot.resolve("agents").resolve("demo"));
    Files.writeString(agent.resolve("AGENT.md"), "---\nname: demo\n---\n正文内容");
    Files.createDirectories(oryxosRoot.resolve("archive"));
    lifecycle = org.mockito.Mockito.mock(io.oryxos.core.agent.AgentLifecycleService.class);
    bumpedDomains.clear();
    localReloads.set(0);
    controller =
        new WorkspaceApiController(oryxosRoot.toString(), lifecycle, clusterRefreshService());
    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  // 027 refresh 端点：集群档 bump 全域 / 单机档本地重载（可观测的记录桩）
  private final java.util.List<String> bumpedDomains =
      java.util.Collections.synchronizedList(new java.util.ArrayList<>());
  private final java.util.concurrent.atomic.AtomicInteger localReloads =
      new java.util.concurrent.atomic.AtomicInteger();

  private io.oryxos.core.cluster.WorkspaceRefreshService clusterRefreshService() {
    return new io.oryxos.core.cluster.WorkspaceRefreshService(
        true, bumpedDomains::add, localReloads::incrementAndGet);
  }

  private io.oryxos.core.cluster.WorkspaceRefreshService standaloneRefreshService() {
    return new io.oryxos.core.cluster.WorkspaceRefreshService(
        false, bumpedDomains::add, localReloads::incrementAndGet);
  }

  private MockMvc mvcWith(io.oryxos.core.cluster.WorkspaceRefreshService refresh) {
    return MockMvcBuilders.standaloneSetup(
            new WorkspaceApiController(oryxosRoot.toString(), lifecycle, refresh))
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
  }

  @Test
  void inFlightAtomicTemporaryCannotBeReadOrDownloadedEvenThroughAlias() throws Exception {
    Path output = Files.createDirectories(oryxosRoot.resolve("output"));
    Path published = output.resolve("report.txt");
    Files.writeString(published, "previous");
    io.oryxos.core.io.AtomicFiles.write(
        published,
        stream -> {
          stream.write("partial".getBytes(java.nio.charset.StandardCharsets.UTF_8));
          stream.flush();
          try (var paths = Files.list(output)) {
            Path temporary =
                paths
                    .filter(p -> p.getFileName().toString().startsWith(".report.txt.write-"))
                    .findFirst()
                    .orElseThrow();
            Path alias = output.resolve("alias.txt");
            Files.createSymbolicLink(alias, temporary.getFileName());
            for (Path target : java.util.List.of(temporary, alias)) {
              String relative = oryxosRoot.relativize(target).toString();
              mvc.perform(get("/api/v1/workspace/file").param("path", relative))
                  .andExpect(status().isBadRequest());
              mvc.perform(get("/api/v1/workspace/download").param("path", relative))
                  .andExpect(status().isBadRequest());
            }
            mvc.perform(get("/api/v1/workspace/file").param("path", "output/report.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("previous"));
            Files.delete(alias);
          } catch (Exception failure) {
            throw new IOException(failure);
          }
        });
    mvc.perform(get("/api/v1/workspace/file").param("path", "output/report.txt"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").value("partial"));
  }

  @Test
  @DisplayName("027 refresh：集群档递增全部 4 域版本号，不做本地重载")
  void refresh_clusterBumpsAllDomains() throws Exception {
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                "/api/v1/workspace/refresh"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.mode").value("cluster"))
        .andExpect(jsonPath("$.data.domains.length()").value(4));
    org.junit.jupiter.api.Assertions.assertEquals(
        java.util.List.of("agents", "skills", "personas", "knowledge"), bumpedDomains);
    org.junit.jupiter.api.Assertions.assertEquals(0, localReloads.get());
  }

  @Test
  @DisplayName("027 refresh：单机档本地全量重载，零版本号写入")
  void refresh_standaloneReloadsLocally() throws Exception {
    mvcWith(standaloneRefreshService())
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                "/api/v1/workspace/refresh"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.mode").value("standalone"));
    org.junit.jupiter.api.Assertions.assertEquals(1, localReloads.get());
    org.junit.jupiter.api.Assertions.assertTrue(bumpedDomains.isEmpty());
  }

  @Test
  @DisplayName("tree 返回存在的四个默认可读根、可钻进 Agent 目录列文件")
  void tree_returnsAgentsAndArchive() throws Exception {
    // 树只列存在的目录：这里把四个默认可读根都建出来，验证默认配置下的结构
    Files.createDirectories(oryxosRoot.resolve("skills"));
    Files.createDirectories(oryxosRoot.resolve("output"));
    mvc.perform(get("/api/v1/workspace/tree"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.children[*].name")
                .value(org.hamcrest.Matchers.hasItems("agents", "skills", "output", "archive")))
        .andExpect(jsonPath("$.data.children[0].children[0].name").value("demo"));
  }

  @Test
  @DisplayName("tree 跟着 readable-roots 走：改成 agents,custom 后列 custom、不列 output/archive")
  void tree_followsConfiguredReadableRoots() throws Exception {
    Files.createDirectories(oryxosRoot.resolve("custom"));
    Files.createDirectories(oryxosRoot.resolve("output"));
    setReadableRoots("agents,custom");

    mvc.perform(get("/api/v1/workspace/tree"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.children.length()").value(2))
        .andExpect(
            jsonPath("$.data.children[*].name")
                .value(org.hamcrest.Matchers.hasItems("agents", "custom")))
        .andExpect(
            jsonPath(
                "$.data.children[*].name",
                org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("output"))))
        .andExpect(
            jsonPath(
                "$.data.children[*].name",
                org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("archive"))));
  }

  @Test
  @DisplayName("tree 只列存在的目录：缺失的根不出现（避免点进去 404）")
  void tree_omitsMissingRoots() throws Exception {
    // setUp 建了 archive/；默认配置下 skills/、output/ 不存在 —— 树里只应有 agents 与 archive
    mvc.perform(get("/api/v1/workspace/tree"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.children.length()").value(2))
        .andExpect(
            jsonPath("$.data.children[*].name")
                .value(org.hamcrest.Matchers.contains("agents", "archive")));

    // 删掉 archive/ 后它也不再出现
    Files.delete(oryxosRoot.resolve("archive"));
    mvc.perform(get("/api/v1/workspace/tree"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.children.length()").value(1))
        .andExpect(jsonPath("$.data.children[0].name").value("agents"));
  }

  /** 模拟 {@code oryxos.web.workspace.readable-roots} 注入（@Value 字段只在 Spring 装配时赋值）。 */
  private void setReadableRoots(String setting) throws Exception {
    java.lang.reflect.Field field =
        WorkspaceApiController.class.getDeclaredField("readableRootsSetting");
    field.setAccessible(true);
    field.set(controller, setting);
  }

  @Test
  @DisplayName("file?path=../../etc/passwd 目录穿越 → 400")
  void file_pathTraversal_returns400() throws Exception {
    mvc.perform(get("/api/v1/workspace/file").param("path", "../../etc/passwd"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  @DisplayName("正常文件返回内容")
  void file_validPath_returnsContent() throws Exception {
    mvc.perform(get("/api/v1/workspace/file").param("path", "agents/demo/AGENT.md"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").value("---\nname: demo\n---\n正文内容"));
  }

  @Test
  @DisplayName("download 正常文件：附件头 + 原始字节")
  void download_validPath_returnsAttachment() throws Exception {
    Path report = oryxosRoot.resolve("agents").resolve("demo").resolve("output");
    Files.createDirectories(report);
    Files.writeString(report.resolve("report.md"), "# 研报\n今日无异常");
    mvc.perform(get("/api/v1/workspace/download").param("path", "agents/demo/output/report.md"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Disposition", containsString("attachment")))
        .andExpect(header().string("Content-Disposition", containsString("report.md")))
        .andExpect(
            content().bytes("# 研报\n今日无异常".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  @Test
  @DisplayName("download 目录穿越 → 400")
  void download_pathTraversal_returns400() throws Exception {
    mvc.perform(get("/api/v1/workspace/download").param("path", "../../etc/passwd"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  @DisplayName("download 文件不存在 → 404")
  void download_missingFile_returns404() throws Exception {
    mvc.perform(get("/api/v1/workspace/download").param("path", "agents/demo/output/nope.md"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  @DisplayName("read/download/write 均拒绝经父软连接逃逸，tree 把链接作为叶节点")
  void symlinkEscapeIsRejectedAndTreeDoesNotFollow() throws Exception {
    SymlinkAssumptions.assumeSymlinksSupported(oryxosRoot);
    Path outside = Files.createDirectories(oryxosRoot.resolveSibling("outside-workspace"));
    Files.writeString(outside.resolve("secret.txt"), "SECRET");
    Files.createSymbolicLink(oryxosRoot.resolve("agents/demo/escape"), outside);

    mvc.perform(get("/api/v1/workspace/file").param("path", "agents/demo/escape/secret.txt"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/workspace/download").param("path", "agents/demo/escape/secret.txt"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"agents/demo/escape/new.txt\",\"content\":\"bad\"}"))
        .andExpect(status().isBadRequest());
    org.junit.jupiter.api.Assertions.assertFalse(Files.exists(outside.resolve("new.txt")));

    mvc.perform(get("/api/v1/workspace/tree"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$..[?(@.name == 'escape')].type").value("link"))
        .andExpect(jsonPath("$..[?(@.name == 'escape')].children.length()").value(0));
  }

  @Test
  @DisplayName("write 在 createDirectories 后复检路径，阻断父路径被换成外向软链")
  void writeRechecksPathAfterCreateDirectories() throws Exception {
    Assumptions.assumeTrue(
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "需要 POSIX 软链支持");
    Path outside = Files.createDirectories(oryxosRoot.resolveSibling("outside-recheck"));
    String rel = "agents/demo/hook/nested/file.txt";
    Path hook = oryxosRoot.resolve("agents/demo/hook");
    org.junit.jupiter.api.Assertions.assertFalse(Files.exists(hook));

    // 模拟 TOCTOU：首次 resolve 时 hook 尚不存在，建目录前被换成外向软链
    RealPathBoundary.requireWithin(oryxosRoot, oryxosRoot.resolve(rel).normalize());
    Files.createSymbolicLink(hook, outside);
    Files.createDirectories(oryxosRoot.resolve(rel).getParent());

    assertThrows(
        IllegalArgumentException.class,
        () -> RealPathBoundary.requireWithin(oryxosRoot, oryxosRoot.resolve(rel).normalize()));
    org.junit.jupiter.api.Assertions.assertFalse(Files.exists(outside.resolve("nested/file.txt")));

    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"" + rel + "\",\"content\":\"bad\"}"))
        .andExpect(status().isBadRequest());
    org.junit.jupiter.api.Assertions.assertFalse(Files.exists(outside.resolve("nested/file.txt")));
  }

  @Test
  @DisplayName("file 读前复检路径，阻断目标被换成外向软链")
  void fileRechecksPathBeforeRead() throws Exception {
    Assumptions.assumeTrue(
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "需要 POSIX 软链支持");
    Path outside = Files.createDirectories(oryxosRoot.resolveSibling("outside-read-recheck"));
    Files.writeString(outside.resolve("secret.txt"), "leaked");
    String rel = "agents/demo/notes.md";
    Path notes = oryxosRoot.resolve(rel);
    Files.writeString(notes, "inside");

    RealPathBoundary.requireWithin(oryxosRoot, notes.normalize());
    Files.delete(notes);
    Files.createSymbolicLink(notes, outside.resolve("secret.txt"));
    assertThrows(
        IllegalArgumentException.class,
        () -> RealPathBoundary.requireWithin(oryxosRoot, notes.normalize()));

    mvc.perform(get("/api/v1/workspace/file").param("path", rel))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("download 读前复检路径，阻断目标被换成外向软链")
  void downloadRechecksPathBeforeRead() throws Exception {
    Assumptions.assumeTrue(
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "需要 POSIX 软链支持");
    Path outside = Files.createDirectories(oryxosRoot.resolveSibling("outside-download-recheck"));
    Files.writeString(outside.resolve("secret.bin"), "leaked");
    String rel = "agents/demo/output/report.md";
    Path report = oryxosRoot.resolve(rel);
    Files.createDirectories(report.getParent());
    Files.writeString(report, "inside");

    RealPathBoundary.requireWithin(oryxosRoot, report.normalize());
    Files.delete(report);
    Files.createSymbolicLink(report, outside.resolve("secret.bin"));
    assertThrows(
        IllegalArgumentException.class,
        () -> RealPathBoundary.requireWithin(oryxosRoot, report.normalize()));

    mvc.perform(get("/api/v1/workspace/download").param("path", rel))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("工作区写入口禁止直写 MEMORY.md（须走 save_memory）")
  void writeMemoryMdIsRejected() throws Exception {
    Path memory = Files.createDirectories(oryxosRoot.resolve("agents/demo"));
    Files.writeString(memory.resolve("MEMORY.md"), "## 核心记忆\n- keep\n## 归档记忆\n");

    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"path\":\"agents/demo/MEMORY.md\",\"content\":\"## 核心记忆\\n## 归档记忆\\n- hijack\"}"))
        .andExpect(status().isBadRequest());
    org.junit.jupiter.api.Assertions.assertEquals(
        "## 核心记忆\n- keep\n## 归档记忆\n",
        Files.readString(oryxosRoot.resolve("agents/demo/MEMORY.md")));
  }

  @Test
  @DisplayName("工作区写入口禁止经软链改写 MEMORY.md（notes.md → MEMORY.md）")
  void writeViaSymlinkToMemoryMdIsRejected() throws Exception {
    Path agentDir = Files.createDirectories(oryxosRoot.resolve("agents/demo"));
    Path memory = agentDir.resolve("MEMORY.md");
    Files.writeString(memory, "## 核心记忆\n- keep\n## 归档记忆\n");
    Path alias = agentDir.resolve("notes.md");
    try {
      Files.createSymbolicLink(alias, memory.getFileName());
    } catch (IOException | UnsupportedOperationException e) {
      org.junit.jupiter.api.Assumptions.assumeTrue(false, "当前环境无法创建软链: " + e.getMessage());
    }

    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"path\":\"agents/demo/notes.md\",\"content\":\"## 核心记忆\\n## 归档记忆\\n- hijack\"}"))
        .andExpect(status().isBadRequest());
    org.junit.jupiter.api.Assertions.assertEquals(
        "## 核心记忆\n- keep\n## 归档记忆\n", Files.readString(memory));
  }

  @Test
  @DisplayName("工作区写入口禁止直写 channels.yaml / mcp_servers.yaml")
  void writeAdminConfigFilesIsRejected() throws Exception {
    Path channels = Files.writeString(oryxosRoot.resolve("channels.yaml"), "channels: []\n");
    Path mcp = Files.writeString(oryxosRoot.resolve("mcp_servers.yaml"), "servers: []\n");
    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"path\":\"channels.yaml\",\"content\":\"channels: [{hijack: true}]\\n\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"path\":\"mcp_servers.yaml\",\"content\":\"servers: [{hijack: true}]\\n\"}"))
        .andExpect(status().isBadRequest());
    org.junit.jupiter.api.Assertions.assertEquals("channels: []\n", Files.readString(channels));
    org.junit.jupiter.api.Assertions.assertEquals("servers: []\n", Files.readString(mcp));
  }

  @Test
  @DisplayName("file/download 禁止读取 channels.yaml / mcp_servers.yaml / oryxos.db 原文")
  void readReservedFilesIsRejected() throws Exception {
    Files.writeString(oryxosRoot.resolve("channels.yaml"), "channels: [{token: SECRET-CHANNEL}]\n");
    Files.writeString(
        oryxosRoot.resolve("mcp_servers.yaml"), "servers: [{env: {KEY: SECRET-MCP}}]\n");
    Files.writeString(oryxosRoot.resolve("oryxos.db"), "fake sqlite bytes SECRET-DB");
    Files.writeString(oryxosRoot.resolve("oryxos.db-wal"), "fake wal bytes SECRET-WAL");

    mvc.perform(get("/api/v1/workspace/file").param("path", "channels.yaml"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
    mvc.perform(get("/api/v1/workspace/file").param("path", "mcp_servers.yaml"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/workspace/file").param("path", "oryxos.db"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/workspace/file").param("path", "oryxos.db-wal"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/workspace/download").param("path", "channels.yaml"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/workspace/download").param("path", "oryxos.db"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("file/download 禁止经软链别名读 channels.yaml（投影后复检）")
  void readViaSymlinkAliasToAdminConfigIsRejected() throws Exception {
    Files.writeString(oryxosRoot.resolve("channels.yaml"), "token: SECRET-CHANNEL\n");
    Path alias = oryxosRoot.resolve("agents/demo/alias.yaml");
    try {
      Files.createSymbolicLink(alias, Path.of("../../channels.yaml"));
    } catch (IOException | UnsupportedOperationException e) {
      Assumptions.assumeTrue(false, "当前环境无法创建软链: " + e.getMessage());
    }

    mvc.perform(get("/api/v1/workspace/file").param("path", "agents/demo/alias.yaml"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/workspace/download").param("path", "agents/demo/alias.yaml"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("工作区写入口禁止写 Agent skills 绑定视图")
  void writeThroughAgentSkillsIsRejected() throws Exception {
    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"agents/demo/skills/report/SKILL.md\",\"content\":\"bad\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("写 AGENT.md 走 lifecycle.update，不直接落盘")
  void writeAgentMarkdown_goesThroughUpdate() throws Exception {
    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"path\":\"agents/demo/AGENT.md\",\"content\":\"---\\nname: demo\\n---\\n新正文\"}"))
        .andExpect(status().isOk());
    org.mockito.Mockito.verify(lifecycle).update("demo", "---\nname: demo\n---\n新正文");
    org.junit.jupiter.api.Assertions.assertEquals(
        "---\nname: demo\n---\n正文内容", Files.readString(oryxosRoot.resolve("agents/demo/AGENT.md")));
  }

  @Test
  @DisplayName("agent.md 大小写不同仍走 update，不能绕过重注册")
  void writeAgentMarkdown_wrongCaseStillGoesThroughUpdate() throws Exception {
    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"path\":\"agents/demo/agent.md\",\"content\":\"---\\nname: demo\\n---\\nbypass\"}"))
        .andExpect(status().isOk());
    org.mockito.Mockito.verify(lifecycle).update("demo", "---\nname: demo\n---\nbypass");
    try (java.util.stream.Stream<Path> entries = Files.list(oryxosRoot.resolve("agents/demo"))) {
      org.junit.jupiter.api.Assertions.assertFalse(
          entries.anyMatch(p -> "agent.md".equals(String.valueOf(p.getFileName()))),
          "不得另写一份 agent.md 绕过 update");
    }
  }

  @Test
  @DisplayName("Skills/ 大小写不同仍禁止写入绑定视图")
  void writeThroughAgentSkillsWrongCaseIsRejected() throws Exception {
    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"agents/demo/Skills/report/SKILL.md\",\"content\":\"bad\"}"))
        .andExpect(status().isBadRequest());
    org.junit.jupiter.api.Assertions.assertFalse(
        Files.exists(oryxosRoot.resolve("agents/demo/Skills/report/SKILL.md")));
  }

  @Test
  @DisplayName("工作区写入口禁止直接或经归档路径段修改共享 Skill")
  void writeThroughSharedSkillPathsIsRejected() throws Exception {
    Path shared = Files.createDirectories(oryxosRoot.resolve("skills/report"));
    Path skillFile = Files.writeString(shared.resolve("SKILL.md"), "original");
    // 不建软链：路径段守卫已拒 skills/**；归档侧同名 skills 段亦拒（免 Windows 建链权限）
    Files.createDirectories(oryxosRoot.resolve("archive/ops-old/skills"));

    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"skills/report/SKILL.md\",\"content\":\"bad\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"path\":\"archive/ops-old/skills/report/SKILL.md\",\"content\":\"bad\"}"))
        .andExpect(status().isBadRequest());
    org.junit.jupiter.api.Assertions.assertEquals("original", Files.readString(skillFile));
  }

  @Test
  @DisplayName("工作区写入口禁止 Knowledge 绑定视图与共享实体")
  void writeThroughKnowledgePathsIsRejected() throws Exception {
    Path shared = Files.createDirectories(oryxosRoot.resolve("knowledge/ops"));
    Path doc = Files.writeString(shared.resolve("doc.md"), "original");

    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"knowledge/ops/doc.md\",\"content\":\"bad\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"agents/demo/knowledge/ops/doc.md\",\"content\":\"bad\"}"))
        .andExpect(status().isBadRequest());
    org.junit.jupiter.api.Assertions.assertEquals("original", Files.readString(doc));
  }

  @Test
  @DisplayName("工作区读取悬空软连接返回 400 而非 500")
  void danglingLinkReturnsBadRequest() throws Exception {
    SymlinkAssumptions.assumeSymlinksSupported(oryxosRoot);
    Files.createSymbolicLink(
        oryxosRoot.resolve("agents/demo/dangling"), Path.of("../../skills/missing"));

    mvc.perform(get("/api/v1/workspace/file").param("path", "agents/demo/dangling/SKILL.md"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  @DisplayName("file 超过 10 MiB 文本上限 → 400，不整文件读入")
  void file_oversizedText_returns400() throws Exception {
    Path output = Files.createDirectories(oryxosRoot.resolve("output"));
    Path big = output.resolve("huge.txt");
    // 写 MAX+1 字节：不依赖稀疏文件，保证 Files.size 超限
    byte[] chunk = new byte[1024 * 1024];
    try (var out = Files.newOutputStream(big)) {
      long written = 0;
      long target = WorkspaceApiController.MAX_TEXT_READ_BYTES + 1;
      while (written < target) {
        int n = (int) Math.min(chunk.length, target - written);
        out.write(chunk, 0, n);
        written += n;
      }
    }
    mvc.perform(get("/api/v1/workspace/file").param("path", "output/huge.txt"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message", containsString("10 MiB")));
  }

  @Test
  @DisplayName("writeFile 超过 10 MiB → 400，不落盘")
  void writeFile_oversizedText_returns400() throws Exception {
    String huge = "x".repeat((int) WorkspaceApiController.MAX_TEXT_READ_BYTES + 1);
    String body =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .writeValueAsString(java.util.Map.of("path", "output/huge.txt", "content", huge));
    mvc.perform(
            post("/api/v1/workspace/file").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message", containsString("10 MiB")));
    org.junit.jupiter.api.Assertions.assertFalse(
        Files.exists(oryxosRoot.resolve("output/huge.txt")));
  }

  @Test
  @DisplayName("master.key 是系统保留文件：workspace file/download/write 一律 400，且密钥内容不得被改写")
  void masterKeyIsReservedForReadAndWrite() throws Exception {
    // 主密钥真实存在于工作区根（{oryxosRoot}/master.key，022）—— 正是通用读路径能触达的位置
    Path key = oryxosRoot.resolve("master.key");
    Files.writeString(key, "BASE64-MASTER-KEY");
    String original = Files.readString(key);

    mvc.perform(get("/api/v1/workspace/file").param("path", "master.key"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message", containsString("master.key")));
    mvc.perform(get("/api/v1/workspace/file").param("path", "MASTER.KEY"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/workspace/download").param("path", "master.key"))
        .andExpect(status().isBadRequest());
    // "./" 前缀不能绕过：守卫按路径段判定，"." 段不影响后面的保留名段
    mvc.perform(get("/api/v1/workspace/file").param("path", "./master.key"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/workspace/download").param("path", "master.key"))
        .andExpect(
            content().string(org.hamcrest.Matchers.not(containsString("BASE64-MASTER-KEY"))));
    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"master.key\",\"content\":\"ATTACKER-REPLACED\"}"))
        .andExpect(status().isBadRequest());

    org.junit.jupiter.api.Assertions.assertEquals(original, Files.readString(key), "主密钥不得被通用写入口改写");
  }

  @Test
  @DisplayName("目录白名单：读入口只放行四个树根，logs/ 与自建目录一律 400")
  void readIsLimitedToTheAllowlistedRoots() throws Exception {
    Files.createDirectories(oryxosRoot.resolve("logs"));
    Files.writeString(oryxosRoot.resolve("logs/app.log"), "log line");
    Files.createDirectories(oryxosRoot.resolve("custom"));
    Files.writeString(oryxosRoot.resolve("custom/notes.md"), "custom notes");

    mvc.perform(get("/api/v1/workspace/file").param("path", "logs/app.log"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message", containsString("工作区根之外")));
    mvc.perform(get("/api/v1/workspace/download").param("path", "logs/app.log"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/workspace/file").param("path", "custom/notes.md"))
        .andExpect(status().isBadRequest());
    // 允许的根照常可读（archive/ 在默认可读白名单里）
    Files.createDirectories(oryxosRoot.resolve("archive/2026"));
    Files.writeString(oryxosRoot.resolve("archive/2026/report.md"), "archived");
    mvc.perform(get("/api/v1/workspace/file").param("path", "archive/2026/report.md"))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("目录白名单：写入口不放行自建目录，也不放行 skills/（绑定视图只读）")
  void writeIsLimitedToTheWritableRoots() throws Exception {
    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"custom/notes.md\",\"content\":\"x\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"skills/demo/SKILL.md\",\"content\":\"x\"}"))
        .andExpect(status().isBadRequest());
    org.junit.jupiter.api.Assertions.assertFalse(
        Files.exists(oryxosRoot.resolve("custom/notes.md")), "被拒的写不得落盘");
  }

  @Test
  @DisplayName("目录白名单可由配置放宽：readable-roots 含 custom 后该目录可读")
  void readableRootsCanBeRelaxedByConfiguration() throws Exception {
    Files.createDirectories(oryxosRoot.resolve("custom"));
    Files.writeString(oryxosRoot.resolve("custom/notes.md"), "custom notes");
    WorkspaceApiController controller =
        controllerWithRoots("agents,skills,output,archive,custom", null);

    MockMvc relaxed =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    relaxed
        .perform(get("/api/v1/workspace/file").param("path", "custom/notes.md"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("custom notes")));
  }

  @Test
  @DisplayName("目录白名单配置非法（含 ..）时启动校验直接抛，不静默降级")
  void illegalAllowlistConfigurationFailsFast() throws Exception {
    WorkspaceApiController controller = controllerWithRoots("../etc", null);
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class, controller::warnOnRelaxedRoots);
  }

  private WorkspaceApiController controllerWithRoots(String readable, String writable)
      throws Exception {
    WorkspaceApiController controller =
        new WorkspaceApiController(oryxosRoot.toString(), lifecycle, clusterRefreshService());
    if (readable != null) {
      java.lang.reflect.Field f =
          WorkspaceApiController.class.getDeclaredField("readableRootsSetting");
      f.setAccessible(true);
      f.set(controller, readable);
    }
    if (writable != null) {
      java.lang.reflect.Field f =
          WorkspaceApiController.class.getDeclaredField("writableRootsSetting");
      f.setAccessible(true);
      f.set(controller, writable);
    }
    return controller;
  }
}
