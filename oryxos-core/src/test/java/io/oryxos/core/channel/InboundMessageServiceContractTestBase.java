package io.oryxos.core.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.oryxos.core.agent.AgentExecutionService;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 入站渠道契约测试集基类（017 T024，FR-010/SC-007）：对 contracts/inbound-channel-contract.md 的行为规则 B1~B10
 * 逐条钉死。参数化两档：
 *
 * <ul>
 *   <li>桩档（{@code StubInboundContractTest}，core）——直接构造归一化消息，证明第二个渠道零 core 修改接入；
 *   <li>飞书档（{@code FeishuChannelContractTest}，oryxos-channel-feishu）——消息经 FeishuEventNormalizer
 *       从真实事件 JSON 归一化产出，证明飞书适配器满足同一契约。
 * </ul>
 *
 * <p>子类只提供消息构造方式（{@link #p2pMessage}/{@link #groupMessage}/{@link #nonTextualMessage}）与渠道
 * 类型标识，行为断言完全共享。
 */
public abstract class InboundMessageServiceContractTestBase {

  protected static final String AGENT = "ops-agent";

  protected AgentService agentService;
  protected SessionManager sessionManager;
  protected ProfileRegistry profileRegistry;
  protected AgentExecutionService executionService;
  protected StubChannelAdapter replyChannel;
  protected InboundMessageService service;

  /** 本档的渠道类型标识（会话 channel 位与审计 source）。 */
  protected abstract String channelType();

  /** 构造一条私聊文本消息。 */
  protected abstract InboundMessage p2pMessage(String messageId, String content);

  /** 构造一条群聊 @ 机器人文本消息。 */
  protected abstract InboundMessage groupMessage(String messageId, String content);

  /** 构造一条私聊非文本且不可处理的消息（无附件）。 */
  protected abstract InboundMessage nonTextualMessage(String messageId);

  /** 构造一条私聊图片消息（含附件，应进入编排）。 */
  protected abstract InboundMessage imageMessage(String messageId);

  @BeforeEach
  void contractSetUp() {
    agentService = mock(AgentService.class);
    sessionManager = mock(SessionManager.class);
    profileRegistry = mock(ProfileRegistry.class);
    executionService = mock(AgentExecutionService.class);
    replyChannel = new StubChannelAdapter("contract-chan", AGENT);
    service =
        new InboundMessageService(
            agentService,
            sessionManager,
            profileRegistry,
            executionService,
            new MessageDeduplicator(),
            null,
            Duration.ofSeconds(30));
    when(profileRegistry.get(AGENT)).thenReturn(Optional.of(mock(Profile.class)));
    doAnswer(
            inv -> {
              try {
                ((Runnable) inv.getArgument(3)).run();
              } catch (RuntimeException ignored) {
                // 真实 AgentExecutionService 捕获异常并落失败记录
              }
              return 1L;
            })
        .when(executionService)
        .triggerAsync(anyString(), anyString(), any(), any());
  }

  private Session stubSession(String userId) {
    Session session = new Session(channelType() + ":" + userId + ":" + AGENT, AGENT);
    when(sessionManager.getOrCreate(eq(channelType()), eq(userId), eq(AGENT))).thenReturn(session);
    return session;
  }

  @Test
  @DisplayName("B1: 重复 message_id 只处理一次，用户只收到一条回答")
  void b1Deduplication() {
    InboundMessage msg = p2pMessage("dup-1", "你好");
    Session session = stubSession(msg.userId());
    when(agentService.process(eq(session), anyString(), anyList())).thenReturn("回答");

    service.onMessage(msg, replyChannel);
    service.onMessage(msg, replyChannel);

    verify(agentService, times(1)).process(eq(session), anyString(), anyList());
    assertEquals(1, replyChannel.sent().size());
  }

  @Test
  @DisplayName("B2: 私聊走「渠道+用户+Agent」持久会话")
  void b2P2pPersistentSession() {
    InboundMessage msg = p2pMessage("p2p-1", "磁盘告警怎么处理");
    Session session = stubSession(msg.userId());
    when(agentService.process(eq(session), anyString(), anyList())).thenReturn("先看 df -h");

    service.onMessage(msg, replyChannel);

    verify(sessionManager).getOrCreate(channelType(), msg.userId(), AGENT);
    assertEquals("先看 df -h", replyChannel.sent().get(0).text());
  }

  @Test
  @DisplayName("B3: 群聊走无状态问答（渠道前缀临时会话 id），不触达持久会话")
  void b3GroupStateless() {
    InboundMessage msg = groupMessage("grp-1", "发布为什么回滚");
    when(agentService.processStateless(
            eq(AGENT), anyString(), anyList(), startsWith(channelType() + "-group:")))
        .thenReturn("配置漂移");

    service.onMessage(msg, replyChannel);

    verify(agentService)
        .processStateless(eq(AGENT), anyString(), anyList(), startsWith(channelType() + "-group:"));
    verifyNoInteractions(sessionManager);
  }

  @Test
  @DisplayName("B4: 私聊直发；群聊回复引用原消息")
  void b4ReplyCorrelation() {
    Session session = stubSession(p2pMessage("x", "x").userId());
    when(agentService.process(any(), anyString(), anyList())).thenReturn("答");
    when(agentService.processStateless(anyString(), anyString(), anyList(), anyString()))
        .thenReturn("答");

    service.onMessage(p2pMessage("corr-p2p", "问"), replyChannel);
    service.onMessage(groupMessage("corr-grp", "问"), replyChannel);

    assertEquals(null, replyChannel.sent().get(0).replyToMessageId());
    assertEquals("corr-grp", replyChannel.sent().get(1).replyToMessageId());
  }

  @Test
  @DisplayName("B5/B10: 推理经 triggerAsync 提交（source=渠道类型），审计以会话 id 关联")
  void b5b10AuditViaTriggerAsync() {
    InboundMessage msg = p2pMessage("audit-1", "问");
    Session session = stubSession(msg.userId());
    when(agentService.process(eq(session), anyString(), anyList())).thenReturn("答");

    service.onMessage(msg, replyChannel);

    verify(executionService)
        .triggerAsync(eq(AGENT), eq(channelType()), eq(session.sessionId()), any(Runnable.class));
  }

  @Test
  @DisplayName("B6: 推理失败回复可读说明，不静默、不带堆栈")
  void b6ReadableFailure() {
    InboundMessage msg = p2pMessage("fail-1", "问");
    stubSession(msg.userId());
    when(agentService.process(any(), anyString(), anyList()))
        .thenThrow(new IllegalStateException("boom"));

    service.onMessage(msg, replyChannel);

    assertEquals(InboundMessageService.FAILURE_REPLY, replyChannel.sent().get(0).text());
  }

  @Test
  @DisplayName("B7: 不可处理非文本消息回能力说明，不触发推理")
  void b7NonTextualNotice() {
    service.onMessage(nonTextualMessage("img-1"), replyChannel);

    assertEquals(InboundMessageService.UNSUPPORTED_TYPE_REPLY, replyChannel.sent().get(0).text());
    verifyNoInteractions(agentService);
    verify(executionService, never()).triggerAsync(anyString(), anyString(), any(), any());
  }

  @Test
  @DisplayName("B7b: 图片附件消息进入编排")
  void b7ImageAttachmentProcessed() {
    InboundMessage img = imageMessage("img-2");
    Session session = stubSession(img.userId());
    when(agentService.process(eq(session), anyString(), anyList())).thenReturn("看到了图片");

    service.onMessage(img, replyChannel);

    verify(agentService).process(eq(session), anyString(), anyList());
    assertEquals("看到了图片", replyChannel.sent().get(0).text());
  }

  @Test
  @DisplayName("B9: 绑定 Agent 不存在回复「不可用」并落失败执行留痕")
  void b9AgentUnavailable() {
    when(profileRegistry.get(AGENT)).thenReturn(Optional.empty());

    service.onMessage(p2pMessage("ghost-1", "问"), replyChannel);

    assertEquals(InboundMessageService.AGENT_UNAVAILABLE_REPLY, replyChannel.sent().get(0).text());
    verify(executionService).triggerAsync(eq(AGENT), eq(channelType()), any(), any(Runnable.class));
    verifyNoInteractions(agentService);
  }

  @Test
  @DisplayName("B8: 慢推理先收「处理中」提示（时序档见 InboundMessageServiceTest）——此处验证快路径不发提示")
  void b8NoNoticeOnFastPath() throws Exception {
    InboundMessage msg = p2pMessage("fast-1", "问");
    Session session = stubSession(msg.userId());
    when(agentService.process(eq(session), anyString(), anyList())).thenReturn("秒回");

    service.onMessage(msg, replyChannel);
    Thread.sleep(50);

    assertEquals(1, replyChannel.sent().size());
    assertTrue(
        replyChannel.sent().stream()
            .noneMatch(r -> r.text().equals(InboundMessageService.PROCESSING_REPLY)));
  }
}
