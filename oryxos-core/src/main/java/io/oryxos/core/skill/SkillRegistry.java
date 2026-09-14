package io.oryxos.core.skill;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skill 内存索引，按 name 查找（第 32 节）。
 *
 * <p>与 {@code ProfileRegistry} 同构：启动扫描与运行时 CRUD 走同一份注册表。{@link #register}/{@link #remove} 供 {@code
 * SkillService} 在增删改后即时同步内存视图（{@code ContextLoader} 按名解析时立即可见）。
 */
public class SkillRegistry {

  private final Map<String, Skill> skills = new ConcurrentHashMap<>();

  public SkillRegistry() {}

  public SkillRegistry(Map<String, Skill> initial) {
    if (initial != null) {
      skills.putAll(initial);
    }
  }

  public Optional<Skill> get(String name) {
    return Optional.ofNullable(skills.get(name));
  }

  /** 快照拷贝，不暴露内部可变集合。 */
  public Collection<Skill> all() {
    return List.copyOf(skills.values());
  }

  /** 运行时登记（CRUD 与启动扫描共用）；同名覆盖。 */
  public void register(Skill skill) {
    skills.put(skill.name(), skill);
  }

  /** 运行时注销；存在并移除返回 true。 */
  public boolean remove(String name) {
    return skills.remove(name) != null;
  }

  /**
   * 027：全量替换为一份新扫描结果（集群档重载用）——先增改后删失，读方（ContextLoader 按名解析） 任一时刻见到的都是完整条目；在途轮次持有的 Skill
   * 对象本身不可变，不受替换影响。
   */
  public void replaceAll(Collection<Skill> fresh) {
    Map<String, Skill> next = new ConcurrentHashMap<>(Math.max(16, fresh.size() * 2));
    for (Skill skill : fresh) {
      next.put(skill.name(), skill);
    }
    skills.putAll(next);
    skills.keySet().retainAll(next.keySet());
  }

  public boolean exists(String name) {
    return skills.containsKey(name);
  }
}
