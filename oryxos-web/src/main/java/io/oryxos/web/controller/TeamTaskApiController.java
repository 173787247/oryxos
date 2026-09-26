package io.oryxos.web.controller;

import io.oryxos.core.task.TeamTaskOrchestrator;
import io.oryxos.core.task.TeamTaskResult;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.controller.dto.TeamTaskRequest;
import io.oryxos.web.controller.dto.TeamTaskView;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Direction I MVP: natural-language goal → coordinator plan → specialist fan-out. */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification = "Spring MVC injects orchestrator; same pattern as other controllers.")
@RestController
@RequestMapping("/api/v1/team-tasks")
@ConditionalOnBean(TeamTaskOrchestrator.class)
public class TeamTaskApiController {

  private final TeamTaskOrchestrator orchestrator;

  public TeamTaskApiController(TeamTaskOrchestrator orchestrator) {
    this.orchestrator = Objects.requireNonNull(orchestrator);
  }

  @PostMapping
  public ApiResponse<TeamTaskView> create(@RequestBody TeamTaskRequest req) {
    if (req == null || req.goal() == null || req.goal().isBlank()) {
      throw new IllegalArgumentException("goal is required");
    }
    TeamTaskResult result = orchestrator.run(req.goal(), req.coordinator());
    return ApiResponse.ok(TeamTaskView.from(result));
  }
}
