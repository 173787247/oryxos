package io.oryxos.cli;

import io.oryxos.cli.command.AgentCommand;
import io.oryxos.cli.command.ChatCommand;
import io.oryxos.cli.command.GatewayCommand;
import io.oryxos.cli.command.InitCommand;
import io.oryxos.cli.command.LightDbConfigException;
import io.oryxos.cli.command.OrgCommand;
import io.oryxos.cli.command.ProfileCommand;
import io.oryxos.cli.command.ProviderListCommand;
import io.oryxos.cli.command.ServeCommand;
import io.oryxos.cli.command.SessionListCommand;
import io.oryxos.cli.command.StatusCommand;
import io.oryxos.cli.command.TeamCommand;
import io.oryxos.cli.command.TeamTaskCommand;
import io.oryxos.cli.command.ToolListCommand;
import io.oryxos.cli.command.UserCommand;
import io.oryxos.core.profile.ProfileValidationException;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;

/**
 * OryxOS 命令行主入口：整个程序的 main，子命令都挂在这。
 *
 * <p>CLI 是消息进出的门，不是干活的人——参数解析/帮助/报错全交 Picocli，Agent 逻辑全在引擎。
 */
@Command(
    name = "oryxos",
    description = "Enterprise Agent OS — run AI agents on your own infrastructure.",
    mixinStandardHelpOptions = true,
    versionProvider = OryxOsCli.VersionProvider.class,
    subcommands = {
      CommandLine.HelpCommand.class,
      InitCommand.class,
      StatusCommand.class,
      ChatCommand.class,
      ServeCommand.class,
      GatewayCommand.class,
      ProfileCommand.class,
      ProviderListCommand.class,
      ToolListCommand.class,
      SessionListCommand.class,
      io.oryxos.cli.command.KnowledgeCommand.class,
      UserCommand.class,
      OrgCommand.class,
      TeamCommand.class,
      TeamTaskCommand.class,
      io.oryxos.cli.command.ApiKeyCommand.class,
      AgentCommand.class
    })
public class OryxOsCli implements Runnable {

  public static void main(String[] args) {
    System.exit(commandLine().execute(args));
  }

  /** main 与用例共用同一条 CommandLine（含配置错误的落地形态）。 */
  static CommandLine commandLine() {
    return new CommandLine(new OryxOsCli())
        .setExecutionExceptionHandler(OryxOsCli::handleExecutionException);
  }

  /**
   * 用户可纠正的错误落成 stderr 一行信息 + 退出码 1，不打堆栈：
   *
   * <ul>
   *   <li>{@link LightDbConfigException}：轻命令的数据库配置读不出/解析失败（拒绝静默换库）；
   *   <li>{@link IllegalArgumentException} / {@link
   *       ProfileValidationException}：用户输入非法（非法角色、密码不一致或过短、 未知 Agent、非法 Agent
   *       名、AGENT.md/导入源超限、服务层的入参校验……）——与 HTTP 侧 {@code GlobalExceptionHandler} 把这两者映射为 400 是同一判据。
   * </ul>
   *
   * <p>其余异常保持 picocli 默认形态（堆栈 + {@code exitCodeOnExecutionException}）：内部错误要保留调试信息。参数解析错误由 picocli
   * 自己以退出码 2 落账，不经过本方法。
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "INFORMATION_EXPOSURE_THROUGH_AN_ERROR_MESSAGE",
      justification =
          "CLI 把信息打到调用者自己的终端，不是网络响应；消息来自 LightDbConfig（配置路径/YAML 行列）或用户输入校验"
              + "（回显用户自己给的名字/角色等），不含凭证或配置值。")
  static int handleExecutionException(
      Exception exception, CommandLine commandLine, CommandLine.ParseResult parseResult) {
    if (exception instanceof LightDbConfigException configError) {
      commandLine.getErr().println(configError.getMessage());
      commandLine.getErr().flush();
      return 1;
    }
    if (exception instanceof IllegalArgumentException
        || exception instanceof ProfileValidationException) {
      commandLine.getErr().println(exception.getMessage());
      commandLine.getErr().flush();
      return 1;
    }
    exception.printStackTrace(commandLine.getErr());
    commandLine.getErr().flush();
    return commandLine.getCommandSpec().exitCodeOnExecutionException();
  }

  @Override
  public void run() {
    new CommandLine(this).usage(System.out);
  }

  static class VersionProvider implements IVersionProvider {
    private static final String DEFAULT_VERSION = "1.0.0-SNAPSHOT";

    @Override
    public String[] getVersion() {
      String ver = OryxOsCli.class.getPackage().getImplementationVersion();
      if (ver == null) {
        ver = DEFAULT_VERSION;
      }
      return new String[] {
        "OryxOS " + ver,
        "JVM: "
            + System.getProperty("java.version")
            + " ("
            + System.getProperty("java.vendor")
            + ")",
        "OS:  " + System.getProperty("os.name") + " " + System.getProperty("os.version")
      };
    }
  }
}
