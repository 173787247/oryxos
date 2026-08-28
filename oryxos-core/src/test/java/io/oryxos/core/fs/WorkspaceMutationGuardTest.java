package io.oryxos.core.fs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorkspaceMutationGuardTest {

  @Test
  @DisplayName("拒绝共享 skills/knowledge 与 Agent 绑定视图下的内容写")
  void rejectSkillKnowledgeContentWrite() {
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkspaceMutationGuard.rejectSkillKnowledgeContentWrite("skills/report/SKILL.md"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            WorkspaceMutationGuard.rejectSkillKnowledgeContentWrite(
                "agents/demo/skills/report/SKILL.md"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            WorkspaceMutationGuard.rejectSkillKnowledgeContentWrite(
                "agents/demo/Skills/report/x.md"));
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkspaceMutationGuard.rejectSkillKnowledgeContentWrite("knowledge/ops/doc.md"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            WorkspaceMutationGuard.rejectSkillKnowledgeContentWrite(
                "agents/demo/knowledge/ops/doc.md"));
    assertDoesNotThrow(
        () -> WorkspaceMutationGuard.rejectSkillKnowledgeContentWrite("agents/demo/notes.md"));
    assertDoesNotThrow(
        () -> WorkspaceMutationGuard.rejectSkillKnowledgeContentWrite("output/report.md"));
  }

  @Test
  @DisplayName("拒绝直写 agents/<name>/AGENT.md")
  void rejectAgentMdDirectWrite() {
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkspaceMutationGuard.rejectAgentMdDirectWrite("agents/demo/AGENT.md"));
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkspaceMutationGuard.rejectAgentMdDirectWrite("agents/demo/agent.md"));
    assertDoesNotThrow(
        () -> WorkspaceMutationGuard.rejectAgentMdDirectWrite("agents/demo/notes.md"));
    assertDoesNotThrow(
        () -> WorkspaceMutationGuard.rejectAgentMdDirectWrite("agents/demo/skills/AGENT.md"));
  }

  @Test
  @DisplayName("拒绝 make_dir 占用 bind 叶子；允许建 skills 目录本身")
  void rejectBindSlotCreate() {
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkspaceMutationGuard.rejectBindSlotCreate("agents/demo/skills/report"));
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkspaceMutationGuard.rejectBindSlotCreate("agents/demo/knowledge/ops"));
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkspaceMutationGuard.rejectBindSlotCreate("agents/demo/skills/report/sub"));
    assertDoesNotThrow(() -> WorkspaceMutationGuard.rejectBindSlotCreate("agents/demo/skills"));
    assertDoesNotThrow(() -> WorkspaceMutationGuard.rejectBindSlotCreate("agents/demo/output"));
  }

  @Test
  @DisplayName("拒绝 delete/move 拆 bind 叶子")
  void rejectBindLinkDetach() {
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkspaceMutationGuard.rejectBindLinkDetach("agents/demo/skills/report"));
    assertDoesNotThrow(
        () -> WorkspaceMutationGuard.rejectBindLinkDetach("agents/demo/skills/report/SKILL.md"));
  }
}
