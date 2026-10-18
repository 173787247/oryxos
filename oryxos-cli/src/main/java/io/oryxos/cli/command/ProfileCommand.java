package io.oryxos.cli.command;

import io.oryxos.core.agent.AgentLoader;
import io.oryxos.core.agent.AgentStore;
import io.oryxos.core.fs.RealPathBoundary;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * 轻命令组：Agent 目录管理（一个目录 = 一个 Agent，第 29 节）。直接读写 {@code .oryxos/agents/<name>/AGENT.md}， 零
 * Spring（课件：列个目录不值得等 4 秒）。
 *
 * <p>写动作与 API 同口径：删除不物理删——整个目录移进 {@code <root>/archive/}（与 API 的 {@code AgentStore.archive}
 * 同一个归档器，含 MEMORY.md）；create/delete 落盘后递增 {@code workspace_versions} 的 {@code agents} 版本号，集群档靠它让
 * 运行中的副本感知文件面变更（单机档由 WatchService 收敛，见 {@link WorkspaceVersionBump}）。
 */
@Command(
    name = "profile",
    description = "管理 Agent（.oryxos/agents/ 下一个目录一个 Agent）",
    mixinStandardHelpOptions = true,
    subcommands = {
      ProfileCommand.ListCommand.class,
      ProfileCommand.CreateCommand.class,
      ProfileCommand.ShowCommand.class,
      ProfileCommand.DeleteCommand.class
    })
public class ProfileCommand implements Runnable {

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }

  /** Agent 目录区：{@code <工作区根>/agents}（根默认 .oryxos，可用 ORYXOS_ROOT 自定义）。 */
  static Path agentsDir() {
    return Workspace.root().resolve("agents");
  }

  /**
   * Agent 目录的唯一入口：名字必须是一个安全目录段（与 {@link AgentStore} 同一口径），且真实路径必须仍在 {@code agents/} 内。
   *
   * <p>没有这道校验时 {@code profile delete ..} 会把整个工作区根（{@code .oryxos/} 里的 oryxos.db、memory/ 与
   * mcp_servers.yaml）递归删掉，{@code delete ../..} 会删到工作区父目录，{@code create ../../x} 会在任意位置 创建 Agent
   * 目录——delete 对它拿到的路径是真的 {@code Files.walk} 全删。字符集臂挡 {@code ..} 与绝对路径； 真实路径臂挡「agents 下的软链指向根外」。
   */
  static Path safeAgentDir(String name) {
    if (!AgentStore.isSafeName(name)) {
      throw new IllegalArgumentException("非法 Agent 名（只允许字母/数字/下划线/连字符）: " + name);
    }
    Path agents = agentsDir();
    Path dir = agents.resolve(name).normalize();
    try {
      RealPathBoundary.requireWithin(agents, dir);
    } catch (IllegalArgumentException | UncheckedIOException e) {
      throw new IllegalArgumentException("Agent 目录越界（真实路径不在 agents/ 内）: " + name, e);
    }
    return dir;
  }

  static Path agentFile(String name) {
    return safeAgentDir(name).resolve("AGENT.md");
  }

  @Command(name = "list", description = "列出全部 Agent", mixinStandardHelpOptions = true)
  static class ListCommand implements Runnable {
    @Override
    public void run() {
      Path agentsDir = agentsDir();
      if (!Files.isDirectory(agentsDir)) {
        System.out.println("Agent 目录不存在（先跑 oryxos init）。");
        return;
      }
      try (Stream<Path> dirs = Files.list(agentsDir)) {
        dirs.filter(Files::isDirectory).sorted().forEach(p -> System.out.println(p.getFileName()));
      } catch (IOException e) {
        throw new UncheckedIOException("读取 Agent 目录失败", e);
      }
    }
  }

  @Command(
      name = "create",
      description = "创建 Agent（最小 AGENT.md 模板）",
      mixinStandardHelpOptions = true)
  static class CreateCommand implements Runnable {
    @Parameters(index = "0", description = "Agent 名（= 目录名）")
    String name;

    /** 版本号总线写端（集群档靠它感知；单机档无消费者，写入无害）。用例可替换。 */
    WorkspaceVersionBump versionBump = new WorkspaceVersionBump();

    @Override
    public void run() {
      Path dir = safeAgentDir(name);
      Path file = dir.resolve("AGENT.md");
      if (Files.exists(file)) {
        System.out.println("已存在，未覆盖: " + file);
        return;
      }
      String template =
          """
          ---
          name: {name}
          description: 描述这个 Agent 做什么
          identity:
            agent_name: {name}
            prompt: 你是一个乐于助人的助手。
          provider:
            name: deepseek
            model: deepseek-v4-flash
          tools: []
          bootstrap:
            - AGENTS.md
          settings:
            max_iterations: 10
            max_history_turns: 20
          ---

          在这里写这个 Agent 的任务指令（正文）。被触发时它会照做。
          """
              .replace("{name}", name);
      try {
        Files.createDirectories(dir);
        Files.writeString(file, template);
      } catch (IOException e) {
        throw new UncheckedIOException("写入 Agent 失败", e);
      }
      versionBump.bump("agents");
      System.out.println("已创建: " + file);
    }
  }

  @Command(name = "show", description = "查看 Agent 的 AGENT.md", mixinStandardHelpOptions = true)
  static class ShowCommand implements Callable<Integer> {
    @Parameters(index = "0", description = "Agent 名")
    String name;

    @Override
    public Integer call() {
      Path file = agentFile(name);
      if (!Files.exists(file)) {
        System.err.println("Agent 不存在: " + name);
        return 1;
      }
      try {
        long size = Files.size(file);
        if (size > AgentLoader.MAX_AGENT_MD_BYTES) {
          throw new IllegalArgumentException(
              "AGENT.md 过大（"
                  + size
                  + " bytes），上限 "
                  + AgentLoader.MAX_AGENT_MD_BYTES
                  + " bytes（10 MiB）: "
                  + name);
        }
        System.out.println(Files.readString(file));
      } catch (IOException e) {
        throw new UncheckedIOException("读取 Agent 失败", e);
      }
      return 0;
    }
  }

  @Command(
      name = "delete",
      description = "删除 Agent（归档到 .oryxos/archive/，不物理删）",
      mixinStandardHelpOptions = true)
  static class DeleteCommand implements Callable<Integer> {
    @Parameters(index = "0", description = "Agent 名")
    String name;

    /** 版本号总线写端（集群档靠它感知；单机档无消费者，写入无害）。用例可替换。 */
    WorkspaceVersionBump versionBump = new WorkspaceVersionBump();

    @Override
    public Integer call() {
      Path dir = safeAgentDir(name);
      if (!Files.isDirectory(dir)) {
        System.err.println("Agent 不存在: " + name);
        return 1;
      }
      // 与 API 的 AgentLifecycleService.delete 同口径：不物理删，整个目录移进 <root>/archive/（重名加时间戳），
      // 定义可追溯、MEMORY.md 等文件一并保留。注册表/调度由运行中的实例在重载时收敛，CLI 不持有它们。
      new AgentStore(Workspace.root()).archive(name);
      versionBump.bump("agents");
      System.out.println("已删除（已归档保留）: " + name);
      return 0;
    }
  }
}
