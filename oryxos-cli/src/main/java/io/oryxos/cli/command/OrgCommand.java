package io.oryxos.cli.command;

import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.storage.Organization;
import io.oryxos.storage.OrganizationCatalogService;
import java.util.List;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** 组织目录管理（#554）：{@code create|rename|list|delete}。无 Admin UI。 */
@Command(
    name = "org",
    description = "管理组织目录（可选元数据；不驱动授权）",
    mixinStandardHelpOptions = true,
    subcommands = {
      OrgCommand.CreateCommand.class,
      OrgCommand.RenameCommand.class,
      OrgCommand.ListCommand.class,
      OrgCommand.DeleteCommand.class
    })
public class OrgCommand implements Runnable {

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }

  private static void withCatalog(java.util.function.Consumer<OrganizationCatalogService> action) {
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(OryxOsRuntime.class)
            .web(WebApplicationType.NONE)
            .bannerMode(Banner.Mode.OFF)
            .run()) {
      action.accept(context.getBean(OrganizationCatalogService.class));
    }
  }

  @Command(name = "create", description = "创建组织目录行", mixinStandardHelpOptions = true)
  static class CreateCommand implements Runnable {
    @Parameters(index = "0", description = "组织 id（不透明字符串，无空格）")
    String orgId;

    @Option(
        names = {"-n", "--name"},
        description = "展示名（缺省=org id）")
    String displayName;

    @Override
    public void run() {
      withCatalog(
          service -> {
            Organization o = service.create(orgId, displayName);
            System.out.println("Created org '" + o.getOrgId() + "' (" + o.getDisplayName() + ")");
          });
    }
  }

  @Command(name = "rename", description = "修改组织展示名", mixinStandardHelpOptions = true)
  static class RenameCommand implements Runnable {
    @Parameters(index = "0", description = "组织 id")
    String orgId;

    @Parameters(index = "1", description = "新展示名")
    String displayName;

    @Override
    public void run() {
      withCatalog(
          service -> {
            Organization o = service.rename(orgId, displayName);
            System.out.println(
                "Renamed org '" + o.getOrgId() + "' -> '" + o.getDisplayName() + "'");
          });
    }
  }

  @Command(name = "list", description = "列出组织目录", mixinStandardHelpOptions = true)
  static class ListCommand implements Runnable {
    @Override
    public void run() {
      withCatalog(
          service -> {
            List<Organization> orgs = service.list();
            if (orgs.isEmpty()) {
              System.out.println("No orgs. Run 'oryxos org create <id>' to add one.");
              return;
            }
            System.out.printf("%-24s %s%n", "ORG_ID", "DISPLAY_NAME");
            for (Organization o : orgs) {
              System.out.printf("%-24s %s%n", o.getOrgId(), o.getDisplayName());
            }
          });
    }
  }

  @Command(
      name = "delete",
      description = "删除组织目录行（清空 teams.org_id）",
      mixinStandardHelpOptions = true)
  static class DeleteCommand implements Runnable {
    @Parameters(index = "0", description = "组织 id")
    String orgId;

    @Override
    public void run() {
      withCatalog(
          service -> {
            service.delete(orgId);
            System.out.println("Deleted org catalog entry '" + orgId + "'");
          });
    }
  }
}
