package io.oryxos.web.controller.dto;

import java.util.List;

/** PUT /agents/{name}/basic 请求体：结构化编辑 Agent 基本信息（只动 AGENT.md frontmatter 的几个 key）。 */
public record UpdateAgentBasicRequest(
    String description, String provider, String model, List<String> skills) {

  public UpdateAgentBasicRequest {
    skills = skills == null ? List.of() : List.copyOf(skills);
  }
}
