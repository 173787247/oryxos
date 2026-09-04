package io.oryxos.core.channel;

import io.oryxos.core.session.ImageMime;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 默认入站媒体富化：文本直传；图片/文件转为说明文案；语音优先转写正文。图片另由 {@link InboundMediaParts} 供 Vision；文件/ 未转写语音只走文案路径。 */
public final class DefaultInboundMediaEnricher implements InboundMediaEnricher {

  private static final Logger LOG = LoggerFactory.getLogger(DefaultInboundMediaEnricher.class);

  private static final String IMAGE_WITH_URL = "[用户发送了一张图片]\n图片链接: ";
  private static final String IMAGE_WITH_REF = "[用户发送了一张图片]\n图片资源: ";
  private static final String FILE_WITH_URL = "[用户发送了一个文件]\n本地路径: ";
  private static final String FILE_WITH_REF = "[用户发送了一个文件]\n文件资源: ";
  private static final String FILE_HINT = "\n可用 read_file 读取该路径（文本或文本型 PDF；须在 FILE 沙箱白名单内）。";
  private static final String AUDIO_PREFIX = "[用户发送了一段语音]\n转写: ";
  private static final String AUDIO_PATH = "[用户发送了一段语音]\n本地路径: ";
  private static final String AUDIO_NO_ASR =
      "\n未配置语音转写（设置 OPENAI_API_KEY 或 ORYXOS_ASR_API_KEY 启用 Whisper）。";
  private static final String AUDIO_ASR_FAIL = "\n语音转写失败: ";

  private final InboundSpeechTranscriber speechTranscriber;

  public DefaultInboundMediaEnricher() {
    this(null);
  }

  public DefaultInboundMediaEnricher(InboundSpeechTranscriber speechTranscriber) {
    this.speechTranscriber = speechTranscriber;
  }

  @Override
  public String toAgentInput(InboundMessage message) {
    List<String> parts = new ArrayList<>();
    if (message.content() != null && !message.content().isBlank()) {
      parts.add(message.content().strip());
    }
    for (InboundAttachment attachment : message.attachments()) {
      if (InboundAttachment.TYPE_IMAGE.equals(attachment.type())) {
        if (attachment.url() != null && !attachment.url().isBlank()) {
          parts.add(IMAGE_WITH_URL + attachment.url().strip());
        } else if (attachment.reference() != null && !attachment.reference().isBlank()) {
          parts.add(IMAGE_WITH_REF + attachment.reference().strip());
        }
      } else if (InboundAttachment.TYPE_FILE.equals(attachment.type())) {
        if (attachment.url() != null && !attachment.url().isBlank()) {
          parts.add(FILE_WITH_URL + attachment.url().strip() + FILE_HINT);
        } else if (attachment.reference() != null && !attachment.reference().isBlank()) {
          parts.add(FILE_WITH_REF + attachment.reference().strip());
        }
      } else if (InboundAttachment.TYPE_AUDIO.equals(attachment.type())) {
        parts.add(enrichAudio(attachment));
      }
    }
    return String.join("\n\n", parts).strip();
  }

  private String enrichAudio(InboundAttachment attachment) {
    String path =
        attachment.url() != null && !attachment.url().isBlank()
            ? attachment.url().strip()
            : (attachment.reference() != null ? attachment.reference().strip() : "");
    if (speechTranscriber != null
        && attachment.url() != null
        && !attachment.url().isBlank()
        && !ImageMime.isHttpUrl(attachment.url())) {
      try {
        String text = speechTranscriber.transcribe(Path.of(attachment.url().strip()));
        if (text != null && !text.isBlank()) {
          return AUDIO_PREFIX + text.strip();
        }
      } catch (Exception e) {
        LOG.warn("入站语音转写失败: {}", sanitize(e.getMessage()));
        return AUDIO_PATH + path + AUDIO_ASR_FAIL + sanitize(e.getMessage());
      }
    }
    if (speechTranscriber == null) {
      return AUDIO_PATH + path + AUDIO_NO_ASR;
    }
    return AUDIO_PATH + path;
  }

  private static String sanitize(String value) {
    if (value == null) {
      return "";
    }
    return value.replace('\r', '_').replace('\n', '_');
  }
}
