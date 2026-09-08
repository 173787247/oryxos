package io.oryxos.web.controller.dto;

import io.oryxos.persona.PersonaService.PersonaEntry;

/** GET /personas 视图：内置（classpath 只读）+ 自定义（.oryxos/personas/）合并列表的卡片元数据（025 人格库）。 */
public record PersonaLibraryView(
    String key, // 唯一 slug（也是导入时的建议 Agent 名）
    String label, // 展示名（内置用决策表 label，自定义取源 frontmatter name）
    String description, // 卡片副标题（源 frontmatter description）
    String emoji, // 卡片图标（源 frontmatter emoji）
    String sourceFile, // 内置：agency-agents-zh 原始相对路径（署名）；自定义：null
    boolean builtin) { // true=内置只读，不可 CRUD；false=自定义，可增删改

  public static PersonaLibraryView from(PersonaEntry e) {
    return new PersonaLibraryView(
        e.key(), e.label(), e.description(), e.emoji(), e.sourceFile(), e.builtin());
  }
}
