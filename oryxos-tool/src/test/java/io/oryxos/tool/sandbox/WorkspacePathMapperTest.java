package io.oryxos.tool.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 024 T005：工作区路径双向翻译。跨平台注意：宿主侧期望值用 {@code workspaceRoot.resolve(...)} 构造 （随平台分隔符），容器侧恒为 {@code
 * /}——翻译规则本身钉死（RQ-4 固定挂载点）。
 */
class WorkspacePathMapperTest {

  @TempDir Path tempDir;

  private WorkspacePathMapper mapper() {
    return new WorkspacePathMapper(tempDir.resolve(".oryxos"));
  }

  @Test
  @DisplayName("双向翻译_工作区子路径_host↔/workspace")
  void bidirectionalSubPath() {
    WorkspacePathMapper mapper = mapper();
    Path hostFile = mapper.workspaceRoot().resolve("agents").resolve("ops").resolve("script.py");
    String containerPath = mapper.toContainer(hostFile.toString());

    assertEquals("/workspace/agents/ops/script.py", containerPath);
    assertEquals(hostFile.toString(), mapper.toHost(containerPath));
  }

  @Test
  @DisplayName("工作区根本体_往返为_/workspace")
  void workspaceRootItself() {
    WorkspacePathMapper mapper = mapper();
    assertEquals("/workspace", mapper.toContainer(mapper.workspaceRoot().toString()));
    assertEquals(mapper.workspaceRoot().toString(), mapper.toHost("/workspace"));
  }

  @Test
  @DisplayName("非工作区绝对路径_直通不翻译")
  void nonWorkspaceAbsolutePathPassesThrough() {
    WorkspacePathMapper mapper = mapper();
    assertEquals("/etc/os-release", mapper.toContainer("/etc/os-release"));
    assertEquals("/etc/os-release", mapper.toHost("/etc/os-release"));
    assertFalse(mapper.isWorkspacePath("/etc/os-release"));
  }

  @Test
  @DisplayName("相对路径_直通不翻译")
  void relativePathPassesThrough() {
    WorkspacePathMapper mapper = mapper();
    assertEquals("script.py", mapper.toContainer("script.py"));
    assertEquals("agents/ops", mapper.toHost("agents/ops"));
    assertFalse(mapper.isWorkspacePath("script.py"));
  }

  @Test
  @DisplayName("容器自有路径_/tmp_不反译")
  void containerOwnPathNotReverseTranslated() {
    WorkspacePathMapper mapper = mapper();
    assertEquals("/tmp/out.txt", mapper.toHost("/tmp/out.txt"));
  }

  @Test
  @DisplayName("尾分隔符归一")
  void trailingSeparatorNormalized() {
    WorkspacePathMapper mapper = mapper();
    assertEquals(mapper.workspaceRoot().toString(), mapper.toHost("/workspace/"));
  }

  @Test
  @DisplayName("isWorkspacePath_组件级前缀匹配")
  void isWorkspacePathComponentWise() {
    WorkspacePathMapper mapper = mapper();
    assertTrue(mapper.isWorkspacePath(mapper.workspaceRoot().resolve("agents").toString()));
    // 字符串前缀相似但组件不同的目录不算工作区（/opt/x.oryxos 不是 /opt/x/.oryxos）
    Path sibling = mapper.workspaceRoot().getParent().resolve(".oryxos-backup");
    assertFalse(mapper.isWorkspacePath(sibling.toString()));
  }
}
