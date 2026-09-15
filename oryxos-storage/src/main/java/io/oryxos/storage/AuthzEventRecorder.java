package io.oryxos.storage;

import io.oryxos.core.auth.Principal;
import io.oryxos.core.policy.Action;
import io.oryxos.core.policy.ResourceRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * 授权拒绝落库（039）：任何异常只记 ERROR 不抛出——写失败绝不能把拒绝变成放行。
 *
 * <p>API Key 只记名称（{@link Principal#id()}），绝不含明文。方法/路径由调用方（web 层）传入，本模块不依赖 servlet API。
 */
public class AuthzEventRecorder {

  private static final Logger LOG = LoggerFactory.getLogger(AuthzEventRecorder.class);

  private static final int MAX_REASON = 512;
  private static final int MAX_PATH = 512;
  private static final int MAX_ID = 128;
  private static final String TRACE_MDC_KEY = "traceId";

  private final AuthzEventRepository repository;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "repository 为 Spring 注入共享单例，存同一引用正是意图。")
  public AuthzEventRecorder(AuthzEventRepository repository) {
    this.repository = repository;
  }

  /** 记录一次拒绝；失败吞掉并记 ERROR。 */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification =
          "exception toString for diagnostics only; write failure must not change deny"
              + " decision; no request body in log.")
  public void record(
      Principal principal,
      Action action,
      ResourceRef resource,
      String reason,
      String requestMethod,
      String requestPath) {
    try {
      AuthzEvent event = new AuthzEvent();
      Principal subject = principal == null ? Principal.anonymous() : principal;
      event.setPrincipalKind(subject.kind().name());
      event.setPrincipalId(truncate(subject.id(), MAX_ID));
      event.setAction(action == null ? "UNKNOWN" : action.name());
      if (resource != null) {
        event.setResourceType(resource.type());
        event.setResourceId(resource.id());
      }
      event.setReason(truncate(reason == null ? "未说明理由" : reason, MAX_REASON));
      event.setRequestMethod(requestMethod);
      event.setRequestPath(truncate(requestPath, MAX_PATH));
      String trace = MDC.get(TRACE_MDC_KEY);
      if (trace != null && !trace.isBlank()) {
        event.setTraceId(truncate(trace, 64));
      }
      repository.save(event);
    } catch (RuntimeException ex) {
      LOG.error("authz_events 写入失败（拒绝裁决不回滚）：{}", ex.toString());
    }
  }

  private static String truncate(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }
}
