package io.oryxos.core.channel;

/**
 * 渠道出站校验守卫：渠道适配器发送回复前必须经它校验目标 URL（017 R7，宪法 VI）。
 *
 * <p>沙箱实现在 oryxos-tool（{@code WhitelistSandbox}），本接口按依赖倒置放 core——装配层用 {@code url ->
 * sandbox.enforce(new SandboxAction(HTTP_REQUEST, url))} 适配。渠道模块自建的 HTTP 出站不会被沙箱自动拦截，
 * 必须显式过本守卫，否则「复用沙箱域名白名单」的承诺落空。
 */
@FunctionalInterface
public interface OutboundGuard {

  /**
   * 校验一次出站请求目标；不通过时抛出运行时异常（沿用沙箱的引导文案，提示在管理台把域名加入 http 白名单）。
   *
   * @param url 出站目标 URL
   */
  void check(String url);
}
