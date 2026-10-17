package io.oryxos.channel.feishu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.GetMessageResourceResp;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMediaJanitor;
import io.oryxos.core.channel.InboundMessage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * 入站媒体清理的接线守卫（026）：janitor 不是装配层的定时任务，而是每个渠道 resolver 在 resolve 入口清扫 TTL、 在写盘之前检查配额。这里用真实的 {@link
 * InboundMediaJanitor} 走真实文件系统——被守护的就是它对落盘的影响，mock 掉它这条链就断了。
 */
class FeishuInboundMediaJanitorWiringTest {

  private static final Duration TTL = Duration.ofHours(24);

  @TempDir Path mediaRoot;

  @Test
  @DisplayName("TTL：resolve 入口触发 sweep——过期消息目录被删，新鲜的不动")
  void resolveSweepsExpiredMessageDirectories() throws Exception {
    Path expired = messageDir("om_expired", "old.bin", Duration.ofHours(48));
    Path fresh = messageDir("om_fresh", "new.bin", Duration.ZERO);
    // 配额关闭（maxBytes=0），只剩 TTL 这一条判据，失败时能指认出是 sweep 没被调用
    FeishuInboundImageResolver resolver =
        new FeishuInboundImageResolver(
            mock(Client.class), mediaRoot, "ops-feishu", new InboundMediaJanitor(TTL, 0L));

    resolver.resolve(message("om_ttl", List.of()));

    assertFalse(Files.exists(expired), "过期目录应被 TTL 清理");
    assertTrue(Files.isRegularFile(fresh.resolve("new.bin")), "新鲜目录不得被删");
  }

  @Test
  @DisplayName("超配额：写盘前被拒且不静默——WARN、保留平台引用、目录里没有新文件")
  void quotaExceededKeepsReferenceAndWarns() throws Exception {
    // sweep 只清「根下一级的 message 目录」，根级文件腾不出来：它让 sizeOf 超配额，
    // 于是 ensureQuotaOrThrow 在写盘前真的抛错（而不是被一次清扫把空间腾出来）。
    Files.write(mediaRoot.resolve("over-quota.bin"), new byte[4096]);
    FeishuInboundImageResolver resolver =
        new FeishuInboundImageResolver(
            stubClient("shot.png"), mediaRoot, "ops-feishu", new InboundMediaJanitor(TTL, 1024L));
    Logger logger = (Logger) LoggerFactory.getLogger(FeishuInboundImageResolver.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      InboundMessage out =
          resolver.resolve(
              message("om_quota", List.of(InboundAttachment.imageReference("img_quota"))));

      InboundAttachment att = out.attachments().get(0);
      assertEquals("img_quota", att.reference(), "超配额时必须保留平台引用");
      assertTrue(att.url() == null || att.url().isBlank(), "超配额时不得把路径当成本地文件: " + att.url());
      assertEquals(0L, countRegularFiles(mediaRoot.resolve("om_quota")), "超配额时不得落盘");
      assertTrue(
          appender.list.stream()
              .anyMatch(
                  e ->
                      e.getLevel() == Level.WARN
                          && e.getFormattedMessage().contains("保留原引用")
                          && e.getFormattedMessage().contains("超过配额")),
          () ->
              "超配额必须留下可观测的 WARN，实际日志: "
                  + appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  @DisplayName("正常配额：附件真的落盘到注入的媒体根，url 非空")
  void writesToMediaRootUnderQuota() throws Exception {
    FeishuInboundImageResolver resolver =
        new FeishuInboundImageResolver(
            stubClient("shot.png"),
            mediaRoot,
            "ops-feishu",
            new InboundMediaJanitor(TTL, 10L * 1024 * 1024));

    InboundMessage out =
        resolver.resolve(message("om_ok", List.of(InboundAttachment.imageReference("img_ok"))));

    InboundAttachment att = out.attachments().get(0);
    assertTrue(att.url() != null && !att.url().isBlank(), "正常配额下必须落盘");
    Path written = Path.of(att.url()).toAbsolutePath().normalize();
    assertTrue(Files.isRegularFile(written), written.toString());
    assertTrue(
        written.startsWith(mediaRoot.toAbsolutePath().normalize()), "落盘必须在注入的媒体根内: " + written);
    assertEquals("PNGDATA", Files.readString(written));
  }

  private static InboundMessage message(String messageId, List<InboundAttachment> attachments) {
    return new InboundMessage(
        "feishu",
        "ops-feishu",
        messageId,
        ChatKind.P2P,
        "ou_user",
        "oc_chat",
        "",
        false,
        false,
        attachments);
  }

  /** 一个 {@code mediaRoot/<messageId>/payload} 消息目录，年龄为 {@code age}。 */
  private Path messageDir(String messageId, String fileName, Duration age) throws Exception {
    Path dir = Files.createDirectories(mediaRoot.resolve(messageId));
    Path file = dir.resolve(fileName);
    Files.writeString(file, "payload");
    if (!age.isZero()) {
      FileTime stamp = FileTime.from(Instant.now().minus(age));
      Files.setLastModifiedTime(file, stamp);
      Files.setLastModifiedTime(dir, stamp);
    }
    return dir;
  }

  private static Client stubClient(String fileName) throws Exception {
    Client client = mock(Client.class, RETURNS_DEEP_STUBS);
    GetMessageResourceResp resp = new GetMessageResourceResp();
    resp.setCode(0);
    resp.setMsg("ok");
    resp.setFileName(fileName);
    ByteArrayOutputStream data = new ByteArrayOutputStream();
    data.write("PNGDATA".getBytes(StandardCharsets.UTF_8));
    resp.setData(data);
    when(client.im().messageResource().get(any())).thenReturn(resp);
    return client;
  }

  private static long countRegularFiles(Path dir) throws Exception {
    if (!Files.isDirectory(dir)) {
      return 0L;
    }
    try (Stream<Path> files = Files.walk(dir)) {
      return files.filter(Files::isRegularFile).count();
    }
  }
}
