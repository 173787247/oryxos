package io.oryxos.core.channel;

/**
 * 入站媒体附件（图片、文件等），由渠道 normalizer 从平台事件提取。
 *
 * @param type 媒体类型，见 {@link #TYPE_IMAGE} / {@link #TYPE_FILE}
 * @param url 可直接访问的路径或 URL（下载落地后的本地绝对路径，或企微临时 URL）；可能为空
 * @param reference 平台资源标识（飞书 file_key/image_key、钉钉 downloadCode、企微 aeskey 等）
 */
public record InboundAttachment(String type, String url, String reference) {

  public static final String TYPE_IMAGE = "image";
  public static final String TYPE_FILE = "file";

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

  public static InboundAttachment fileUrl(String url) {
    return new InboundAttachment(TYPE_FILE, url, null);
  }

  public static InboundAttachment fileReference(String reference) {
    return new InboundAttachment(TYPE_FILE, null, reference);
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
