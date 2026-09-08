package io.oryxos.web.controller.dto;

/** PUT /agents/{name}/persona 请求体：结构化编辑 persona 段（只动 AGENT.md frontmatter 的 persona 块）。 */
public record UpdatePersonaRequest(
    String name,
    String role,
    String traits,
    String tone,
    String values,
    String boundaries,
    String sampleStyle) {}
