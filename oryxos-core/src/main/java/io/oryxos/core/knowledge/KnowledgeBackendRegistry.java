package io.oryxos.core.knowledge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 后端插件按名显式注册表（宪法 III 同款哲学；规避 AgentScope 无插件发现机制之坑，research D9）。
 *
 * <p>内置本地后端以保留名 {@code local} 注册且恒可用；清单未声明 backend 的库落到它。
 */
public final class KnowledgeBackendRegistry {

  /** 内置本地后端的保留注册名。 */
  public static final String LOCAL = "local";

  private final Map<String, KnowledgeBackend> backends = new LinkedHashMap<>();

  public synchronized void register(KnowledgeBackend backend) {
    String name = backend.name();
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("知识后端注册名不能为空");
    }
    if (backends.containsKey(name)) {
      throw new IllegalArgumentException("知识后端重复注册: " + name);
    }
    backends.put(name, backend);
  }

  public synchronized Optional<KnowledgeBackend> byName(String name) {
    return Optional.ofNullable(backends.get(name));
  }

  /** 内置本地后端；未注册说明装配缺陷，直接失败而不是静默降级。 */
  public synchronized KnowledgeBackend localDefault() {
    KnowledgeBackend local = backends.get(LOCAL);
    if (local == null) {
      throw new IllegalStateException("内置本地知识后端未注册（装配缺陷）");
    }
    return local;
  }

  public synchronized List<String> names() {
    return List.copyOf(backends.keySet());
  }
}
