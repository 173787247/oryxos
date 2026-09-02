package io.oryxos.core.channel;

/**
 * 入站媒体附件（图片等），由渠道 normalizer 从平台事件提取。
 *
 * @param type 媒体类型，见 {@link #TYPE_IMAGE}
 * @param url 可直接访问的 URL（企微/钉钉图片）；飞书可能为空
 * @param reference 平台资源标识（如飞书 {@code image_key}），供后续解析
 */
public record InboundAttachment(String type, String url, String reference) {

  public static final String TYPE_IMAGE = "image";

  public InboundAttachment {
    requireNonBlank(type, "type");
    if (isBlank(url)) {
      if (isBlank(reference)) {
        throw new IllegalArgumentException("url 与 reference 至少提供一个");
      }
    }
  }

  public static InboundAttachment imageUrl(String url) {
    return new InboundAttachment(TYPE_IMAGE, url, null);
  }

  public static InboundAttachment imageReference(String reference) {
    return new InboundAttachment(TYPE_IMAGE, null, reference);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static void requireNonBlank(String value, String field) {
    if (isBlank(value)) {
      throw new IllegalArgumentException(field + " 不能为空");
    }
  }
}
