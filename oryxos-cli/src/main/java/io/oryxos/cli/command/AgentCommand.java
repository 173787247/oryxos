package io.oryxos.cli.command;

import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.core.OryxTool;
import io.oryxos.core.agent.AgencyAgentsImporter;
import io.oryxos.core.agent.AgencyAgentsParser;
import io.oryxos.core.agent.AgencyAgentsParser.ParsedExpert;
import io.oryxos.core.agent.AgentLifecycleService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * 重命令组：Agent 导入（025）。leaf 自启 Spring（镜像 {@link ChatCommand}），通过 {@link
 * AgentLifecycleService#importAgent} 落盘——导入要真实校验链，不能像 init/status/profile 那样零 Spring。
 */
@Command(
    name = "agent",
    description = "Agent 管理（import：从 agency-agents-zh 人格库导入专家）",
    mixinStandardHelpOptions = true,
    subcommands = {AgentCommand.ImportCommand.class})
public class AgentCommand implements Runnable {

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }

  @Command(
      name = "import",
      description = "导入一个 agency-agents-zh 专家 .md 为 Agent（.oryxos/agents/<slug>/）",
      mixinStandardHelpOptions = true)
  static class ImportCommand implements Runnable {
    @Parameters(index = "0", description = "agency-agents-zh 源文件路径（.md）")
    String sourcePath;

    @Option(names = "--name", description = "Agent 名（缺省用文件名 slug）")
    String name;

    @Override
    public void run() {
      try (ConfigurableApplicationContext context =
          new SpringApplicationBuilder(OryxOsRuntime.class)
              .web(WebApplicationType.NONE)
              .bannerMode(Banner.Mode.OFF)
              .run()) {
        ChatCommand.validateProviderRegistry(context); // 复用 chat 的 provider 校验（同包 package-private）
        AgentLifecycleService service = context.getBean(AgentLifecycleService.class);

        Path src = Path.of(sourcePath);
        ParsedExpert expert = new AgencyAgentsParser().parse(readString(src));
        String agentName = (name != null && !name.isBlank()) ? name.strip() : slug(src);
        if (agentName.isEmpty()) {
          throw new IllegalArgumentException("无法从源文件名派生 Agent 名，请用 --name 显式指定");
        }
        @SuppressWarnings("unchecked")
        Map<String, OryxTool> tools =
            context.getBean("tools", Map.class); // tools bean（OryxOsRuntime）

        String rendered =
            new AgencyAgentsImporter()
                .toMarkdown(expert, service.defaultProvider(), tools.keySet(), agentName, null);
        service.importAgent(agentName, rendered);
        System.out.println("已导入: " + agentName + " ← " + src.getFileName());
      }
    }
  }

  private static String readString(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException e) {
      throw new UncheckedIOException("读取源文件失败: " + path, e);
    }
  }

  /**
   * 文件名 slug → Agent 目录名（对齐 {@code AgentStore.safe} 的 {@code [A-Za-z0-9_-]+}）：software-architect.md
   * → software-architect。
   */
  private static String slug(Path path) {
    Path fileName = path.getFileName();
    String f =
        fileName == null ? "" : fileName.toString(); // 路径无元素时 getFileName 返回 null，空 slug 由调用方拒绝
    int dot = f.lastIndexOf('.');
    String base = dot > 0 ? f.substring(0, dot) : f;
    return base.replaceAll("[^A-Za-z0-9_-]", "");
  }
}
