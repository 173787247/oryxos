package io.oryxos.web.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxos.core.durable.ApprovalInteractionService;
import io.oryxos.core.durable.ApprovalInteractionService.ApprovalDetailView;
import io.oryxos.core.durable.ApprovalInteractionService.DecisionOutcome;
import io.oryxos.core.durable.ApprovalInteractionService.DecisionRequest;
import io.oryxos.core.durable.ApprovalInteractionService.PendingApprovalView;
import io.oryxos.core.durable.TaskCheckpoint;
import io.oryxos.core.policy.ApprovalPolicyProperties;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.error.ResourceNotFoundException;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审批交互 HTTP API（044 / #466）：管理台列表/详情/决策 + 飞书/企微统一回调薄入口。
 *
 * <p>flag {@code oryxos.approval.interaction-api-enabled} 默认关 → 404。回调路径不验签（薄 stub）；生产需渠道验签跟进。
 */
@SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification = "审批交互是有意暴露的治理端点（#466）；service/properties 为 Spring 注入共享单例。")
@RestController
@RequestMapping("/api/v1/approvals")
public class ApprovalInteractionApiController {

  private static final String MSG_DISABLED = "approval interaction api disabled";

  private final ApprovalPolicyProperties properties;
  private final ApprovalInteractionService interaction;

  public ApprovalInteractionApiController(
      ApprovalPolicyProperties properties, ApprovalInteractionService interaction) {
    this.properties = properties;
    this.interaction = interaction;
  }

  @GetMapping
  public ApiResponse<List<PendingApprovalView>> listWaiting() {
    requireEnabled();
    return ApiResponse.ok(interaction.listWaiting());
  }

  @GetMapping("/{checkpointId}")
  public ApiResponse<ApprovalDetailView> detail(@PathVariable("checkpointId") String checkpointId) {
    requireEnabled();
    return ApiResponse.ok(
        interaction
            .detail(checkpointId)
            .orElseThrow(() -> new ResourceNotFoundException("checkpoint not found")));
  }

  @PostMapping("/{checkpointId}/decide")
  public ApiResponse<DecisionView> decide(
      @PathVariable("checkpointId") String checkpointId, @RequestBody DecideBody body) {
    requireEnabled();
    DecisionRequest req =
        new DecisionRequest(
            checkpointId,
            body != null && body.approved(),
            body == null ? null : body.actor(),
            body == null ? null : body.comment(),
            body == null ? null : body.argumentsJson());
    return ApiResponse.ok(DecisionView.from(interaction.decide(req)));
  }

  /** 飞书/企微统一回调（薄 stub）：{@code channel} 为 {@code feishu}|{@code wecom}；靠 callbackId 去重。 */
  @PostMapping("/callbacks/{channel}")
  public ApiResponse<DecisionView> callback(
      @PathVariable("channel") String channel, @RequestBody CallbackBody body) {
    requireEnabled();
    if (body == null || body.callbackId() == null || body.callbackId().isBlank()) {
      throw new IllegalArgumentException("callbackId required");
    }
    DecisionRequest req =
        new DecisionRequest(
            body.checkpointId(),
            body.approved(),
            body.actor(),
            body.comment(),
            body.argumentsJson());
    return ApiResponse.ok(
        DecisionView.from(interaction.handleCallback(channel, body.callbackId(), req)));
  }

  private void requireEnabled() {
    if (properties == null || !properties.isInteractionApiEnabled()) {
      throw new ResourceNotFoundException(MSG_DISABLED);
    }
  }

  /** 管理台决策体。 */
  public record DecideBody(boolean approved, String actor, String comment, String argumentsJson) {}

  /** IM 回调体。 */
  public record CallbackBody(
      String callbackId,
      String checkpointId,
      boolean approved,
      String actor,
      String comment,
      String argumentsJson) {}

  /** 决策响应（含回放所需的状态/结果摘要）。 */
  public record DecisionView(
      String checkpointId,
      String state,
      boolean approvedPath,
      boolean alreadyDone,
      boolean expired,
      boolean duplicate,
      String lastError,
      String toolOutput,
      String message) {

    static DecisionView from(DecisionOutcome outcome) {
      TaskCheckpoint cp = outcome.checkpoint();
      String out =
          outcome.toolResult() == null
              ? null
              : (outcome.toolResult().success()
                  ? outcome.toolResult().content()
                  : outcome.toolResult().errorMessage());
      return new DecisionView(
          cp == null ? null : cp.id(),
          cp == null ? null : cp.state().name(),
          outcome.toolResult() != null,
          outcome.alreadyDone(),
          outcome.expired(),
          outcome.duplicate(),
          cp == null ? null : cp.lastError(),
          out,
          outcome.message());
    }
  }
}
