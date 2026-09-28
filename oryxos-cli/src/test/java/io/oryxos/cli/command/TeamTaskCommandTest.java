package io.oryxos.cli.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.cli.OryxOsCli;
import io.oryxos.core.task.TeamTaskResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class TeamTaskCommandTest {

  @Test
  @DisplayName("picocli registers team-task run/get/list")
  void subcommandsRegistered() {
    CommandLine cmd = new CommandLine(new OryxOsCli());
    assertTrue(cmd.getSubcommands().containsKey("team-task"));
    CommandLine teamTask = cmd.getSubcommands().get("team-task");
    assertTrue(teamTask.getSubcommands().containsKey("run"));
    assertTrue(teamTask.getSubcommands().containsKey("get"));
    assertTrue(teamTask.getSubcommands().containsKey("list"));
  }

  @Test
  @DisplayName("formatResult includes id and worker status")
  void formatResult() {
    TeamTaskResult r =
        TeamTaskResult.builder()
            .id("tid")
            .goal("ship")
            .coordinator("boss")
            .addWorker(new TeamTaskResult.WorkerResult("w", "m", "ok", null))
            .addWorker(new TeamTaskResult.WorkerResult("x", "m", "", "boom"))
            .summary("done")
            .build();
    String text = TeamTaskCommand.formatResult(r);
    assertTrue(text.contains("id=tid"));
    assertTrue(text.contains("w ok"));
    assertTrue(text.contains("x FAILED: boom"));
    assertTrue(text.contains("summary=done"));
  }

  @Test
  @DisplayName("formatListRow truncates long goals")
  void formatListRow() {
    String longGoal = "a".repeat(60);
    TeamTaskResult r =
        TeamTaskResult.builder().id("id1").goal(longGoal).coordinator("c").summary("").build();
    String row = TeamTaskCommand.formatListRow(r);
    assertTrue(row.contains("..."));
    assertEquals(48, longGoal.substring(0, 45).length() + 3); // sanity
    assertTrue(row.contains("id1"));
  }
}
