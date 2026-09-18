package io.oryxos.cli.command;

import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.storage.TeamMembershipService;
import java.util.Set;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/** 团队成员管理（#535）：{@code member-add|member-remove|member-list}。无 Admin UI；持久化表由 V12 提供。 */
@Command(
    name = "team",
    description = "管理用户的持久化团队成员关系",
    mixinStandardHelpOptions = true,
    subcommands = {
      TeamCommand.MemberAddCommand.class,
      TeamCommand.MemberRemoveCommand.class,
      TeamCommand.MemberListCommand.class
    })
public class TeamCommand implements Runnable {

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }

  private static void withService(java.util.function.Consumer<TeamMembershipService> action) {
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(OryxOsRuntime.class)
            .web(WebApplicationType.NONE)
            .bannerMode(Banner.Mode.OFF)
            .run()) {
      action.accept(context.getBean(TeamMembershipService.class));
    }
  }

  @Command(name = "member-add", description = "将用户加入团队（幂等）", mixinStandardHelpOptions = true)
  static class MemberAddCommand implements Runnable {
    @Parameters(index = "0", description = "用户名")
    String username;

    @Parameters(index = "1", description = "团队 id（不透明字符串）")
    String teamId;

    @Override
    public void run() {
      withService(
          service -> {
            service.add(username, teamId);
            System.out.println("Added '" + username + "' to team '" + teamId + "'");
          });
    }
  }

  @Command(name = "member-remove", description = "将用户移出团队（幂等）", mixinStandardHelpOptions = true)
  static class MemberRemoveCommand implements Runnable {
    @Parameters(index = "0", description = "用户名")
    String username;

    @Parameters(index = "1", description = "团队 id")
    String teamId;

    @Override
    public void run() {
      withService(
          service -> {
            service.remove(username, teamId);
            System.out.println("Removed '" + username + "' from team '" + teamId + "'");
          });
    }
  }

  @Command(name = "member-list", description = "列出用户的团队 id", mixinStandardHelpOptions = true)
  static class MemberListCommand implements Runnable {
    @Parameters(index = "0", description = "用户名")
    String username;

    @Override
    public void run() {
      withService(
          service -> {
            Set<String> ids = service.listTeamIds(username);
            if (ids.isEmpty()) {
              System.out.println("No team memberships for '" + username + "'");
              return;
            }
            System.out.printf("%-24s %s%n", "USERNAME", "TEAM_ID");
            for (String id : ids) {
              System.out.printf("%-24s %s%n", username, id);
            }
          });
    }
  }
}
