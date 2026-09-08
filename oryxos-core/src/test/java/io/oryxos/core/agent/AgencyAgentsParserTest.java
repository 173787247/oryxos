package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * specs/025-persona-library 验收 harness：AgencyAgentsParserTest——agency-agents-zh 源文件解析与 ## 关键词分类。
 */
class AgencyAgentsParserTest {

  @Test
  @DisplayName(
      "解析专家文件：frontmatter 名/描述 + ## 关键词分类（身份/记忆→role/traits/background；沟通→tone；规则→values；其余→body）")
  void parse_classifiesByHeadingKeywords() {
    String src =
        """
        ---
        name: 软件架构师
        description: 软件架构与系统设计专家
        emoji: 🏛️
        color: indigo
        ---
        # 软件架构师
        你是**软件架构师**，一个帮助团队把复杂需求变成可落地系统设计的专家。

        ## 你的身份与记忆
        - **角色**：软件架构与系统设计专家
        - **个性**：有战略眼光、务实、注重权衡
        - **记忆**：记住各种架构模式及失败模式

        ## 核心使命
        帮助团队从需求到架构设计。

        ## 关键规则
        1. **先找问题，不要先跳到方案** — 找到底层痛点。
        2. **权衡取舍要透明** — 记录每项决策的理由。

        ## 沟通风格
        专业简洁，先结论后依据。
        """;

    AgencyAgentsParser.ParsedExpert e = new AgencyAgentsParser().parse(src);

    assertEquals("软件架构师", e.displayName());
    assertTrue(e.role().contains("软件架构与系统设计专家"));
    assertTrue(e.traits().contains("有战略眼光"));
    assertTrue(e.background().contains("记住各种架构模式")); // 记忆/经验 → 背景，不进 persona
    assertEquals("专业简洁，先结论后依据。", e.communication());
    assertTrue(e.keyRules().contains("权衡取舍要透明")); // 正向规则留 values
    assertFalse(e.keyRules().contains("不要先跳到方案"), "否定式红线拆出，不留 values");
    assertTrue(e.boundaries().contains("不要先跳到方案"), "否定式红线 → boundaries");
    assertTrue(e.body().contains("核心使命")); // 任务层进正文
    assertFalse(e.body().contains("你的身份与记忆")); // 人格层不进正文
    assertFalse(e.body().contains("emoji")); // 装饰字段丢弃
  }

  @Test
  @DisplayName("否定式红线按行拆进 boundaries；正向规则留 values；无否定词时 boundaries 为空")
  void parse_splitsNegativeRedLinesIntoBoundaries() {
    String src =
        """
        ---
        name: 审查员
        description: 代码审查专家
        ---
        # 审查员

        ## 🔧 关键规则
        1. **具体明确** — 说"第 42 行可能存在 SQL 注入"，而不是"有安全问题"
        2. **建议而非命令** — 说"可以考虑用 X"，而不是"改成 X"
        3. **不要分多轮逐步反馈** — 一次审查给出完整意见

        ## 核心使命
        审查代码。
        """;

    AgencyAgentsParser.ParsedExpert e = new AgencyAgentsParser().parse(src);

    assertTrue(e.keyRules().contains("具体明确"), "正向规则留 values");
    assertTrue(e.keyRules().contains("建议而非命令"), "「而非」不是硬否定词，留 values");
    assertFalse(e.keyRules().contains("不要分多轮"), "否定式红线拆出");
    assertTrue(e.boundaries().contains("不要分多轮"), "「不要」开头的行 → boundaries");
    assertFalse(e.boundaries().contains("具体明确"), "正向规则不进 boundaries");
  }

  @Test
  @DisplayName("「不在/不能/别」等源文件实测红线措辞也拆进 boundaries")
  void parse_splitsWideNegativeHints() {
    String src =
        """
        ---
        name: AI 工程师
        description: 模型工程专家
        ---
        # AI 工程师

        ## 关键规则
        - 训练代码必须可复现——随机种子、环境依赖、数据版本全部锁定
        - 不在生产环境用 `model.eval()` 没调的模型
        - GPU 资源按需申请，训练完及时释放，别当矿主

        ## 核心使命
        训练和部署模型。
        """;

    AgencyAgentsParser.ParsedExpert e = new AgencyAgentsParser().parse(src);

    assertTrue(e.keyRules().contains("训练代码必须可复现"), "正向规则留 values");
    assertFalse(e.keyRules().contains("不在生产环境"), "「不在」红线拆出");
    assertFalse(e.keyRules().contains("别当矿主"), "「别」红线拆出");
    assertTrue(e.boundaries().contains("不在生产环境"), "「不在」行 → boundaries");
    assertTrue(e.boundaries().contains("别当矿主"), "「别」行 → boundaries");
  }

  @Test
  @DisplayName("评论/回复格式段 → sampleStyle；技术交付物示例/模板不进 sampleStyle，留 body")
  void parse_routesCommentFormatIntoSampleStyle() {
    String src =
        """
        ---
        name: 审查员
        description: 代码审查专家
        ---
        # 审查员

        ## 📝 审查评论格式
        🔴 **安全：SQL 注入风险**
        第 42 行：用户输入直接拼接。

        ## 技术交付物
        ### RAG 服务示例
        ```java
        // 一段示例代码
        ```
        """;

    AgencyAgentsParser.ParsedExpert e = new AgencyAgentsParser().parse(src);

    assertTrue(e.sampleStyle().contains("SQL 注入风险"), "评论格式段 → sampleStyle");
    assertFalse(e.sampleStyle().contains("RAG 服务示例"), "技术交付物示例不进 sampleStyle");
    assertTrue(e.body().contains("RAG 服务示例"), "技术交付物示例留 body");
  }

  @Test
  @DisplayName("sample_style 只抽示例锚点（契约四）：原因/建议/代码块不进、单行拼接、留 body 不丢")
  void parse_extractsCompactSampleStyleAnchor() {
    String src =
        """
        ---
        name: 审查员
        description: 代码审查专家
        ---
        # 审查员

        ## 📝 审查评论格式
        🔴 **安全：SQL 注入风险**
        第 42 行：用户输入直接拼接。

        **原因：** 攻击者可注入恶意 payload。

        **建议：**
        - 使用参数化查询：`db.query('...')`

        ## 技术交付物
        ### RAG 服务示例
        ```java
        // 一段示例代码
        ```
        """;

    AgencyAgentsParser.ParsedExpert e = new AgencyAgentsParser().parse(src);

    assertTrue(e.sampleStyle().contains("安全：SQL 注入风险"), "示例锚点取首条示例");
    assertTrue(e.sampleStyle().contains("第 42 行：用户输入直接拼接"), "示例锚点含定位行");
    assertFalse(e.sampleStyle().contains("原因"), "原因段不进 sample_style");
    assertFalse(e.sampleStyle().contains("建议"), "建议段不进 sample_style");
    assertFalse(e.sampleStyle().contains("```"), "代码块不进 sample_style");
    assertFalse(e.sampleStyle().contains("\n"), "sample_style 是单行锚点，不是整段格式规范");
    assertTrue(e.body().contains("RAG 服务示例"), "技术交付物示例留 body");
  }

  @Test
  @DisplayName("词尾「别」不是红线：级别/特别/分别留在 values；子句开头的「别」才进 boundaries")
  void parse_doesNotTreatWordSuffixBieAsRedLine() {
    String src =
        """
        ---
        name: 性能基准师
        description: 性能测试专家
        ---
        # 性能基准师

        ## 关键规则
        - 压测数据量必须和生产级别一致，不能用 100 条数据测然后声称"性能没问题"
        - 测试环境别用生产库，特别是有敏感数据的库
        - GPU 资源按需申请，训练完及时释放，别当矿主

        ## 核心使命
        做性能测试。
        """;

    AgencyAgentsParser.ParsedExpert e = new AgencyAgentsParser().parse(src);

    assertTrue(e.keyRules().contains("压测数据量必须和生产级别一致"), "「级别」的词尾别不是红线，留 values");
    assertFalse(e.boundaries().contains("压测数据量必须和生产级别一致"), "词尾「别」不得进 boundaries");
    assertTrue(e.boundaries().contains("不能用 100 条数据测然后声称"), "「不能」子句仍拆进 boundaries");
    assertTrue(e.keyRules().contains("特别是有敏感数据的库"), "「特别」的词尾别不是红线，留 values");
    assertTrue(e.boundaries().contains("别当矿主"), "子句开头的「别」才是红线 → boundaries");
  }

  @Test
  @DisplayName("分句级红线拆分：正向半句留 values、否定子句进 boundaries——「先找问题」不被「不要先跳到方案」连带吞掉")
  void parse_splitsMixedLineClausesWithoutLosingPositiveHalf() {
    String src =
        """
        ---
        name: 架构师
        description: 软件架构专家
        ---
        # 架构师

        ## 关键规则
        1. **先找问题，不要先跳到方案** — 找到底层痛点。
        2. **权衡取舍要透明** — 记录每项决策的理由。

        ## 核心使命
        做架构设计。
        """;

    AgencyAgentsParser.ParsedExpert e = new AgencyAgentsParser().parse(src);

    assertTrue(e.keyRules().contains("先找问题"), "正向半句被拆回 values");
    assertTrue(e.keyRules().contains("找到底层痛点"), "正向说明留 values");
    assertFalse(e.keyRules().contains("不要先跳到方案"), "否定子句不留 values");
    assertTrue(e.boundaries().contains("不要先跳到方案"), "否定子句 → boundaries");
    assertFalse(e.boundaries().contains("先找问题"), "正向半句不进 boundaries");
    assertTrue(e.keyRules().contains("权衡取舍要透明"), "整行正向规则完整留 values");
  }
}
