package io.oryxos.persona;

import io.oryxos.core.agent.AgentMarkdown;
import io.oryxos.persona.PersonaPresetCatalog.Preset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 人格库编排（025阶段二落地）：只读内置（classpath，随 jar 升级自动更新）+ 可 CRUD 自定义（{@code .oryxos/personas/}，
 * 跨版本持久）。两者合并成一个列表对外展示，**存储分开**——内置 12 个永远不合进工作区（版本升级自动携带），工作区只放用户自建。
 *
 * <p>copy-in 模板库：选中某个人格 = 照搬源文件原文走 import-preview → import → saveFiles 链，不是被多个 Agent 按名引用的共享实体
 * （仍非「人格市场」——按名引用 / 人格共享是下一阶段红线）。删除 = 物理删除（persona 无反向引用）。
 *
 * <p>与 {@code AgentLifecycleService} / {@code SkillService} 同构：{@link #get} 返回 {@link Optional}，404
 * 由 web 层决定； 非法入参抛 {@link IllegalArgumentException}（web 映射 400）。内置人格一律拒绝 CRUD（只读）。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "catalog/store 均为 Spring 注入的共享单例，构造注入共享同一引用正是意图（无法也不应防御性拷贝）。")
public class PersonaService {

  private final PersonaPresetCatalog builtins;
  private final PersonaStore store;

  public PersonaService(PersonaPresetCatalog builtins, PersonaStore store) {
    this.builtins = builtins;
    this.store = store;
  }

  /** 统一入口记录：内置/自定义都能投影成卡片元数据；{@code builtin=true} 表示 classpath 只读、不可 CRUD。 */
  public record PersonaEntry(
      String key,
      String label, // 展示名：内置用025决策表 label，自定义取源 frontmatter name（缺省 key）
      String description,
      String emoji,
      String sourceFile, // 内置：agency-agents-zh 原始相对路径（署名）；自定义：null
      boolean builtin) {}

  /** 合并列表：内置（保持 preset 顺序）+ 自定义（字典序）追加；同名冲突时自定义优先（创建时已挡同名，此处兜底）。 */
  public List<PersonaEntry> list() {
    Map<String, PersonaEntry> merged = new LinkedHashMap<>();
    for (Preset p : builtins.all()) {
      merged.put(p.key(), entryOf(p));
    }
    for (String key : store.list()) {
      merged.put(key, customEntry(key));
    }
    return List.copyOf(merged.values());
  }

  /** 单个人格是否存在（内置或自定义）。 */
  public Optional<PersonaEntry> get(String key) {
    return list().stream().filter(e -> e.key().equals(key)).findFirst();
  }

  /** 源文件全文：自定义优先（用户可改），回落到内置源文件原文。未知 key → 空。 */
  public Optional<String> source(String key) {
    if (store.exists(key)) {
      return Optional.of(store.read(key));
    }
    return builtins.get(key).map(builtins::sourceContent);
  }

  /** 新建自定义：key 校验 + 与内置/已有自定义同名冲突拒绝 → 写盘 + 投影 meta。 */
  public PersonaEntry create(String key, String content) {
    requireContent(content);
    String k = key == null ? "" : key.strip();
    PersonaStore.safe(k); // 非法 key 先抛（web 映射 400）
    if (builtins.get(k).isPresent()) {
      throw new IllegalArgumentException("内置人格只读，不能新建同名自定义: " + k);
    }
    if (store.entryExists(k)) {
      throw new IllegalArgumentException("自定义人格已存在: " + k);
    }
    store.write(k, content);
    return customEntry(k);
  }

  /** 更新自定义：仅自定义；内置 key → 拒绝（只读）。 */
  public PersonaEntry update(String key, String content) {
    requireContent(content);
    if (builtins.get(key).isPresent()) {
      throw new IllegalArgumentException("内置人格只读，不能修改: " + key);
    }
    if (!store.exists(key)) {
      throw new IllegalArgumentException("自定义人格不存在: " + key);
    }
    store.write(key, content);
    return customEntry(key);
  }

  /** 删除自定义：仅自定义；内置 key → 拒绝（只读）。物理删除（copy-in 库无反向引用）。 */
  public void delete(String key) {
    if (builtins.get(key).isPresent()) {
      throw new IllegalArgumentException("内置人格只读，不能删除: " + key);
    }
    if (!store.exists(key)) {
      throw new IllegalArgumentException("自定义人格不存在: " + key);
    }
    store.delete(key);
  }

  private static void requireContent(String content) {
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("人格内容为空");
    }
  }

  private static PersonaEntry entryOf(Preset p) {
    return new PersonaEntry(p.key(), p.label(), p.description(), p.emoji(), p.sourceFile(), true);
  }

  /** 自定义人格的 meta 投影：源 frontmatter 读 name/description/emoji（label 缺省用 key），sourceFile 仅内置有。 */
  private PersonaEntry customEntry(String key) {
    AgentMarkdown.Parsed fm = AgentMarkdown.split(store.read(key));
    String name = str(fm.frontmatter().get("name"));
    return new PersonaEntry(
        key,
        name == null || name.isBlank() ? key : name,
        str(fm.frontmatter().get("description")),
        str(fm.frontmatter().get("emoji")),
        null,
        false);
  }

  private static String str(Object v) {
    return v == null ? null : String.valueOf(v);
  }
}
