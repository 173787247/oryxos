package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * specs/025-persona-library 验收 harness：AgencyAgentsImporterTest——组装 + tools 交集 + persona 兜底 +
 * 可无损拆回。
 */
class AgencyAgentsImporterTest {

  @Test
  @DisplayName("导入组装：role 兜底 + tools 交集 + provider 兜底 + 产出可无损拆回")
  void toMarkdown_intersectsToolsAndDefaultsPersona() {
    AgencyAgentsParser.ParsedExpert e =
        new AgencyAgentsParser.ParsedExpert(
            "软件架构师",
            "软件架构与系统设计专家",
            null, // role 为空 → 兜底
            "有战略眼光、务实、注重权衡",
            "记住各种架构模式",
            "专业简洁",
            "清晰权衡得失，优先选可演进方案", // keyRules（正向）→ values
            "不做未授权的生产变更", // boundaries（红线）→ boundaries
            null, // sampleStyle 空不写
            "## 核心使命\n正文");

    String md = new AgencyAgentsImporter().toMarkdown(e, "deepseek", Set.of("read_file", "shell"));

    assertTrue(md.contains("role: 乐于助人的助手"), "role 兜底");
    assertFalse(md.contains("notify"), "notify 不在本机 → 交集剔除");
    assertTrue(md.contains("provider:\n  name: deepseek"), "defaultProvider 兜底");

    AgentMarkdown.Parsed roundTrip = AgentMarkdown.split(md); // 产出能无损拆回
    @SuppressWarnings("unchecked")
    Map<String, Object> persona = (Map<String, Object>) roundTrip.frontmatter().get("persona");
    assertEquals("乐于助人的助手", persona.get("role"));
  }

  @Test
  @DisplayName("七字段：values 写正向规则、boundaries 写红线、sample_style 写风格示范；空字段不写")
  void toMarkdown_writesAllSevenPersonaFields_whenPresent() {
    AgencyAgentsParser.ParsedExpert e =
        new AgencyAgentsParser.ParsedExpert(
            "专家",
            "描述",
            "角色",
            "专业可靠",
            "背景",
            "简洁友好",
            "诚实准确，不确定就明说",
            "不执行未授权的高风险操作；不泄露敏感信息",
            "先给结论，再给依据",
            "## 核心使命\n正文");

    String md = new AgencyAgentsImporter().toMarkdown(e, "deepseek", Set.of("read_file"));

    assertTrue(md.contains("role: 角色"));
    assertTrue(md.contains("values: 诚实准确，不确定就明说"));
    assertTrue(md.contains("boundaries: 不执行未授权的高风险操作；不泄露敏感信息"));
    assertTrue(md.contains("sample_style: 先给结论，再给依据"));
  }

  @Test
  @DisplayName("traits/tone/values/boundaries/sample_style 非空才写")
  void toMarkdown_onlyWritesNonBlankPersonaFields() {
    AgencyAgentsParser.ParsedExpert e =
        new AgencyAgentsParser.ParsedExpert(
            "专家",
            "描述",
            "角色",
            null, // traits 空
            null, // background 空
            null, // tone 空
            null, // values 空
            null, // boundaries 空
            null, // sampleStyle 空
            "## 核心使命\n正文");

    String md = new AgencyAgentsImporter().toMarkdown(e, "deepseek", Set.of("read_file"));

    assertTrue(md.contains("role: 角色"));
    assertFalse(md.contains("traits:"), "traits 空不写");
    assertFalse(md.contains("boundaries:"), "boundaries 空不写");
    assertFalse(md.contains("sample_style:"), "sample_style 空不写");
  }
}
