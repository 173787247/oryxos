package io.oryxos.core.channel;

import java.util.regex.Pattern;

/**
 * 入站渠道配置条目（.oryxos/channels.yaml 一条 = 一个平台应用 = 绑定一个 Agent，017 Clarify-Q2）。
 *
 * <p>凭证字段（appId/appSecret）在 raw 读法下保留 {@code ${ENV}} 字面量、resolved 读法下为真实值——两套读法 不能混用，见 {@link
 * ChannelConfigLoader}。
 *
 * @param name 渠道名，唯一，[a-zA-Z0-9_-]+
 * @param type 渠道类型（须是已注册的适配器类型）
 * @param appId 平台应用标识（推荐 ${ENV} 占位）
 * @param appSecret 平台应用凭证（必须 ${ENV} 占位，禁明文，FR-012）
 * @param agent 绑定的 Agent 名（.oryxos/agents/ 目录名）
 * @param enabled false = 停用（断开连接但保留配置），缺省 true
 */
public record ChannelConfig(
    String name, String type, String appId, String appSecret, String agent, boolean enabled) {

  private static final Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z0-9_-]+");

  /**
   * 结构校验（name/type/agent 非空、name 字符集）；凭证 resolved 校验与 type/agent 存在性校验发生在装配与 Admin 变更时（需要注册表在场）。
   *
   * @throws IllegalArgumentException 校验失败时点名报错
   */
  public void validateShape() {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("渠道配置缺少 name");
    }
    if (!NAME_PATTERN.matcher(name).matches()) {
      throw new IllegalArgumentException("渠道 " + name + " 的 name 非法（仅允许字母/数字/下划线/连字符）");
    }
    if (type == null || type.isBlank()) {
      throw new IllegalArgumentException("渠道 " + name + " 缺少 type");
    }
    if (agent == null || agent.isBlank()) {
      throw new IllegalArgumentException("渠道 " + name + " 缺少 agent 绑定");
    }
  }

  /**
   * 凭证已解析校验：resolved 读法下值仍含 {@code ${} } 即环境变量未解析（仿 ProvidersProperties 口径，017 R2）。
   *
   * @throws IllegalStateException 凭证缺失或未解析时点名报错
   */
  public void validateCredentialsResolved() {
    requireResolved(appId, "app_id");
    requireResolved(appSecret, "app_secret");
  }

  private void requireResolved(String value, String field) {
    if (value == null || value.isBlank() || value.contains("${")) {
      throw new IllegalStateException("渠道 " + name + " 的 " + field + " 未配置或环境变量未解析，请检查对应环境变量");
    }
  }
}
