package io.oryxos.core.session;

import java.util.List;

/**
 * 会话中的一条消息。role 三值：{@code user} / {@code assistant} / {@code tool}；toolName 仅 tool 角色非空。
 *
 * <p>为支持多步 ReAct（31 节修复）保留 OpenAI 工具调用配对：assistant 消息带它发起的 {@code toolCalls}（每个含模型分配的 id）， tool
 * 消息带它回应的 {@code toolCallId}。旧三参构造保留（user / 简单 assistant / 无 id 的 tool），旧会话 JSON 反序列化时新字段缺省为空。
 *
 * <p>{@code media}：用户轮可选的多模态附件（图片 URL 或本地绝对路径）。历史 JSON 无此字段时反序列化为空列表。
 */
public record Message(
    String role,
    String content,
    String toolName,
    String toolCallId,
    List<ToolCall> toolCalls,
    List<MediaPart> media) {

  public static final String ROLE_USER = "user";
  public static final String ROLE_ASSISTANT = "assistant";
  public static final String ROLE_TOOL = "tool";

  public Message {
    toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    media = media == null ? List.of() : List.copyOf(media);
  }

  public Message(String role, String content, String toolName) {
    this(role, content, toolName, null, List.of(), List.of());
  }

  public Message(
      String role, String content, String toolName, String toolCallId, List<ToolCall> toolCalls) {
    this(role, content, toolName, toolCallId, toolCalls, List.of());
  }

  /** assistant 发起的一次工具调用（含模型分配的 id，回填结果时据此配对）。 */
  public record ToolCall(String id, String name, String argumentsJson) {}

  /**
   * 用户消息中的媒体引用。
   *
   * @param mimeType 如 {@code image/jpeg}；可空，由 provider 侧再嗅探
   * @param uri {@code http(s)://} URL 或本地绝对路径（飞书落地文件）
   */
  public record MediaPart(String mimeType, String uri) {
    public MediaPart {
      if (uri == null || uri.isBlank()) {
        throw new IllegalArgumentException("uri 不能为空");
      }
      uri = uri.strip();
      mimeType = mimeType == null || mimeType.isBlank() ? null : mimeType.strip();
    }
  }
}
