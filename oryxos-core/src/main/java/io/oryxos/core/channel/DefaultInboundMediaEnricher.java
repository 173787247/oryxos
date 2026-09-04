package io.oryxos.core.channel;

import io.oryxos.core.session.ImageMime;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 默认入站媒体富化：文本直传；图片/文件转为说明文案；语音优先转写正文。图片另由 {@link InboundMediaParts} 供 Vision；文件/ 未转写语音只走文案路径。 */
public final class DefaultInboundMediaEnricher implements InboundMediaEnricher {

  private static final Logger LOG = LoggerFactory.getLogger(DefaultInboundMediaEnricher.class);

  private static final String IMAGE_WITH_URL = "[用户发送了一张图片]\n图片链接: ";
  private static final String IMAGE_WITH_REF = "[用户发送了一张图片]\n图片资源: ";
  private static final String FILE_WITH_URL = "[用户发送了一个文件]\n本地路径: ";
  private static final String FILE_WITH_REF = "[用户发送了一个文件]\n文件资源: ";
  private static final String FILE_NAME_LINE = "\n文件名: ";
  private static final String FILE_HINT = "\n可用 read_file 读取该路径（文本或文本型 PDF；须在 FILE 沙箱白名单内）。";
  private static final String AUDIO_PREFIX = "[用户发送了一段语音]\n转写: ";
  private static final String AUDIO_PATH = "[用户发送了一段语音]\n本地路径: ";
  private static final String AUDIO_NO_ASR =
      "\n未配置语音转写（设置 OPENAI_API_KEY 或 ORYXOS_ASR_API_KEY 启用 Whisper）。";
  private static final String AUDIO_FFMPEG =
      "\n语音转写失败（格式需 ffmpeg 转码，请安装 ffmpeg 或设置 ORYXOS_FFMPEG）: ";
  private static final String AUDIO_ASR_FAIL = "\n语音转写失败: ";
  private static final String VIDEO_WITH_URL = "[用户发送了一段视频]\n本地路径: ";
  private static final String VIDEO_WITH_REF = "[用户发送了一段视频]\n视频资源: ";
  private static final String VIDEO_HINT = "\n视频已落盘；可用工具处理本地文件（不自动理解画面）。";
  private static final String VIDEO_AUDIO_PREFIX = "\n音轨转写: ";
  private static final String VIDEO_AUDIO_FAIL = "\n音轨转写失败: ";
  private static final String MARKER_FFMPEG = "需安装 ffmpeg";

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
        parts.add(enrichFile(attachment));
      } else if (InboundAttachment.TYPE_AUDIO.equals(attachment.type())) {
        parts.add(enrichAudio(attachment));
      } else if (InboundAttachment.TYPE_VIDEO.equals(attachment.type())) {
        parts.add(enrichVideo(attachment));
      }
    }
    return String.join("\n\n", parts).strip();
  }

  private static String enrichFile(InboundAttachment attachment) {
    StringBuilder sb = new StringBuilder();
    if (attachment.url() != null && !attachment.url().isBlank()) {
      sb.append(FILE_WITH_URL).append(attachment.url().strip());
    } else if (attachment.reference() != null && !attachment.reference().isBlank()) {
      sb.append(FILE_WITH_REF).append(attachment.reference().strip());
    } else {
      return "";
    }
    if (attachment.fileName() != null && !attachment.fileName().isBlank()) {
      sb.append(FILE_NAME_LINE).append(attachment.fileName().strip());
    }
    if (attachment.url() != null && !attachment.url().isBlank()) {
      sb.append(FILE_HINT);
    }
    return sb.toString();
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
        String detail = sanitize(e.getMessage());
        if (isFfmpegRelated(detail)) {
          return AUDIO_PATH + path + AUDIO_FFMPEG + detail;
        }
        return AUDIO_PATH + path + AUDIO_ASR_FAIL + detail;
      }
    }
    if (speechTranscriber == null) {
      return AUDIO_PATH + path + AUDIO_NO_ASR;
    }
    return AUDIO_PATH + path;
  }

  private String enrichVideo(InboundAttachment attachment) {
    StringBuilder sb = new StringBuilder();
    String path =
        attachment.url() != null && !attachment.url().isBlank()
            ? attachment.url().strip()
            : (attachment.reference() != null ? attachment.reference().strip() : "");
    if (attachment.url() != null && !attachment.url().isBlank()) {
      sb.append(VIDEO_WITH_URL).append(path);
      if (attachment.fileName() != null && !attachment.fileName().isBlank()) {
        sb.append(FILE_NAME_LINE).append(attachment.fileName().strip());
      }
      sb.append(VIDEO_HINT);
      if (speechTranscriber != null && !ImageMime.isHttpUrl(attachment.url())) {
        try {
          String text = speechTranscriber.transcribe(Path.of(attachment.url().strip()));
          if (text != null && !text.isBlank()) {
            sb.append(VIDEO_AUDIO_PREFIX).append(text.strip());
          }
        } catch (Exception e) {
          LOG.warn("入站视频音轨转写失败: {}", sanitize(e.getMessage()));
          sb.append(VIDEO_AUDIO_FAIL).append(sanitize(e.getMessage()));
        }
      }
    } else if (attachment.reference() != null && !attachment.reference().isBlank()) {
      sb.append(VIDEO_WITH_REF).append(path);
      if (attachment.fileName() != null && !attachment.fileName().isBlank()) {
        sb.append(FILE_NAME_LINE).append(attachment.fileName().strip());
      }
    }
    return sb.toString();
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification = "仅对 ASCII 错误文案关键字做 Locale.ROOT 小写匹配")
  private static boolean isFfmpegRelated(String detail) {
    if (detail == null || detail.isBlank()) {
      return false;
    }
    return detail.toLowerCase(Locale.ROOT).contains(MARKER_FFMPEG.toLowerCase(Locale.ROOT));
  }

  private static String sanitize(String value) {
    if (value == null) {
      return "";
    }
    return value.replace('\r', '_').replace('\n', '_');
  }
}
