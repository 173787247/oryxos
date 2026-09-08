package io.oryxos.core.agent;

import io.oryxos.core.profile.Profile;

/**
 * 一份 {@code AGENT.md} 的 dry-run 校验结果（不落盘、不注册）：valid 时带派生出的 {@link Profile}，invalid 时带可读错误信息。
 *
 * <p>供 import-preview 展示可解析性——校验失败不抛异常（预览永远 200），结果体现在 {@code valid=false} + {@code error} 里；导入落盘仍走
 * {@link AgentLifecycleService#importAgent} 那套会抛的校验链。
 */
public record AgentValidation(Profile profile, String error) {

  /** 校验通过：profile 非空、error 为 null。 */
  public boolean valid() {
    return error == null;
  }

  public static AgentValidation ok(Profile profile) {
    return new AgentValidation(profile, null);
  }

  public static AgentValidation fail(String error) {
    return new AgentValidation(null, error);
  }
}
