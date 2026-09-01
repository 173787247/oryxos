package io.oryxos.provider;

import io.oryxos.core.profile.Profile;
import io.oryxos.core.provider.LlmCallAuditor;
import io.oryxos.core.provider.ModelPricing;
import io.oryxos.core.provider.PricingStore;
import io.oryxos.core.provider.ProviderDef;
import io.oryxos.core.provider.ProviderRegistry;
import io.oryxos.core.provider.ProviderRequest;
import io.oryxos.core.provider.ProviderResponse;
import io.oryxos.core.provider.ProviderService;
import io.oryxos.core.provider.ToolCallRequest;
import io.oryxos.core.provider.Usage;
import io.oryxos.core.session.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;

/**
 * Provider 前台（core {@link ProviderService} 契约的 Spring AI 实现）：按 Profile 显式路由到对应
 * ChatModel，完成一次调用并落审计。
 *
 * <p>宪法 II/III：显式 name→ChatModel 映射、调用方式 {@code chatModel.call(new Prompt(...))}、 {@code
 * internalToolExecutionEnabled=false} 关闭框架自动工具执行——工具 schema 只翻译、tool call 原样透传。
 */
public class SpringAiProviderServiceImpl implements ProviderService {

  private static final Logger LOG = LoggerFactory.getLogger(SpringAiProviderServiceImpl.class);

  private final ProviderRegistry registry;
  private final Function<ProviderDef, ChatModel> chatModelBuilder;
  private final ToolSchemaAdapter adapter;
  private final LlmCallAuditor audit;
  private final PricingStore pricingStore;
  // 已建的 ChatModel 缓存：key = provider name，值携带配置指纹（apiKey|baseUrl）。指纹变了原地替换旧条目——
  // 缓存大小恒等于 provider 数，反复改 key/url 不再累积不可回收的旧实例（31 节动态 provider）。
  private final Map<String, CachedModel> cache = new ConcurrentHashMap<>();

  /** 缓存条目：配置指纹 + 已建实例，指纹不变则复用。 */
  private record CachedModel(String fingerprint, ChatModel model) {}

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "registry/builder/adapter/audit 均为 Spring 注入的共享单例，构造注入共享同一引用正是意图")
  public SpringAiProviderServiceImpl(
      ProviderRegistry registry,
      Function<ProviderDef, ChatModel> chatModelBuilder,
      ToolSchemaAdapter adapter,
      LlmCallAuditor audit,
      PricingStore pricingStore) {
    this.registry = registry;
    this.chatModelBuilder = chatModelBuilder;
    this.adapter = adapter;
    this.audit = audit;
    this.pricingStore = pricingStore;
  }

  @Override
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "日志中的 provider 名已经 sanitize() 消去 CR/LF；taint 分析不跨方法追踪该消毒，故局部抑制")
  public ProviderResponse chat(String sessionId, Profile profile, ProviderRequest request) {
    String providerName = profile.provider().name();
    // 宪法 III：仍是按 name 的显式查找，只是从"启动静态 map"变成"运行时注册表 + 按名动态建/缓存"
    ProviderDef def =
        registry.find(providerName).orElseThrow(() -> new ProviderNotFoundException(providerName));
    ChatModel model = resolveModel(def);
    Prompt prompt = buildPrompt(profile, request);
    long startedAt = System.currentTimeMillis();
    ProviderResponse result;
    try {
      ChatResponse response = model.call(prompt);
      result = toProviderResponse(response);
    } catch (RuntimeException e) {
      recordFailure(sessionId, profile, providerName, e, startedAt);
      throw e;
    }
    recordSuccess(sessionId, profile, providerName, result, startedAt);
    return result;
  }

  /**
   * 流式调用（019 R2）：{@code model.stream(prompt)} 经 {@code toIterable()} 在当前（虚拟）线程上同步迭代—— Flux/Reactor
   * 类型不出本方法（宪法 VII 边界）。只回调 content 增量（R3）；tool-call 增量在本地聚合。
   *
   * <p>降级（FR-006）：模型无流式能力（{@code stream} 抛 {@link UnsupportedOperationException}）且尚无任何输出时，
   * 回落到契约默认实现（整段 {@code chat} + 一次性回调，审计在 chat 内）。已有部分输出后失败 → 结果残缺， 失败先落账再上抛，绝不把残缺内容当完整返回。
   */
  @Override
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = {"CRLF_INJECTION_LOGS", "RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE"},
      justification =
          "CRLF：与 chat 同口径，日志里的 provider 名经 sanitize() 消去 CR/LF，taint 分析不跨方法追踪该消毒；"
              + "RCN：Spring AI 的注解声称 chunk 各字段非空，但流式 chunk 的边界形态因 provider 而异，"
              + "对 generation/output 的防御性判空是有意保留的（信注解不如信线上流量）")
  public ProviderResponse chatStream(
      String sessionId,
      Profile profile,
      ProviderRequest request,
      java.util.function.Consumer<String> onToken) {
    String providerName = profile.provider().name();
    ProviderDef def =
        registry.find(providerName).orElseThrow(() -> new ProviderNotFoundException(providerName));
    ChatModel model = resolveModel(def);
    Prompt prompt = buildPrompt(profile, request);
    long startedAt = System.currentTimeMillis();
    StringBuilder text = new StringBuilder();
    ToolCallAggregator toolCalls = new ToolCallAggregator();
    Usage usage = null;
    try {
      for (ChatResponse chunk : model.stream(prompt).toIterable()) {
        Generation generation = chunk.getResult();
        if (generation != null && generation.getOutput() != null) {
          String delta = generation.getOutput().getText();
          if (delta != null && !delta.isEmpty()) {
            text.append(delta);
            onToken.accept(delta);
          }
          toolCalls.accept(generation.getOutput().getToolCalls());
        }
        Usage chunkUsage = extractUsage(chunk);
        // 多数 provider 只在末尾 chunk 带真实 usage，中间是空/零值——只保留最后一个有效值
        if (chunkUsage != null
            && chunkUsage.totalTokens() != null
            && chunkUsage.totalTokens() > 0) {
          usage = chunkUsage;
        }
      }
    } catch (UnsupportedOperationException e) {
      if (text.isEmpty() && toolCalls.isEmpty()) {
        // 模型无流式能力且零输出：安全降级整段路径（审计由 chat 负责，恰好一条）
        return ProviderService.super.chatStream(sessionId, profile, request, onToken);
      }
      recordFailure(sessionId, profile, providerName, e, startedAt);
      throw e;
    } catch (RuntimeException e) {
      // 流式中断（网络抖动/上游截断）：结果残缺不当完整返回（FR-006）——失败先落账再上抛（宪法 V）
      recordFailure(sessionId, profile, providerName, e, startedAt);
      throw e;
    }
    ProviderResponse result = new ProviderResponse(text.toString(), toolCalls.build(), usage);
    recordSuccess(sessionId, profile, providerName, result, startedAt);
    return result;
  }

  /**
   * 失败审计（宪法 V）：先落账再上抛——只记成功不记失败，一次真实事故就没有痕迹。 审计自身再失败也不许反客为主：上抛的必须是模型调用的真实异常（排障首先看到的是「LLM 调
   * 400」而非「审计存储抖动」），审计异常挂 suppressed + ERROR 日志独立告警。
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "日志里的 provider 名经 sanitize() 消去 CR/LF，taint 分析不跨方法追踪该消毒")
  private void recordFailure(
      String sessionId, Profile profile, String providerName, RuntimeException e, long startedAt) {
    try {
      audit.record(
          sessionId,
          profile.name(),
          providerName,
          profile.provider().model(),
          null,
          null,
          false,
          e.getMessage(),
          System.currentTimeMillis() - startedAt);
    } catch (RuntimeException auditFailure) {
      LOG.error("LLM 调用失败的审计落库也失败（主异常照常上抛）: provider={}", sanitize(providerName), auditFailure);
      e.addSuppressed(auditFailure);
    }
  }

  /**
   * 成功审计 fail-open：调用已成功、token 已消耗，审计存储抖动不应让调用方丢掉这次完整回答 （宪法 V 约束的是实现上不许省审计，不是拿审计故障牺牲用户请求）；失败走 ERROR
   * 日志独立告警。
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "日志里的 provider 名经 sanitize() 消去 CR/LF，taint 分析不跨方法追踪该消毒")
  private void recordSuccess(
      String sessionId,
      Profile profile,
      String providerName,
      ProviderResponse result,
      long startedAt) {
    try {
      audit.record(
          sessionId,
          profile.name(),
          providerName,
          profile.provider().model(),
          result.usage(),
          computeCost(providerName, profile.provider().model(), result.usage()),
          true,
          null,
          System.currentTimeMillis() - startedAt);
    } catch (RuntimeException auditFailure) {
      LOG.error("成功 LLM 调用的审计落库失败（结果照常返回）: provider={}", sanitize(providerName), auditFailure);
    }
    // 021 日志与审计互查（SC-007）：处理路径关键日志点——MDC 自动携带 traceId，不记 prompt 内容
    LOG.info(
        "LLM 调用完成: provider={} model={} totalTokens={} durationMs={}",
        sanitize(providerName),
        sanitize(profile.provider().model()),
        result.usage() == null ? null : result.usage().totalTokens(),
        System.currentTimeMillis() - startedAt);
  }

  /**
   * 流式 tool-call 聚合器（019 R2）：兼容两种 chunk 形态——「合并式」（一个 chunk 携带完整 tool call）与 「增量式」（同一 id 的 arguments
   * 分片到达，或 id 只在首片、后续片 id 为空）。按 id 归组、arguments 顺序拼接。
   */
  private static final class ToolCallAggregator {

    private final Map<String, PendingCall> byId = new java.util.LinkedHashMap<>();
    private PendingCall current;

    private static final class PendingCall {
      private String name;
      private final StringBuilder arguments = new StringBuilder();
    }

    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
        value = "RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE",
        justification = "Spring AI 注解声称 ToolCall 各字段非空，但流式 chunk 增量形态因 provider 而异，防御性判空有意保留")
    void accept(List<AssistantMessage.ToolCall> calls) {
      if (calls == null) {
        return;
      }
      for (AssistantMessage.ToolCall call : calls) {
        String id = call.id();
        if (id != null && !id.isBlank()) {
          current = byId.computeIfAbsent(id, k -> new PendingCall());
        } else if (current == null) {
          // 首片就无 id 的异常形态：给个合成 id 兜底（真实 provider 首片必带 id）
          current = byId.computeIfAbsent("stream-call-" + byId.size(), k -> new PendingCall());
        }
        if (call.name() != null && !call.name().isBlank()) {
          current.name = call.name();
        }
        if (call.arguments() != null) {
          current.arguments.append(call.arguments());
        }
      }
    }

    boolean isEmpty() {
      return byId.isEmpty();
    }

    List<ToolCallRequest> build() {
      return byId.entrySet().stream()
          .map(
              e ->
                  new ToolCallRequest(
                      e.getKey(), e.getValue().name, e.getValue().arguments.toString()))
          .toList();
    }
  }

  /** 日志参数消毒：去掉换行，防日志伪造（CRLF injection）。 */
  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }

  /** 按 (provider, model) 查价算成本（微元）；失败/查不到价 → null（未计量）。 */
  private Long computeCost(String providerName, String model, Usage usage) {
    if (usage == null || usage.totalTokens() == null) {
      return null;
    }
    return pricingStore.find(providerName, model).map(p -> computeMicros(usage, p)).orElse(null);
  }

  private static Long computeMicros(Usage usage, ModelPricing pricing) {
    Double promptPrice = pricing.promptPrice();
    Double completionPrice = pricing.completionPrice();
    if (promptPrice == null && completionPrice == null) {
      return null;
    }
    long micros = 0;
    if (usage.promptTokens() != null && promptPrice != null) {
      micros += Math.round(usage.promptTokens() * promptPrice);
    }
    if (usage.completionTokens() != null && completionPrice != null) {
      micros += Math.round(usage.completionTokens() * completionPrice);
    }
    return micros;
  }

  /** 按 provider 名缓存已建的 ChatModel；同名下 key/url 变化即原地重建替换（provider CRUD 改了配置立即生效，旧实例可回收）。 */
  private ChatModel resolveModel(ProviderDef def) {
    String fingerprint = def.apiKey() + "|" + def.baseUrl();
    return cache
        .compute(
            def.name(),
            (name, cached) ->
                cached != null && cached.fingerprint().equals(fingerprint)
                    ? cached
                    : new CachedModel(fingerprint, chatModelBuilder.apply(def)))
        .model();
  }

  private Prompt buildPrompt(Profile profile, ProviderRequest request) {
    OpenAiChatOptions.Builder options =
        OpenAiChatOptions.builder()
            .model(profile.provider().model())
            .internalToolExecutionEnabled(Boolean.FALSE); // 执行权只在 ToolExecutor（17 节）
    if (profile.provider().temperature() != null) {
      options.temperature(profile.provider().temperature());
    }
    List<ToolCallback> callbacks = adapter.toSpringAiTools(request.availableTools());
    if (!callbacks.isEmpty()) {
      options.toolCallbacks(callbacks);
    }
    // 结构化消息透传（31 节修复）：system + 逐条对话消息，保留 assistant tool_calls / tool tool_call_id 配对，
    // 让模型看出工具已调过、继续下一步而不是反复重调。
    List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
    if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
      messages.add(new SystemMessage(request.systemPrompt()));
    }
    for (Message message : request.messages()) {
      messages.add(toSpringMessage(message));
    }
    return new Prompt(messages, options.build());
  }

  private static org.springframework.ai.chat.messages.Message toSpringMessage(Message message) {
    if (Message.ROLE_USER.equals(message.role())) {
      return new UserMessage(message.content());
    }
    if (Message.ROLE_TOOL.equals(message.role())) {
      String id = message.toolCallId();
      // 无 id 的工具结果（旧格式历史，或被截断得没了配对的 assistant tool_call）：不发成协议级 tool 消息——
      // 否则 OpenAI 会 400「tool 必须紧跟带 tool_calls 的 assistant」。降级成信息性 user 文本喂给模型。
      if (id == null || id.isBlank()) {
        return new UserMessage("[工具 " + message.toolName() + " 返回] " + message.content());
      }
      return ToolResponseMessage.builder()
          .responses(
              List.of(
                  new ToolResponseMessage.ToolResponse(id, message.toolName(), message.content())))
          .build();
    }
    // assistant：带 tool_calls（含 id）才能让下一轮的 tool 结果配上对
    if (message.toolCalls().isEmpty()) {
      return new AssistantMessage(message.content());
    }
    List<AssistantMessage.ToolCall> toolCalls =
        message.toolCalls().stream()
            .map(
                tc ->
                    new AssistantMessage.ToolCall(
                        tc.id() == null ? "" : tc.id(), "function", tc.name(), tc.argumentsJson()))
            .toList();
    return AssistantMessage.builder()
        .content(message.content())
        .properties(Map.of())
        .toolCalls(toolCalls)
        .build();
  }

  private static ProviderResponse toProviderResponse(ChatResponse response) {
    Generation generation = response.getResult();
    AssistantMessage output = generation.getOutput();
    String text = output.getText();
    List<ToolCallRequest> toolCalls =
        output.getToolCalls().stream()
            .map(call -> new ToolCallRequest(call.id(), call.name(), call.arguments()))
            .toList();
    return new ProviderResponse(text, toolCalls, extractUsage(response));
  }

  private static Usage extractUsage(ChatResponse response) {
    if (response.getMetadata().getUsage() == null) {
      return null;
    }
    org.springframework.ai.chat.metadata.Usage usage = response.getMetadata().getUsage();
    return new Usage(usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
  }
}
