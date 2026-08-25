package io.oryxos.core.channel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行中入站渠道注册表：渠道名 → 适配器实例；另记录未能上线渠道的 ERROR 状态（点名原因可查，SC-008）。
 *
 * <p>{@link #statusAll()} 基于注册表实时计算，不 snapshot——管理台增删渠道必须立刻反映到状态端点 （#203 活视图教训，参照
 * ToolRegistry.asMap）。
 */
public class InboundChannelRegistry {

  private final Map<String, InboundChannelAdapter> adapters = new ConcurrentHashMap<>();
  // 校验/启动失败而未上线的渠道：name → ERROR 状态（配置仍在，状态可见，不带病上线）
  private final Map<String, ChannelStatus> failed = new ConcurrentHashMap<>();

  /** 登记一个已启动的适配器；同名 ERROR 记录清除。 */
  public void register(InboundChannelAdapter adapter) {
    failed.remove(adapter.name());
    adapters.put(adapter.name(), adapter);
  }

  /** 登记一个未能上线的渠道及其点名原因。 */
  public void registerFailure(ChannelStatus errorStatus) {
    adapters.remove(errorStatus.name());
    failed.put(errorStatus.name(), errorStatus);
  }

  /** 移除一个渠道的登记（运行中或失败态皆可）。 */
  public void unregister(String name) {
    adapters.remove(name);
    failed.remove(name);
  }

  public Optional<InboundChannelAdapter> get(String name) {
    return Optional.ofNullable(adapters.get(name));
  }

  /** 全部渠道实时状态：运行中的问适配器，未上线的返回登记的 ERROR。 */
  public List<ChannelStatus> statusAll() {
    List<ChannelStatus> out = new ArrayList<>();
    for (InboundChannelAdapter adapter : adapters.values()) {
      out.add(adapter.status());
    }
    out.addAll(failed.values());
    out.sort(java.util.Comparator.comparing(ChannelStatus::name));
    return out;
  }
}
