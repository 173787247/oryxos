package io.oryxos.web.controller.dto;

import java.util.List;
import java.util.Map;

/** POST /agents/{name}/files 请求体：保存（可能被用户改过的）一组 Agent 文件，写入即生效。 */
public record SaveFilesRequest(
    Map<String, String> files, List<String> skillBindings, List<String> knowledgeBindings) {

  public SaveFilesRequest {
    files = files == null ? Map.of() : Map.copyOf(files);
    skillBindings = skillBindings == null ? null : List.copyOf(skillBindings);
    knowledgeBindings = knowledgeBindings == null ? null : List.copyOf(knowledgeBindings);
  }

  public SaveFilesRequest(Map<String, String> files, List<String> skillBindings) {
    this(files, skillBindings, null);
  }

  public SaveFilesRequest(Map<String, String> files) {
    this(files, null, null);
  }
}
