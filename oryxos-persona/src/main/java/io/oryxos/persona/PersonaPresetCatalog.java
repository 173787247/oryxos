package io.oryxos.persona;

import io.oryxos.core.agent.AgentMarkdown;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * 默认人格预设目录（025「Web 导入」的预置内容种子）：12 个 agency-agents-zh 专家源文件随 jar 内置。
 *
 * <p>选定某个预设 = 照搬源文件原文（含 frontmatter + 正文）作为导入草稿，走与「导入 .md 文件」完全相同的 import-preview → import →
 * saveFiles 链。它仍是 copy-in 内容种子，不是被多个 Agent 按名引用的共享实体—— 不构成「人格市场」。内置 12 个**永远只读、随 jar 升级自动更新**；
 * 用户自建人格的可 CRUD 库（specs/025）由 {@link PersonaService} / {@link PersonaStore} 落地：.{@code
 * oryxos/personas/} 只放自定义、内置不合进工作区； 红线上限不变——按名引用 / 共享人格定义 / 编辑后存回中央库仍越线。
 *
 * <p>出处：<a
 * href="https://github.com/jnMetaCode/agency-agents-zh">jnMetaCode/agency-agents-zh</a>（MIT），
 * 每个预设保留原始相对路径 {@link Preset#sourceFile()} 作署名。
 *
 * <p>纯 POJO、零框架依赖：无参构造时从 classpath 一次性读齐 12 份源文件并投影出元数据；内容读取幂等。
 */
public final class PersonaPresetCatalog {

  /** 预设元数据（不含正文）：管理台卡片展示用；正文经 {@link #sourceContent} 按需读。 */
  public record Preset(
      String key, // 唯一 slug（= classpath 资源名 personas/<key>.md，也是导入时的建议 Agent 名）
      String label, // 管理台展示名（025 §12 的 12 个默认选项）
      String description, // 源文件 frontmatter description（卡片副标题）
      String emoji, // 源文件 frontmatter emoji（卡片图标）
      String sourceFile) {} // agency-agents-zh 原始相对路径（署名，不参与装载）

  private record Spec(String key, String label, String sourceFile) {}

  private static final String RESOURCE_ROOT = "personas/";

  /** 12 个默认预设（025评审 §12 决策表）：label 为管理台展示名，sourceFile 为 agency-agents-zh 原始相对路径。 */
  private static final List<Spec> SPECS =
      List.of(
          new Spec("senior-developer", "软件开发工程师", "engineering/engineering-senior-developer.md"),
          new Spec("embedded-qa-engineer", "测试工程师", "testing/testing-embedded-qa-engineer.md"),
          new Spec("product-manager", "产品经理", "product/product-manager.md"),
          new Spec("frontend-developer", "前端开发者", "engineering/engineering-frontend-developer.md"),
          new Spec("backend-architect", "后端架构师", "engineering/engineering-backend-architect.md"),
          new Spec("code-reviewer", "代码审查员", "engineering/engineering-code-reviewer.md"),
          new Spec(
              "devops-automator", "DevOps 自动化工程师", "engineering/engineering-devops-automator.md"),
          new Spec("ai-engineer", "AI 工程师", "engineering/engineering-ai-engineer.md"),
          new Spec("ui-designer", "UI 设计师", "design/design-ui-designer.md"),
          new Spec(
              "performance-benchmarker", "性能基准测试师", "testing/testing-performance-benchmarker.md"),
          new Spec("api-tester", "Bug 猎人", "testing/testing-api-tester.md"),
          new Spec("support-responder", "技术支持工程师", "support/support-support-responder.md"));

  private final List<Preset> presets;

  public PersonaPresetCatalog() {
    this.presets = SPECS.stream().map(this::loadMeta).toList();
  }

  public List<Preset> all() {
    return List.copyOf(presets);
  }

  public Optional<Preset> get(String key) {
    return presets.stream().filter(p -> p.key().equals(key)).findFirst();
  }

  /** 源文件全文（含 frontmatter + 正文），喂给 {@link AgencyAgentsParser} / import-preview。 */
  public String sourceContent(Preset preset) {
    return readResource(RESOURCE_ROOT + preset.key() + ".md");
  }

  /** 元数据投影：description/emoji 从源文件 frontmatter 读，label 用025决策表的展示名。 */
  private Preset loadMeta(Spec s) {
    AgentMarkdown.Parsed fm = AgentMarkdown.split(readResource(RESOURCE_ROOT + s.key() + ".md"));
    String description = str(fm.frontmatter().get("description"));
    return new Preset(
        s.key(),
        s.label(),
        description == null || description.isBlank() ? s.label() : description,
        str(fm.frontmatter().get("emoji")),
        s.sourceFile());
  }

  private static String readResource(String name) {
    try (InputStream in = PersonaPresetCatalog.class.getClassLoader().getResourceAsStream(name)) {
      if (in == null) {
        throw new IllegalStateException("缺内置人格预设资源: " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("读取内置人格预设失败: " + name, e);
    }
  }

  private static String str(Object v) {
    return v == null ? null : String.valueOf(v);
  }
}
