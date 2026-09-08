package io.oryxos.web.controller.dto;

import io.oryxos.core.agent.AgencyAgentsParser;
import io.oryxos.core.agent.AgentValidation;

/** import-preview 返回：渲染出的 AGENT.md 全文 + ParsedExpert 字段投影 + 派生名 + dry-run 校验结果，不落盘。 */
public record ImportPreviewView(
    String name, // 派生/提供的 Agent 名（前端可改，import 时用改过的）
    String agentMarkdown, // 渲染出的 AGENT.md 全文
    ImportExpertView expert,
    ValidationView validation) {

  /** 人格字段投影：ParsedExpert 的 7 个 persona 相关字段 + 任务层正文（展示用，boundaries/sampleStyle 也在列）。 */
  public record ImportExpertView(
      String displayName,
      String description,
      String role,
      String traits,
      String background,
      String communication,
      String keyRules,
      String boundaries,
      String sampleStyle,
      String body) {
    public static ImportExpertView from(AgencyAgentsParser.ParsedExpert e) {
      return new ImportExpertView(
          e.displayName(),
          e.description(),
          e.role(),
          e.traits(),
          e.background(),
          e.communication(),
          e.keyRules(),
          e.boundaries(),
          e.sampleStyle(),
          e.body());
    }
  }

  /** dry-run 校验结果：valid 时带解析出的 provider/model（预览即知落什么模型），invalid 时带可读错误信息。 */
  public record ValidationView(boolean valid, String message, String provider, String model) {

    public static ValidationView from(AgentValidation v) {
      if (v.valid()) {
        String provider = v.profile().provider() == null ? null : v.profile().provider().name();
        String model = v.profile().provider() == null ? null : v.profile().provider().model();
        return new ValidationView(true, "可解析", provider, model);
      }
      return new ValidationView(false, v.error(), null, null);
    }
  }
}
