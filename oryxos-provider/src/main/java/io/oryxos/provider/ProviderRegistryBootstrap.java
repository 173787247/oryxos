package io.oryxos.provider;

import io.oryxos.core.provider.ProviderDef;
import io.oryxos.core.provider.ProviderRegistry;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ProviderRegistryBootstrap {

  private static final Logger LOG = LoggerFactory.getLogger(ProviderRegistryBootstrap.class);

  private final ProviderRegistryValidator validator;

  public ProviderRegistryBootstrap(ProviderRegistryValidator validator) {
    this.validator = validator;
  }

  public void seedMissing(ProviderRegistry registry, ProvidersProperties properties) {
    for (ProvidersProperties.ProviderConfig config : properties.providers()) {
      String name = config.name();
      if (name != null && !name.isBlank() && registry.exists(name)) {
        continue;
      }
      ProviderDef candidate =
          new ProviderDef(config.name(), config.apiKey(), config.baseUrl(), null);
      Optional<String> violation = validator.violation(candidate);
      if (violation.isPresent()) {
        LOG.warn(
            "跳过 provider {} 的启动播种: {}",
            sanitizeLogValue(config.name()),
            sanitizeLogValue(violation.get()));
        continue;
      }
      saveUnlessAnotherReplicaSeeded(registry, config.name(), candidate);
    }
  }

  /**
   * 播种写入在一个集群里不是本进程独占的：两个副本同时启动时都会读到「缺失」、然后都插，后到的那次撞主键 （{@code providers_pkey}），表现是整个副本起不来（{@code
   * ConcurrentMigrationIT} 的两副本并发启动）。
   *
   * <p>冲突后回查一次：对方确实插进去了就按「已播种」收；回查不到（或回查自己也失败）就把原异常上抛 —— 要接住的是"同一条已被别人写过"，不是吞掉写入错误。
   */
  private static void saveUnlessAnotherReplicaSeeded(
      ProviderRegistry registry, String name, ProviderDef candidate) {
    try {
      registry.save(candidate);
    } catch (RuntimeException conflict) {
      if (seededByAnotherReplica(registry, name)) {
        LOG.info("provider {} 已由另一副本播种，本次写入跳过", sanitizeLogValue(name));
        return;
      }
      throw conflict;
    }
  }

  private static boolean seededByAnotherReplica(ProviderRegistry registry, String name) {
    if (name == null || name.isBlank()) {
      return false;
    }
    try {
      return registry.exists(name);
    } catch (RuntimeException recheckFailure) {
      return false;
    }
  }

  private static String sanitizeLogValue(String value) {
    return value == null ? "<unknown>" : value.replace('\r', '_').replace('\n', '_');
  }
}
