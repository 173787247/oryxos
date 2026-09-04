package io.oryxos.cli;

import io.oryxos.core.channel.InboundSpeechTranscriber;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

/**
 * OpenAI 兼容 Whisper 转写（{@code POST /v1/audio/transcriptions}）。 环境变量：{@code ORYXOS_ASR_API_KEY} 或
 * {@code OPENAI_API_KEY}；可选 {@code ORYXOS_ASR_BASE_URL} / {@code OPENAI_BASE_URL}（默认 {@code
 * https://api.openai.com}）。
 */
public final class WhisperHttpTranscriber implements InboundSpeechTranscriber {

  private static final Duration TIMEOUT = Duration.ofSeconds(120);
  private static final String DEFAULT_BASE = "https://api.openai.com";
  private static final String PATH = "/v1/audio/transcriptions";

  private final String apiKey;
  private final String baseUrl;
  private final HttpClient httpClient;

  WhisperHttpTranscriber(String apiKey, String baseUrl, HttpClient httpClient) {
    this.apiKey = apiKey;
    this.baseUrl = trimTrailingSlash(baseUrl);
    this.httpClient = httpClient;
  }

  /** 未配置密钥时返回 null（enricher 降级为「未配置 ASR」提示）。 */
  public static InboundSpeechTranscriber fromEnv() {
    String key =
        firstNonBlank(System.getenv("ORYXOS_ASR_API_KEY"), System.getenv("OPENAI_API_KEY"));
    if (key == null || key.isBlank()) {
      return null;
    }
    String base =
        firstNonBlank(
            System.getenv("ORYXOS_ASR_BASE_URL"), System.getenv("OPENAI_BASE_URL"), DEFAULT_BASE);
    return new WhisperHttpTranscriber(
        key.strip(), base, HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
  }

  @Override
  public String transcribe(Path audioFile) throws Exception {
    if (audioFile == null || !Files.isRegularFile(audioFile)) {
      throw new IOException("音频文件不存在: " + audioFile);
    }
    byte[] bodyBytes = Files.readAllBytes(audioFile);
    if (bodyBytes.length == 0) {
      throw new IOException("音频文件为空");
    }
    String boundary = "----oryxos" + UUID.randomUUID().toString().replace("-", "");
    String fileName = whisperFileName(audioFile, bodyBytes);
    byte[] multipart = buildMultipart(boundary, fileName, bodyBytes);
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + PATH))
            .timeout(TIMEOUT)
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(multipart))
            .build();
    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      String body = response.body();
      String preview =
          body == null
              ? ""
              : body.substring(0, Math.min(200, body.length()))
                  .replace('\n', ' ')
                  .replace('\r', ' ');
      throw new IOException("Whisper HTTP " + response.statusCode() + ": " + preview);
    }
    return extractText(response.body());
  }

  /** Whisper 按上传文件名判格式：占位 .bin 但内容是 Ogg 时改成 .ogg。 */
  static String whisperFileName(Path audioFile, byte[] bodyBytes) {
    String name =
        audioFile.getFileName() == null ? "audio.bin" : audioFile.getFileName().toString();
    String lower = name.toLowerCase(java.util.Locale.ROOT);
    if (lower.endsWith(".ogg")
        || lower.endsWith(".oga")
        || lower.endsWith(".mp3")
        || lower.endsWith(".wav")
        || lower.endsWith(".m4a")
        || lower.endsWith(".webm")
        || lower.endsWith(".flac")) {
      return name;
    }
    if (io.oryxos.core.session.InboundMediaExt.isOggMagic(bodyBytes)) {
      return "voice.ogg";
    }
    return name;
  }

  private static byte[] buildMultipart(String boundary, String fileName, byte[] fileBytes)
      throws IOException {
    String preamble =
        "--"
            + boundary
            + "\r\n"
            + "Content-Disposition: form-data; name=\"model\"\r\n\r\n"
            + "whisper-1\r\n"
            + "--"
            + boundary
            + "\r\n"
            + "Content-Disposition: form-data; name=\"file\"; filename=\""
            + fileName.replace("\"", "")
            + "\"\r\n"
            + "Content-Type: application/octet-stream\r\n\r\n";
    String epilogue = "\r\n--" + boundary + "--\r\n";
    byte[] pre = preamble.getBytes(StandardCharsets.UTF_8);
    byte[] epi = epilogue.getBytes(StandardCharsets.UTF_8);
    byte[] all = new byte[pre.length + fileBytes.length + epi.length];
    System.arraycopy(pre, 0, all, 0, pre.length);
    System.arraycopy(fileBytes, 0, all, pre.length, fileBytes.length);
    System.arraycopy(epi, 0, all, pre.length + fileBytes.length, epi.length);
    return all;
  }

  private static String extractText(String json) throws IOException {
    if (json == null || json.isBlank()) {
      throw new IOException("Whisper 响应为空");
    }
    // 最小解析：{"text":"..."}，避免为 ASR 拉 Jackson 进 cli 装配面
    String marker = "\"text\"";
    int i = json.indexOf(marker);
    if (i < 0) {
      throw new IOException("Whisper 响应无 text 字段");
    }
    int colon = json.indexOf(':', i + marker.length());
    int quote = json.indexOf('"', colon + 1);
    if (quote < 0) {
      throw new IOException("Whisper 响应 text 格式异常");
    }
    StringBuilder out = new StringBuilder();
    for (int p = quote + 1; p < json.length(); p++) {
      char c = json.charAt(p);
      if (c == '\\' && p + 1 < json.length()) {
        char n = json.charAt(++p);
        out.append(
            switch (n) {
              case 'n' -> '\n';
              case 'r' -> '\r';
              case 't' -> '\t';
              case '"' -> '"';
              case '\\' -> '\\';
              default -> n;
            });
        continue;
      }
      if (c == '"') {
        break;
      }
      out.append(c);
    }
    return out.toString().strip();
  }

  private static String firstNonBlank(String... values) {
    if (values == null) {
      return null;
    }
    for (String v : values) {
      if (v != null && !v.isBlank()) {
        return v;
      }
    }
    return null;
  }

  private static String trimTrailingSlash(String url) {
    if (url == null || url.isBlank()) {
      return DEFAULT_BASE;
    }
    String s = url.strip();
    while (s.endsWith("/")) {
      s = s.substring(0, s.length() - 1);
    }
    return s;
  }
}
