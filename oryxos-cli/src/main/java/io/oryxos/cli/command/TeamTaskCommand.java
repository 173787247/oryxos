package io.oryxos.cli.command;

import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.core.task.TeamTaskOrchestrator;
import io.oryxos.core.task.TeamTaskResult;
import java.util.List;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Direction I CLI：{@code oryxos team-task run|get|list}。与目录 {@code team} 命令正交——本命令管任务编排运行。
 *
 * <p>启动时强制 {@code oryxos.task.team.enabled=true}，以便拿到 {@link TeamTaskOrchestrator} bean。
 */
@Command(
    name = "team-task",
    description = "运行 / 查询 Direction I 团队任务（自然语言 goal → 多 Agent 协作）",
    mixinStandardHelpOptions = true,
    subcommands = {
      TeamTaskCommand.RunCommand.class,
      TeamTaskCommand.GetCommand.class,
      TeamTaskCommand.ListCommand.class
    })
public class TeamTaskCommand implements Runnable {

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }

  static void withOrchestrator(java.util.function.Consumer<TeamTaskOrchestrator> action) {
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(OryxOsRuntime.class)
            .web(WebApplicationType.NONE)
            .bannerMode(Banner.Mode.OFF)
            .properties("oryxos.task.team.enabled=true")
            .run()) {
      TeamTaskOrchestrator orch;
      try {
        orch = context.getBean(TeamTaskOrchestrator.class);
      } catch (NoSuchBeanDefinitionException e) {
        throw new IllegalStateException(
            "TeamTaskOrchestrator 未装配；请确认 oryxos.task.team.enabled=true", e);
      }
      action.accept(orch);
    }
  }

  /** Package-visible for tests. */
  static String formatResult(TeamTaskResult r) {
    StringBuilder sb = new StringBuilder();
    sb.append("id=").append(r.id()).append("\n");
    sb.append("goal=").append(r.goal()).append("\n");
    sb.append("coordinator=").append(r.coordinator()).append("\n");
    sb.append("workers=").append(r.workers().size()).append("\n");
    for (TeamTaskResult.WorkerResult w : r.workers()) {
      sb.append("  - ").append(w.agent());
      if (w.failed()) {
        sb.append(" FAILED: ").append(w.error());
      } else {
        sb.append(" ok");
      }
      sb.append("\n");
    }
    sb.append("summary=").append(r.summary() == null ? "" : r.summary()).append("\n");
    return sb.toString();
  }

  static String formatListRow(TeamTaskResult r) {
    String goal = r.goal() == null ? "" : r.goal();
    if (goal.length() > 48) {
      goal = goal.substring(0, 45) + "...";
    }
    return String.format("%-36s %-16s %s", r.id(), r.coordinator(), goal);
  }

  @Command(name = "run", description = "发布并运行一个团队任务", mixinStandardHelpOptions = true)
  static class RunCommand implements Runnable {
    @Parameters(index = "0", description = "自然语言目标（goal）")
    String goal;

    @Option(names = "--coordinator", description = "协调员 Agent 名（缺省用配置默认）")
    String coordinator;

    @Override
    public void run() {
      withOrchestrator(
          orch -> {
            TeamTaskResult result = orch.run(goal, coordinator);
            System.out.print(formatResult(result));
          });
    }
  }

  @Command(name = "get", description = "按 id 查询已完成的团队任务", mixinStandardHelpOptions = true)
  static class GetCommand implements Runnable {
    @Parameters(index = "0", description = "团队任务 id")
    String id;

    @Override
    public void run() {
      withOrchestrator(
          orch -> {
            TeamTaskResult result =
                orch.find(id)
                    .orElseThrow(() -> new IllegalArgumentException("team-task not found: " + id));
            System.out.print(formatResult(result));
          });
    }
  }

  @Command(name = "list", description = "列出近期团队任务（新→旧）", mixinStandardHelpOptions = true)
  static class ListCommand implements Runnable {
    @Option(names = "--limit", defaultValue = "20", description = "条数上限（默认 20，最大 100）")
    int limit;

    @Override
    public void run() {
      withOrchestrator(
          orch -> {
            List<TeamTaskResult> recent = orch.listRecent(limit);
            if (recent.isEmpty()) {
              System.out.println("No team-task runs yet. Try: oryxos team-task run \"your goal\"");
              return;
            }
            System.out.printf("%-36s %-16s %s%n", "ID", "COORDINATOR", "GOAL");
            for (TeamTaskResult r : recent) {
              System.out.println(formatListRow(r));
            }
          });
    }
  }
}
