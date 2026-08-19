package io.oryxos.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnowledgeManifestTest {

  @TempDir Path root;

  @Test
  void readsManifestWithLocalBackendDefault() throws IOException {
    Path dir = Files.createDirectories(root.resolve("ops-manual"));
    Files.writeString(
        dir.resolve(KnowledgeManifest.FILE),
        "---\nname: ops-manual\ndescription: 运维手册\n---\n库级说明正文不入索引\n");

    KnowledgeManifest manifest = KnowledgeManifest.read(dir);

    assertEquals("ops-manual", manifest.name());
    assertEquals("运维手册", manifest.description());
    assertEquals("local", manifest.backend());
    assertTrue(manifest.connection().isEmpty());
  }

  @Test
  void keepsEnvPlaceholderUnresolvedForRemoteBackend() throws IOException {
    Path dir = Files.createDirectories(root.resolve("remote-kb"));
    Files.writeString(
        dir.resolve(KnowledgeManifest.FILE),
        "---\nname: remote-kb\ndescription: 远程库\nbackend: ragflow\nconnection:\n"
            + "  base_url: https://ragflow.internal\n  api_key: ${RAGFLOW_API_KEY}\n---\n");

    KnowledgeManifest manifest = KnowledgeManifest.read(dir);

    assertEquals("ragflow", manifest.backend());
    // 凭证占位原样保留：清单层绝不解析/落明文（宪法 VI）
    assertEquals("${RAGFLOW_API_KEY}", manifest.connection().get("api_key"));
  }

  @Test
  void rejectsMissingFieldsAndNameMismatch() throws IOException {
    Path noName = Files.createDirectories(root.resolve("no-name"));
    Files.writeString(noName.resolve(KnowledgeManifest.FILE), "---\ndescription: x\n---\n");
    assertThrows(IllegalArgumentException.class, () -> KnowledgeManifest.read(noName));

    Path noDesc = Files.createDirectories(root.resolve("no-desc"));
    Files.writeString(noDesc.resolve(KnowledgeManifest.FILE), "---\nname: no-desc\n---\n");
    assertThrows(IllegalArgumentException.class, () -> KnowledgeManifest.read(noDesc));

    Path mismatch = Files.createDirectories(root.resolve("actual"));
    Files.writeString(
        mismatch.resolve(KnowledgeManifest.FILE), "---\nname: other\ndescription: x\n---\n");
    assertThrows(IllegalArgumentException.class, () -> KnowledgeManifest.read(mismatch));

    Path missing = Files.createDirectories(root.resolve("missing"));
    assertThrows(IllegalArgumentException.class, () -> KnowledgeManifest.read(missing));

    Path noFence = Files.createDirectories(root.resolve("no-fence"));
    Files.writeString(noFence.resolve(KnowledgeManifest.FILE), "name: no-fence\n");
    assertThrows(IllegalArgumentException.class, () -> KnowledgeManifest.read(noFence));
  }
}
