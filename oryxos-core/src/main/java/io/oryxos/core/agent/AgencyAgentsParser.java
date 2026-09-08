package io.oryxos.core.agent;

import io.oryxos.core.agent.AgentMarkdown.Parsed;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * agency-agents-zh 源文件解析器（025）：一份专家 .md → {@link ParsedExpert}。
 *
 * <p>纯 POJO、零 Spring、无 IO（入参是已读好的字符串）。body 按 {@code ##} 标题关键词分类，镜像 convert.sh 的分段启发式 （specs/025
 * research §6.3）：标题含「身份/记忆/个性」→ 人格层（身份与记忆段内再分角色/个性/背景）；「沟通/风格」→ 语气； 「关键规则/规则/红线」→
 * 行为准则；其余（核心使命/技术交付物/工作流程/成功指标）→ 任务层正文。
 */
public final class AgencyAgentsParser {

  /** 解析产物：人格层字段 + 任务层正文（核心使命/技术交付物/工作流程/成功指标）。 */
  public record ParsedExpert(
      String displayName, // frontmatter name → identity.agent_name / persona.name
      String description, // frontmatter description → description
      String role, // 身份与记忆 · 角色 → persona.role
      String traits, // 身份与记忆 · 个性 → persona.traits
      String background, // 身份与记忆 · 记忆/经验 → identity.prompt（背景知识，非 persona）
      String communication, // 沟通风格 → persona.tone
      String keyRules, // 关键规则（正向行为准则） → persona.values
      String boundaries, // 关键规则里的否定式红线（含「不要/避免/禁止」等） → persona.boundaries
      String sampleStyle, // 明确命中的「评论/回复格式」段 → persona.sample_style（契约四锚点）
      String body) {} // 任务层正文：核心使命 + 技术交付物 + 工作流程 + 成功指标

  /** 人格层标题关键词（镜像 convert.sh 的 SOUL.md 切分）。 */
  private static final List<String> IDENTITY = List.of("身份", "记忆", "个性");

  /** 身份行剥壳用的列表符（- / * 及其带空格变体）与粗体标记。 */
  private static final String BULLET_SPACE_DASH = "- ";

  private static final String BULLET_SPACE_STAR = "* ";
  private static final String BULLET_DASH = "-";
  private static final String BULLET_STAR = "*";
  private static final String BOLD_MARK = "**";

  private static final List<String> COMMUNICATION = List.of("沟通", "风格");
  private static final List<String> RULES = List.of("关键规则", "规则", "红线");

  /** 风格示范（sample_style）标题关键词：只认「评论/回复/话术/开场白」这类说话样板段，不碰技术交付物示例/模板。 */
  private static final List<String> SAMPLE_STYLE = List.of("评论格式", "回复格式", "话术", "开场白", "提问示例");

  /**
   * 否定式红线判据（子句级）：命中即把该子句从 values 拆到 boundaries。多字提示词（不要/绝不能/不在生产环境/不能…）直接 contains； 单字「别」单独走 {@link
   * #isNegationBie}——「级别/特别/分别/识别」里也有「别」，但那是词尾不是否定（025映射表「红线进 boundaries」）。
   */
  private static final List<String> NEGATIVE_HINTS =
      List.of("不要", "永远不要", "绝不", "禁止", "避免", "严禁", "不做", "不可", "不得", "不在", "不能");

  /** 「别」作否定用时的前驱允许字符：子句开头、非汉字（标点/空格/引号/数字/字母）、或强调副词——避免把「级别/特别/分别」当红线。 */
  private static final List<String> BIE_ADVERBS =
      List.of("千", "万", "可", "切", "务", "请", "须", "都", "也", "更", "就");

  private static final String BIE = "别";

  private static final String NEWLINE = "\n";

  public ParsedExpert parse(String markdown) {
    Parsed parsed = AgentMarkdown.split(markdown);
    Map<String, Object> fm = parsed.frontmatter();

    StringBuilder role = new StringBuilder();
    StringBuilder traits = new StringBuilder();
    StringBuilder background = new StringBuilder();
    StringBuilder communication = new StringBuilder();
    StringBuilder keyRules = new StringBuilder();
    StringBuilder boundaries = new StringBuilder();
    StringBuilder sampleStyle = new StringBuilder();
    StringBuilder body = new StringBuilder();

    for (Section s : sections(parsed.body())) {
      if (matches(s.heading(), RULES)) {
        splitRules(s.content(), keyRules, boundaries);
      } else if (matches(s.heading(), COMMUNICATION)) {
        communication.append(s.content());
      } else if (matches(s.heading(), SAMPLE_STYLE)) {
        sampleStyle.append(extractSample(s.content())); // 只抽示例锚点，不整段塞（契约四）
      } else if (matches(s.heading(), IDENTITY)) {
        classifyIdentity(s.content(), role, traits, background);
      } else {
        body.append("## ").append(s.heading()).append('\n').append(s.content());
      }
    }
    return new ParsedExpert(
        str(fm.get("name")),
        str(fm.get("description")),
        role.toString().strip(),
        traits.toString().strip(),
        background.toString().strip(),
        communication.toString().strip(),
        keyRules.toString().strip(),
        boundaries.toString().strip(),
        sampleStyle.toString().strip(),
        body.toString().strip());
  }

  /** 按 {@code ## } 行把 body 切成「标题 → 内容」段；H1 标题 + 一句话定位 intro 在 frontmatter 已有，直接丢弃。 */
  private record Section(String heading, String content) {}

  private static List<Section> sections(String body) {
    List<Section> out = new ArrayList<>();
    String heading = "";
    StringBuilder buf = new StringBuilder();
    for (String line : body.split(NEWLINE, -1)) {
      String t = line.strip();
      if (t.startsWith("## ")) {
        if (!heading.isEmpty()) {
          out.add(new Section(heading, buf.toString()));
        }
        heading = t.substring(3).strip();
        buf.setLength(0);
      } else if (!heading.isEmpty()) {
        buf.append(line).append('\n');
      }
      // 第一个 ## 之前的 H1 标题与「你是**XX**，一句话定位」introduction 行：丢弃（name/description 已从 frontmatter 取）
    }
    if (!heading.isEmpty()) {
      out.add(new Section(heading, buf.toString()));
    }
    return out;
  }

  /** 标题命中任一关键词即归类（contains，非精确匹配——源文件标题措辞不一，如「你的身份与记忆」）。 */
  private static boolean matches(String heading, List<String> keywords) {
    for (String k : keywords) {
      if (heading.contains(k)) {
        return true;
      }
    }
    return false;
  }

  /** 规则子句的分句符：中文逗号/分号/句号/叹号/问号 + 破折号变体（——/—/–）。「、」不切——列表项保持整条。 */
  private static final String CLAUSE_SEPARATORS = "[，；。！？]|——|—|–";

  /**
   * 关键规则段分句拆：先按行，行内再按中文标点/破折号切子句——只把含否定式红线（「不要/避免/禁止/绝不」等）的**子句**进 boundaries，正向子句留
   * values。「先找问题，不要先跳到方案」这类「正向+否定」混行不再整行被红线吞掉，正向半句被拆回 values；分句级判据也避免把「关键规则」整段全塞进 values（025
   * 映射表「红线进 boundaries」的落地）。
   */
  private static void splitRules(String content, StringBuilder keyRules, StringBuilder boundaries) {
    for (String line : content.split(NEWLINE, -1)) {
      String t = line.strip();
      if (t.isEmpty()) {
        continue;
      }
      for (String clause : t.split(CLAUSE_SEPARATORS, -1)) {
        String c = stripClause(clause);
        if (c.isEmpty()) {
          continue;
        }
        if (isNegativeClause(c)) {
          boundaries.append(c).append('\n');
        } else {
          keyRules.append(c).append('\n');
        }
      }
    }
  }

  /** 子句级红线判据：多字提示词 contains；单字「别」须避开词内出现（级别/特别），见 {@link #isNegationBie}。 */
  private static boolean isNegativeClause(String c) {
    if (matches(c, NEGATIVE_HINTS)) {
      return true;
    }
    return isNegationBie(c);
  }

  /**
   * 单字「别」否定判据：只在**子句开头**、或紧跟在**非汉字**（标点/空格/引号/数字/字母）、或强调副词（千/万/可/切/务/请/须/都/也/更/就）之后
   * 才算否定式红线——「别当矿主」「千万别在生产环境改表」命中，「级别/特别/分别/识别」（词尾「别」前是汉字）不算。全量扫所有「别」，
   * 任一处命中即真（「测试环境别用生产库、别连线上」这类句内多个「别」不因第一个是词内而漏判）。
   */
  private static boolean isNegationBie(String c) {
    int i = c.indexOf(BIE);
    while (i >= 0) {
      if (i == 0 || isNegationBiePrecursor(c.charAt(i - 1))) {
        return true;
      }
      i = c.indexOf(BIE, i + 1);
    }
    return false;
  }

  private static boolean isNegationBiePrecursor(char before) {
    if (BIE_ADVERBS.contains(String.valueOf(before))) {
      return true;
    }
    return !Character.isIdeographic(before);
  }

  /**
   * 剥掉规则子句的装饰，只留内容值：粗体、行首序号（1./2/3)）、列表符（-/*）、拆分残留的前导破折号。与 {@link #stripIdentityLine}
   * 不同：**不切冒号**——「第 42 行：」这类前缀是规则内容的一部分，不能丢。
   */
  private static String stripClause(String clause) {
    String t = clause.replace(BOLD_MARK, "");
    if (t.startsWith(BULLET_SPACE_DASH) || t.startsWith(BULLET_SPACE_STAR)) {
      t = t.substring(2);
    } else if (t.startsWith(BULLET_DASH) || t.startsWith(BULLET_STAR)) {
      t = t.substring(1);
    }
    t = t.replaceFirst("^\\d+[.、)]\\s*", ""); // 行首序号：1. / 2、/ 3)
    t = t.replaceFirst("^[—–-]+\\s*", ""); // 拆分残留的前导破折号
    return t.strip();
  }

  /**
   * 风格示范段抽锚点（契约四：1~2 句示例回复，不是整段格式规范）：取首个连续非空块的前 2 行，每行剥装饰（列表符/粗体）， 空格拼接成一行。code-reviewer
   * 的整段「评论格式」（含代码块、多行建议）只留第一条 「🔴 安全：SQL 注入风险 第 42 行：…」——模型每轮拿到的是风格示例，不是一篇格式说明。
   */
  private static String extractSample(String content) {
    StringBuilder out = new StringBuilder();
    int lines = 0;
    for (String line : content.split(NEWLINE, -1)) {
      String t = line.strip();
      if (t.isEmpty()) {
        if (lines > 0) {
          break; // 首个示例块结束（空行分隔）
        }
        continue;
      }
      if (t.startsWith("```")) {
        continue; // 代码围栏行不是示例内容，跳过（示例常整体包在围栏里）
      }
      if (lines >= 2) {
        break; // 最多 2 行
      }
      out.append(stripExampleLine(t)).append(' ');
      lines++;
    }
    return out.toString().strip();
  }

  /** 示例行剥装饰：列表符/粗体；不切冒号（「第 42 行：」前缀是内容）。 */
  private static String stripExampleLine(String line) {
    String t = line.strip();
    if (t.startsWith(BULLET_SPACE_DASH) || t.startsWith(BULLET_SPACE_STAR)) {
      t = t.substring(2).strip();
    } else if (t.startsWith(BULLET_DASH) || t.startsWith(BULLET_STAR)) {
      t = t.substring(1).strip();
    }
    return t.replace(BOLD_MARK, "").strip();
  }

  /** 身份与记忆段内：按行级关键词把 角色/个性 分给人格字段，记忆/经验 归背景知识（不丢未识别行）。 */
  private static void classifyIdentity(
      String content, StringBuilder role, StringBuilder traits, StringBuilder background) {
    for (String line : content.split(NEWLINE, -1)) {
      String t = line.strip();
      if (t.isEmpty()) {
        continue;
      }
      String value = stripIdentityLine(t); // 剥掉「- **标签**：」装饰，只留内容值
      if (t.contains("角色")) {
        role.append(value).append('\n');
      } else if (t.contains("个性") || t.contains("性格")) {
        traits.append(value).append('\n');
      } else {
        background.append(value).append('\n'); // 记忆/经验/未识别行 → 背景知识，绝不丢内容
      }
    }
  }

  /**
   * 剥掉身份行的人为装饰，只留内容：{- ,*} 列表符 → {@code **} 粗体 → 标签与第一个 {@code ：/:} → 剩余值。 源文件措辞不一（「- 角色：xxx」「-
   * **个性**：xxx」），统一成干净值，persona 字段不再带「- 角色：」噪音。
   */
  private static String stripIdentityLine(String line) {
    String t = line.strip();
    if (t.startsWith(BULLET_SPACE_DASH) || t.startsWith(BULLET_SPACE_STAR)) {
      t = t.substring(2).strip();
    } else if (t.startsWith(BULLET_DASH) || t.startsWith(BULLET_STAR)) {
      t = t.substring(1).strip();
    }
    t = t.replace(BOLD_MARK, ""); // 剥粗体标记
    int sep = t.indexOf('：');
    if (sep < 0) {
      sep = t.indexOf(':');
    }
    if (sep >= 0) {
      t = t.substring(sep + 1).strip();
    }
    return t;
  }

  private static String str(Object v) {
    return v == null ? null : String.valueOf(v);
  }
}
