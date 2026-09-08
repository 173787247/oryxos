package io.oryxos.core.channel;

/**
 * 可选入站 HTTP Webhook 面（026 P0）。未实现的渠道对 {@code POST/GET /api/v1/channels/inbound/{name}} 返回 404。
 *
 * <p>验签、挑战握手、事件拆包由实现负责；归一化后的消息由实现调用 {@link InboundMessageService}，与长连接渠道同一编排入口。
 */
public interface InboundWebhookHandler {

  WebhookResponse onWebhook(WebhookRequest request);
}
