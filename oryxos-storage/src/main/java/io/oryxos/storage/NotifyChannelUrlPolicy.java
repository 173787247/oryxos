package io.oryxos.storage;

/**
 * {@code notify_channels.url} 的落库加密范围（022 范围补齐）：url 是否属于凭证由渠道 type 决定。
 *
 * <p>除 email 外，内置渠道（webhook/feishu/wecom/dingtalk/slack/discord/telegram/…）的 url 都是 HTTP 端点，
 * 拿到即可推送，属于凭证； email 的 url 只是描述性/占位字段（{@code EmailNotifyAdapter} 只读 config 的 host/port/from/to 等，
 * 从不读 url），加密它只会让存量占位值 变得不可读。未知或空 type 按凭证处理——fail-safe：宁可多加密，不漏。
 */
final class NotifyChannelUrlPolicy {

  private static final String EMAIL = "email";

  private NotifyChannelUrlPolicy() {}

  /**
   * true = 该 type 的 url 必须加密落库。只认规范小写的 {@code email}（渠道 type 由管理台白名单校验/内置 adapter
   * 键名写入，均为小写）；其余（含未知、null）一律按凭证处理——fail-safe 宁多加密，不漏。
   */
  static boolean isCredentialUrl(String type) {
    return !EMAIL.equals(type);
  }
}
