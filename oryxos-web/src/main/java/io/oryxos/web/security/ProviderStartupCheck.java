package io.oryxos.web.security;

import io.oryxos.provider.ProvidersProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * Provider 配置启动校验（012-web-auth fix）。
 *
 * <p>仅在 SERVLET web 模式（serve/gateway）执行——user/chat 等 WebApplicationType.NONE 命令不触发， 确保账号管理等不依赖 LLM
 * 的命令不会因 api-key 未配而被阻断。
 *
 * <p>实现 {@link SmartInitializingSingleton} 而非 {@code ApplicationRunner}：校验在所有单例 Bean 初始化完成后、Web
 * Server 开始监听端口之前执行。配置不合法时 ApplicationContext 启动直接失败， 端口不会打开，避免健康检查在 Provider 校验之前返回 200
 * 的竞态（false-positive startup）。
 *
 * <p>校验逻辑委托给 {@link ProvidersProperties#validate()}。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ProviderStartupCheck implements SmartInitializingSingleton {

  private static final Logger LOG = LoggerFactory.getLogger(ProviderStartupCheck.class);

  private final ProvidersProperties properties;

  public ProviderStartupCheck(ProvidersProperties properties) {
    this.properties = properties;
  }

  @Override
  public void afterSingletonsInstantiated() {
    properties.validate();
    LOG.debug(
        "Provider startup check passed ({} provider(s) configured)", properties.providers().size());
  }
}
