package io.oryxos.web.security;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 登录锁定用的客户端地址：取 TCP 对端，不取 {@code X-Forwarded-For} / {@code Forwarded} 改写后的地址。
 *
 * <p>{@code server.forward-headers-strategy=framework} 会注册 {@code ForwardedHeaderFilter}，把 {@link
 * HttpServletRequest#getRemoteAddr()} 换成客户端声称的转发头。Cookie 的 {@code Secure} 需要 {@code
 * X-Forwarded-Proto}，但锁定键若也信转发头，攻击者每次换一个 {@code X-Forwarded-For} 就能绕过「用户名|IP」失败上限。
 *
 * <p>解开 wrapper 后的对端：直连是攻击者真实 IP；前面是反代时是反代 IP（同用户名在该反代后共用一把锁——文档里「未区分客户端时退化为按用户名锁」的兜底）。
 */
public final class ClientIp {

  private static final String UNKNOWN = "unknown";

  private ClientIp() {}

  /** 供 {@link LoginAttemptService} 拼「用户名|IP」键：不可被请求头伪造。 */
  public static String peerAddress(HttpServletRequest request) {
    ServletRequest current = request;
    while (current instanceof ServletRequestWrapper wrapper) {
      current = wrapper.getRequest();
    }
    String addr =
        current instanceof HttpServletRequest http ? http.getRemoteAddr() : request.getRemoteAddr();
    return addr == null || addr.isBlank() ? UNKNOWN : addr;
  }
}
