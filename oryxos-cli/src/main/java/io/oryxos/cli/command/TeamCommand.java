package io.oryxos.cli.command;

import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.storage.Team;
import io.oryxos.storage.TeamCatalogService;
import io.oryxos.storage.TeamMembershipService;
import java.util.List;
import java.util.Set;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * 团队目录与成员管理（#535 / #539）：{@code create|rename|list|delete} + {@code
 * member-add|member-remove|member-list}。无 Admin UI。
 */
@Command(
    name = "team",
    description = "管理团队目录与用户的持久化团队成员关系",
    mixinStandardHelpOptions = true,
    subcommands = {
      TeamCommand.CreateCommand.class,
      TeamCommand.RenameCommand.class,
      TeamCommand.ListCommand.class,
      TeamCommand.DeleteCommand.class,
      TeamCommand.SetOrgCommand.class,
      TeamCommand.MemberAddCommand.class,
      TeamCommand.MemberRemoveCommand.class,
      TeamCommand.MemberListCommand.class
    })
public class TeamCommand implements Runnable {

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }

  private static void withMembership(java.util.function.Consumer<TeamMembershipService> action) {
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(OryxOsRuntime.class)
            .web(WebApplicationType.NONE)
            .bannerMode(Banner.Mode.OFF)
            .run()) {
      action.accept(context.getBean(TeamMembershipService.class));
    }
  }

  private static void withCatalog(java.util.function.Consumer<TeamCatalogService> action) {
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(OryxOsRuntime.class)
            .web(WebApplicationType.NONE)
            .bannerMode(Banner.Mode.OFF)
            .run()) {
      action.accept(context.getBean(TeamCatalogService.class));
    }
  }

  @Command(name = "create", description = "创建团队目录行", mixinStandardHelpOptions = true)
  static class CreateCommand implements Runnable {
    @Parameters(index = "0", description = "团队 id（不透明字符串，无空格）")
    String teamId;

    @Option(
        names = {"-n", "--name"},
        description = "展示名（缺省=team id）")
    String displayName;

    @Override
    public void run() {
      withCatalog(
          service -> {
            Team t = service.create(teamId, displayName);
            System.out.println("Created team '" + t.getTeamId() + "' (" + t.getDisplayName() + ")");
          });
    }
  }

  @Command(name = "rename", description = "修改团队展示名", mixinStandardHelpOptions = true)
  static class RenameCommand implements Runnable {
    @Parameters(index = "0", description = "团队 id")
    String teamId;

    @Parameters(index = "1", description = "新展示名")
    String displayName;

    @Override
    public void run() {
      withCatalog(
          service -> {
            Team t = service.rename(teamId, displayName);
            System.out.println(
                "Renamed team '" + t.getTeamId() + "' -> '" + t.getDisplayName() + "'");
          });
    }
  }

  @Command(name = "list", description = "列出团队目录", mixinStandardHelpOptions = true)
  static class ListCommand implements Runnable {
    @Override
    public void run() {
      withCatalog(
          service -> {
            List<Team> teams = service.list();
            if (teams.isEmpty()) {
              System.out.println("No teams. Run 'oryxos team create <id>' to add one.");
              return;
            }
            System.out.printf("%-24s %-24s %s%n", "TEAM_ID", "ORG_ID", "DISPLAY_NAME");
            for (Team t : teams) {
              String org = t.getOrgId() == null ? "-" : t.getOrgId();
              System.out.printf("%-24s %-24s %s%n", t.getTeamId(), org, t.getDisplayName());
            }
          });
    }
  }

  @Command(name = "delete", description = "删除团队目录行（不删成员关系）", mixinStandardHelpOptions = true)
  static class DeleteCommand implements Runnable {
    @Parameters(index = "0", description = "团队 id")
    String teamId;

    @Override
    public void run() {
      withCatalog(
          service -> {
            service.delete(teamId);
            System.out.println("Deleted team catalog entry '" + teamId + "'");
          });
    }
  }

  @Command(
      name = "set-org",
      description = "设置团队所属组织（缺省 orgId 则清空）",
      mixinStandardHelpOptions = true)
  static class SetOrgCommand implements Runnable {
    @Parameters(index = "0", description = "团队 id")
    String teamId;

    @Parameters(index = "1", arity = "0..1", description = "组织 id（省略则清空）")
    String orgId;

    @Override
    public void run() {
      withCatalog(
          service -> {
            Team t = service.setOrg(teamId, orgId);
            String org = t.getOrgId() == null ? "(none)" : t.getOrgId();
            System.out.println("Set team '" + t.getTeamId() + "' org -> " + org);
          });
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
      withMembership(
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
      withMembership(
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
      withMembership(
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
