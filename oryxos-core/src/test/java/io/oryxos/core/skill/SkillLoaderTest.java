package io.oryxos.core.skill;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.testing.SymlinkAssumptions;
import io.oryxos.core.workspace.SharedPosixWorkspaceStorageProvider;
import io.oryxos.core.workspace.WorkspaceStorage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillLoaderTest {

  @TempDir Path root;

  @Test
  @DisplayName("公共目录名、frontmatter name 和 description 必须完整一致")
  void metadataMustBeCompleteAndMatchDirectory() throws IOException {
    SkillLoader loader = new SkillLoader(root);
    Path mismatch = root.resolve("actual");
    Files.createDirectories(mismatch);
    Files.writeString(mismatch.resolve("SKILL.md"), "---\nname: other\ndescription: d\n---\nbody");
    assertThrows(IllegalArgumentException.class, () -> loader.deriveSkill(mismatch));

    assertThrows(
        IllegalArgumentException.class,
        () -> loader.parse("---\ndescription: d\n---\nbody", "fallback"));
    assertThrows(
        IllegalArgumentException.class, () -> loader.parse("---\nname: valid\n---\nbody", "valid"));
    assertThrows(
        IllegalArgumentException.class,
        () -> loader.parse("---\nname: yes\ndescription: d\n---\nbody", "yes"));
  }

  @Test
  void deriveSkillFollowsRelativeSkillFileLinkWithinTheSkillsRoot() throws IOException {
    SymlinkAssumptions.assumeSymlinksSupported(root);
    Files.writeString(
        root.resolve(".linked-skill.md"),
        "---\nname: linked\ndescription: linked skill\n---\nbody");
    Path skill = Files.createDirectories(root.resolve("linked"));
    Files.createSymbolicLink(skill.resolve("SKILL.md"), Path.of("../.linked-skill.md"));

    assertTrue(new SkillLoader(root).loadAll().exists("linked"));
  }

  @Test
  void sharedWorkspaceOutageFailsReloadInsteadOfPublishingAnEmptyRegistry() throws Exception {
    Path shared = Files.createDirectories(root.resolve("shared"));
    Files.writeString(shared.resolve(".workspace-id"), "skills-fixture");
    Path skill = Files.createDirectories(shared.resolve("skills/demo"));
    Files.writeString(
        skill.resolve("SKILL.md"), "---\nname: demo\ndescription: demo skill\n---\nbody");

    try (WorkspaceStorage storage =
        new SharedPosixWorkspaceStorageProvider().open(shared, "skills-fixture")) {
      SkillLoader loader = new SkillLoader(storage.root().resolve("skills"));
      assertTrue(loader.loadAll().exists("demo"));

      Files.writeString(shared.resolve(".workspace-id"), "wrong");

      assertThrows(UncheckedIOException.class, loader::loadAll);

      Files.writeString(shared.resolve(".workspace-id"), "skills-fixture");
      assertTrue(loader.loadAll().exists("demo"));
      Files.delete(shared.resolve(".workspace-id"));
      assertThrows(UncheckedIOException.class, loader::loadAll);

      Files.writeString(shared.resolve(".workspace-id"), "skills-fixture");
      Files.delete(skill.resolve("SKILL.md"));
      Files.delete(skill);
      assertFalse(loader.loadAll().exists("demo"));
    }
  }

  @Test
  void identityLossDuringSkillDerivationIsStorageFailureRatherThanBadConfiguration()
      throws Exception {
    Path shared = Files.createDirectories(root.resolve("mid-derive-shared"));
    Path marker = shared.resolve(".workspace-id");
    Files.writeString(marker, "skills-fixture");
    Path skill = Files.createDirectories(shared.resolve("skills/demo"));
    Files.writeString(
        skill.resolve("SKILL.md"), "---\nname: demo\ndescription: demo skill\n---\nbody");

    try (WorkspaceStorage storage =
        new SharedPosixWorkspaceStorageProvider().open(shared, "skills-fixture")) {
      SkillLoader loader =
          new SkillLoader(storage.root().resolve("skills")) {
            @Override
            public Skill deriveSkill(Path skillDir) {
              try {
                Files.delete(marker);
              } catch (IOException failure) {
                throw new UncheckedIOException(failure);
              }
              try {
                return super.deriveSkill(skillDir);
              } finally {
                try {
                  Files.writeString(marker, "skills-fixture");
                } catch (IOException failure) {
                  throw new UncheckedIOException(failure);
                }
              }
            }
          };

      assertThrows(UncheckedIOException.class, loader::loadAll);
    }
  }

  @Test
  @DisplayName("SKILL.md 超过 10 MiB → IllegalArgumentException；loadAll 跳过不阻断")
  void oversizedSkillMd_failsLoudAndLoadAllSkips() throws IOException {
    Path huge = Files.createDirectories(root.resolve("huge"));
    Path skillMd = huge.resolve("SKILL.md");
    Files.writeString(skillMd, "---\nname: huge\ndescription: big\n---\nok");
    long target = SkillLoader.MAX_SKILL_MD_BYTES + 1;
    byte[] chunk = new byte[1024 * 1024];
    try (var out = Files.newOutputStream(skillMd, java.nio.file.StandardOpenOption.APPEND)) {
      long written = Files.size(skillMd);
      while (written < target) {
        int n = (int) Math.min(chunk.length, target - written);
        out.write(chunk, 0, n);
        written += n;
      }
    }
    Path ok = Files.createDirectories(root.resolve("ok-skill"));
    Files.writeString(ok.resolve("SKILL.md"), "---\nname: ok-skill\ndescription: fine\n---\nbody");

    SkillLoader loader = new SkillLoader(root);
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> loader.deriveSkill(huge));
    assertTrue(ex.getMessage().contains("10 MiB"));

    SkillRegistry reg = loader.loadAll();
    assertFalse(reg.exists("huge"), "超限 Skill 应被跳过");
    assertTrue(reg.exists("ok-skill"), "其它 Skill 不受阻断");
  }
}
