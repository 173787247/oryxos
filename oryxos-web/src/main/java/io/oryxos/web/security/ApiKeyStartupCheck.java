package io.oryxos.web.security;

import io.oryxos.storage.ApiKeyService;
import io.oryxos.web.config.WebApiKeyProperties;
import io.oryxos.web.config.WebAuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * 启动校验（018-rest-api-key，FR-012）：两种异常 flag 组合只 WARN 不阻断。
 *
 * <ol>
 *   <li>{@code apikey.enabled=true} 但库中无有效 Key——非豁免请求将全部 401，提示先跑 {@code oryxos apikey add}；
 *   <li>{@code apikey.enabled=true} 但 {@code auth.enabled=false}——浏览器既无 session 也无 Key，管理台数据页
 *       将不可用，建议同时开启管理台认证（Clarifications Q2）。
 * </ol>
 *
 * <p>与 012 {@code AuthStartupCheck} 的 fail-fast 差异是有意的（research R7）：012 无账号开 auth 等于永久锁死 管理台（serve
 * 起不来就建不了账号的死锁不存在，但登录永远不可能成功）；018 无 Key 开门禁是「全拒」的安全状态而非 坏状态，且 {@code oryxos apikey add} 用 {@code
 * WebApplicationType.NONE} 起 Spring、不依赖 serve 存活， 告警即可。纯机器调用部署（不用管理台）是合法场景，故组合 ② 也不阻断。
 *
 * <p>{@link ConditionalOnWebApplication} 限定只 SERVLET 模式（serve/gateway）装配，CLI 管理命令不受影响 （镜像
 * AuthStartupCheck）。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ApiKeyStartupCheck implements ApplicationRunner {

  private static final Logger LOG = LoggerFactory.getLogger(ApiKeyStartupCheck.class);

  private final WebApiKeyProperties apiKeyProperties;
  private final WebAuthProperties authProperties;
  private final ApiKeyService apiKeyService;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "apiKeyProperties/authProperties/apiKeyService 均为 Spring 注入的共享单例，构造注入存同一引用正是意图"
              + "（镜像 AuthStartupCheck 的 SuppressFBWarnings 模式）。")
  public ApiKeyStartupCheck(
      WebApiKeyProperties apiKeyProperties,
      WebAuthProperties authProperties,
      ApiKeyService apiKeyService) {
    this.apiKeyProperties = apiKeyProperties;
    this.authProperties = authProperties;
    this.apiKeyService = apiKeyService;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!apiKeyProperties.isEnabled()) {
      LOG.debug("Api key auth disabled (oryxos.web.apikey.enabled=false), startup check skipped");
      return;
    }
    if (!apiKeyService.hasActiveKey()) {
      LOG.warn(
          "Api key auth enabled (oryxos.web.apikey.enabled=true) but no active key found. "
              + "All non-exempt /api/v1/** requests will be rejected (401). "
              + "Run 'oryxos apikey add <name>' to create one.");
    }
    if (!authProperties.isEnabled()) {
      LOG.warn(
          "Api key auth enabled but web auth disabled (oryxos.web.auth.enabled=false). "
              + "Admin console data pages will be unusable (browser has neither session nor key). "
              + "Enable oryxos.web.auth.enabled or use REST API only.");
    }
  }
}
