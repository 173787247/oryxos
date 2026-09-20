package io.oryxos.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.durable.ApprovalInteractionService;
import io.oryxos.core.durable.ApprovalInteractionService.DecisionOutcome;
import io.oryxos.core.durable.ApprovalInteractionService.PendingApprovalView;
import io.oryxos.core.durable.DurableTaskState;
import io.oryxos.core.durable.TaskCheckpoint;
import io.oryxos.core.policy.ApprovalPolicyProperties;
import io.oryxos.web.GlobalExceptionHandler;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 044 / #466 审批交互 HTTP：flag 关 404；开时 list/decide/callback。 */
class ApprovalInteractionApiControllerTest {

  private MockMvc mvc;
  private ApprovalPolicyProperties properties;
  private ApprovalInteractionService interaction;

  @BeforeEach
  void setUp() {
    properties = new ApprovalPolicyProperties();
    interaction = mock(ApprovalInteractionService.class);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new ApprovalInteractionApiController(properties, interaction))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("flag关_list_404")
  void flagOff_list_404() throws Exception {
    properties.setInteractionApiEnabled(false);
    mvc.perform(get("/api/v1/approvals")).andExpect(status().isNotFound());
    verify(interaction, never()).listWaiting();
  }

  @Test
  @DisplayName("flag开_list")
  void flagOn_list() throws Exception {
    properties.setInteractionApiEnabled(true);
    when(interaction.listWaiting())
        .thenReturn(
            List.of(
                new PendingApprovalView(
                    "cp-1",
                    "s1",
                    "a1",
                    "shell",
                    "c1",
                    "{}",
                    "1",
                    "r1",
                    60,
                    Instant.parse("2026-09-20T05:00:00Z"),
                    false,
                    Instant.parse("2026-09-20T04:00:00Z"))));
    mvc.perform(get("/api/v1/approvals"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].checkpointId").value("cp-1"))
        .andExpect(jsonPath("$.data[0].toolName").value("shell"));
  }

  @Test
  @DisplayName("flag开_decide")
  void flagOn_decide() throws Exception {
    properties.setInteractionApiEnabled(true);
    TaskCheckpoint cp =
        new TaskCheckpoint(
            "cp-1",
            null,
            "s1",
            "a1",
            DurableTaskState.SUCCEEDED,
            "k",
            TaskCheckpoint.KIND_PRE_TOOL_APPROVAL,
            "shell",
            "c1",
            "{}",
            "1",
            "r1",
            0,
            null,
            60,
            Instant.parse("2026-09-20T05:00:00Z"),
            Instant.parse("2026-09-20T04:00:00Z"),
            Instant.parse("2026-09-20T04:01:00Z"));
    when(interaction.decide(any()))
        .thenReturn(new DecisionOutcome(cp, null, false, false, false, null));
    mvc.perform(
            post("/api/v1/approvals/cp-1/decide")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"approved\":true,\"actor\":\"admin\",\"comment\":\"ok\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.checkpointId").value("cp-1"))
        .andExpect(jsonPath("$.data.state").value("SUCCEEDED"));
  }

  @Test
  @DisplayName("flag开_feishu回调")
  void flagOn_callback() throws Exception {
    properties.setInteractionApiEnabled(true);
    TaskCheckpoint cp =
        new TaskCheckpoint(
            "cp-1",
            null,
            "s1",
            "a1",
            DurableTaskState.CANCELLED,
            "k",
            TaskCheckpoint.KIND_PRE_TOOL_APPROVAL,
            "shell",
            "c1",
            "{}",
            "1",
            "r1",
            0,
            "denied",
            60,
            null,
            Instant.parse("2026-09-20T04:00:00Z"),
            Instant.parse("2026-09-20T04:01:00Z"));
    when(interaction.handleCallback(eq("feishu"), eq("cb-9"), any()))
        .thenReturn(new DecisionOutcome(cp, null, false, false, false, null));
    mvc.perform(
            post("/api/v1/approvals/callbacks/feishu")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"callbackId\":\"cb-9\",\"checkpointId\":\"cp-1\",\"approved\":false,\"actor\":\"u1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.state").value("CANCELLED"));
  }
}
