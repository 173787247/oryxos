package io.oryxos.core.notify;

import java.util.Map;

/**
 * 通知渠道定义（跨模块值对象，31 节）：一个全局命名的通知出口。
 *
 * <p>{@code name} 全局唯一、Agent 按它引用；{@code type} 决定用哪个 {@code NotifyChannelAdapter}
 * （webhook/feishu/wecom/dingtalk/email）；{@code url} 是 HTTP 类渠道的 webhook 地址；{@code config}
 * 承载该类型所需的额外字段（如 email 的 host/port/from/to/username/password/subject/encryption）。放 core 是因为
 * web（CRUD）、oryxos-tool（notify 工具按名解析）、oryxos-storage（JPA 实现）三边都要认它，而三者都已依赖 core。
 */
public record NotifyChannelDef(
    String name, String type, String url, String description, Map<String, String> config) {

  /** 向后兼容 4 参构造：{@code config} 置空 map（旧调用点免改，HTTP 类渠道用 {@code url} 即可）。 */
  public NotifyChannelDef(String name, String type, String url, String description) {
    this(name, type, url, description, Map.of());
  }

  /** 防御性拷贝：config 是可变 Map，暴露前固化不可变（SpotBugs EI_EXPOSE_REP / EI_EXPOSE_REP2）。 */
  public NotifyChannelDef {
    config = config == null ? Map.of() : Map.copyOf(config);
  }
}
