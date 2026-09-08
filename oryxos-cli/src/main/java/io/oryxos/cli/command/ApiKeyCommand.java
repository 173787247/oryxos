package io.oryxos.cli.command;

import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.storage.ApiKey;
import io.oryxos.storage.ApiKeyService;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * 重命令组：REST API Key 管理（018-rest-api-key）。每个 leaf 自启 Spring（镜像 {@link UserCommand}），通过 {@code
 * context.getBean(ApiKeyService.class)} 干活。明文 Key 只在 add 成功输出中出现一次，NEVER 落日志（宪法 VI）。
 */
@Command(
    name = "apikey",
    description = "管理 REST API Key（机器调用认证）",
    mixinStandardHelpOptions = true,
    subcommands = {
      ApiKeyCommand.AddCommand.class,
      ApiKeyCommand.ListCommand.class,
      ApiKeyCommand.RevokeCommand.class
    })
public class ApiKeyCommand implements Runnable {

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }

  /** 起一次性 Spring 上下文，跑完即退（镜像 UserCommand.withService）。 */
  private static void withService(java.util.function.Consumer<ApiKeyService> action) {
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(OryxOsRuntime.class)
            .web(WebApplicationType.NONE)
            .bannerMode(Banner.Mode.OFF)
            .run()) {
      action.accept(context.getBean(ApiKeyService.class));
    }
  }

  @Command(name = "add", description = "生成 API Key（明文仅显示这一次）", mixinStandardHelpOptions = true)
  static class AddCommand implements Runnable {
    @Parameters(index = "0", description = "Key 名称（标识调用方，≤64 字符，无空格，全局唯一）")
    String name;

    @Override
    public void run() {
      withService(
          service -> {
            ApiKeyService.CreatedKey created = service.create(name);
            System.out.println("Created API key '" + created.key().getName() + "':");
            System.out.println();
            System.out.println("  " + created.plaintext());
            System.out.println();
            System.out.println("This is the ONLY time the key is displayed. Store it securely.");
          });
    }
  }

  @Command(name = "list", description = "列出 API Key（不显明文）", mixinStandardHelpOptions = true)
  static class ListCommand implements Runnable {
    @Override
    public void run() {
      withService(
          service -> {
            List<ApiKey> keys = service.list();
            if (keys.isEmpty()) {
              System.out.println(
                  "No api keys found. Run 'oryxos apikey add <name>' to create one.");
              return;
            }
            DateTimeFormatter fmt = DateTimeFormatter.ISO_INSTANT.withZone(ZoneId.systemDefault());
            System.out.printf(
                "%-24s %-16s %-9s %-24s %s%n",
                "NAME", "PREFIX", "STATUS", "CREATED_AT", "LAST_USED_AT");
            for (ApiKey key : keys) {
              System.out.printf(
                  "%-24s %-16s %-9s %-24s %s%n",
                  key.getName(),
                  key.getKeyPrefix(),
                  key.isActive() ? "active" : "revoked",
                  key.getCreatedAt() == null ? "-" : fmt.format(key.getCreatedAt()),
                  key.getLastUsedAt() == null ? "-" : fmt.format(key.getLastUsedAt()));
            }
          });
    }
  }

  @Command(name = "revoke", description = "吊销 API Key（即时生效）", mixinStandardHelpOptions = true)
  static class RevokeCommand implements Runnable {
    @Parameters(index = "0", description = "要吊销的 Key 名称")
    String name;

    @Override
    public void run() {
      withService(
          service -> {
            if (service.revoke(name)) {
              System.out.println("Revoked API key '" + name + "'");
            } else {
              System.out.println("API key '" + name + "' is already revoked");
            }
          });
    }
  }
}
