package io.oryxos.core.channel;

/** 入站 webhook HTTP 响应（挑战握手 / 确认回执）。 */
public record WebhookResponse(int status, String contentType, String body) {

  public static WebhookResponse ok() {
    return new WebhookResponse(200, "text/plain;charset=UTF-8", "ok");
  }

  public static WebhookResponse text(int status, String body) {
    return new WebhookResponse(status, "text/plain;charset=UTF-8", body == null ? "" : body);
  }

  public static WebhookResponse json(int status, String body) {
    return new WebhookResponse(status, "application/json;charset=UTF-8", body == null ? "" : body);
  }
}
