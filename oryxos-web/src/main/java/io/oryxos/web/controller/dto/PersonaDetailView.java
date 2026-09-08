package io.oryxos.web.controller.dto;

import io.oryxos.persona.PersonaService.PersonaEntry;

/** GET /personas/{key} 视图：卡片元数据 + 源文件全文（sourceContent，喂 import-preview）（025 人格库）。 */
public record PersonaDetailView(
    String key,
    String label,
    String description,
    String emoji,
    String sourceFile,
    boolean builtin,
    String sourceContent) {

  public static PersonaDetailView from(PersonaEntry e, String sourceContent) {
    return new PersonaDetailView(
        e.key(), e.label(), e.description(), e.emoji(), e.sourceFile(), e.builtin(), sourceContent);
  }
}
