package io.oryxos.channel.weixin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMediaJanitor;
import java.net.URI;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 钉住入站媒体的 host 判据 —— 它是这张表里唯一的安全边界。
 *
 * <p>这个类的其它职责（AES 解密、落盘、配额、重试降级）都有 {@code core} 侧的共享实现兜底， 而 {@link
 * WeixinInboundMediaResolver#isAllowedMediaUri} 是它**自己**的判据：下游带 Bot 凭证的 GET
 * 只允许打到微信自家的媒体域。判据写宽一格（例如把后缀写成不带点、或把 {@code contains("wx")} 写成永远为真），就会变成"拿着我方凭证去任何地方取文件"。
 *
 * <p>本测试**只调判据，不发起任何下载** —— 因此 {@code mediaRoot} 指向一个不存在的路径、 {@code OutboundGuard} 用 no-op，都不会被触达。
 */
class WeixinInboundMediaResolverTest {

  /** 不会被写入：本测试不走 {@code resolve()}，只调 {@code isAllowedMediaUri}。 */
  private static final Path UNUSED_MEDIA_ROOT = Path.of("/nonexistent-oryxos-weixin-test");

  private static WeixinInboundMediaResolver resolver() {
    return new WeixinInboundMediaResolver(
        url -> {}, UNUSED_MEDIA_ROOT, "ops-weixin", new InboundMediaJanitor());
  }

  private static boolean allowed(String url) {
    return resolver().isAllowedMediaUri(URI.create(url));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://novac2c.cdn.weixin.qq.com/cgi-bin/media/get?media_id=x", // 逐字命中 HOST_CDN
        "https://ilinkai.weixin.qq.com/x", // 逐字命中 HOST_ILINK
        "https://a.weixin.qq.com/x", // 命中 .weixin.qq.com 后缀
        "https://wx.qlogo.cn/x", // 逐字命中 HOST_QLOGO
        "https://thirdwx.qlogo.cn/x", // 逐字命中 HOST_THIRD_QLOGO
        "https://res.wx.qq.com/x", // 逐字命中 HOST_RES
        "https://mmbiz.qpic.cn/x", // 逐字命中 HOST_MMBIZ_QPIC
        "https://mmbiz.qlogo.cn/x", // 逐字命中 HOST_MMBIZ_QLOGO
        "https://wx.other.qq.com/x", // 命中「.qq.com 且含 wx」
        // 判据是**子串**包含，不是前缀 —— 所以下面这个也过。写下来免得后人误以为
        // 要求"wx 开头的子域"，从而把判据改成更窄（或更宽）的形状：
        "https://notwx.qq.com/x",
      })
  @DisplayName("白名单内的媒体地址被接受")
  void acceptsAllowlistedHosts(String url) {
    assertTrue(allowed(url), "应接受: " + url);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://wx.qlogo.cn/x", // 非 https
        "ftp://novac2c.cdn.weixin.qq.com/x", // 非 https
        "https://evil.example.com/x", // 域外
        "https://wx.qlogo.cn.evil.example.com/x", // 后缀伪造：真域名只是前缀
        "https://evil-weixin.qq.com/x", // 形似，但不以 .weixin.qq.com 结尾
        "https://noqq.com/x", // 主机名既不含 wx 也不含 .qq.com
        "https://wxqq.com/x", // 含 wx，但不含 .qq.com 后缀
      })
  @DisplayName("白名单外的地址被拒绝")
  void rejectsForeignHosts(String url) {
    assertFalse(allowed(url), "应拒绝: " + url);
  }

  @Test
  @DisplayName("主机名匹配大小写不敏感")
  void hostMatchingIsCaseInsensitive() {
    assertTrue(allowed("https://WX.QLOGO.CN/x"));
    assertTrue(allowed("https://NOVAC2C.CDN.WEIXIN.QQ.COM/x"));
    assertTrue(allowed("https://A.WEIXIN.QQ.COM/x"));
  }

  @Test
  @DisplayName("scheme 必须是 https —— http 一律拒绝（不因域名对而放行）")
  void schemeMustBeHttps() {
    assertFalse(allowed("http://novac2c.cdn.weixin.qq.com/x"));
    assertFalse(allowed("http://ilinkai.weixin.qq.com/x"));
  }

  @Test
  @DisplayName("needsDownload：四种可下载类型 + 非空 url 才算")
  void needsDownloadCoversExactlyFourTypes() {
    for (String type :
        new String[] {
          InboundAttachment.TYPE_IMAGE,
          InboundAttachment.TYPE_FILE,
          InboundAttachment.TYPE_AUDIO,
          InboundAttachment.TYPE_VIDEO
        }) {
      assertTrue(
          WeixinInboundMediaResolver.needsDownload(
              new InboundAttachment(type, "https://wx.qlogo.cn/x", null, null)),
          "类型 " + type + " 应视为可下载");
    }
  }

  @Test
  @DisplayName("needsDownload：未知类型、空 url、null 条目都不下载")
  void needsDownloadRejectsTheRest() {
    assertFalse(
        WeixinInboundMediaResolver.needsDownload(
            new InboundAttachment("sticker", "https://wx.qlogo.cn/x", null, null)),
        "未知类型不应触发下载");
    // 注意构造器要求 url 与 reference 至少一个非空，所以空白 url 必须配一个 reference。
    assertFalse(
        WeixinInboundMediaResolver.needsDownload(
            new InboundAttachment(InboundAttachment.TYPE_IMAGE, "   ", "ref1", null)),
        "空白 url 不应触发下载");
    assertFalse(
        WeixinInboundMediaResolver.needsDownload(
            new InboundAttachment(InboundAttachment.TYPE_IMAGE, null, "ref-only", null)),
        "只有 reference 没有 url 时不应触发下载");
    assertFalse(WeixinInboundMediaResolver.needsDownload(null), "null 条目不应触发下载");
  }
}
