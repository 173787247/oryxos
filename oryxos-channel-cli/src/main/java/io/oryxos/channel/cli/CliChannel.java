package io.oryxos.channel.cli;

import io.oryxos.core.agent.AgentService;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;

/**
 * chat 命令的交互通道：读 stdin、写 stdout，维护当前 Session，每行交给引擎，{@code /quit} 退出。
 *
 * <p>CLI 是消息进出的门，不是干活的人——本类没有任何 Agent 智能，就是读—转交—打印的壳（课件骨架）。 channel 字面量 "cli" 只作为三元组参数提供，session_id
 * 拼接在 SessionManager 内部（H4④）。
 *
 * <p>stdin 编码：有 {@link System#console()} 用 console reader；否则 {@link Charset#defaultCharset()}。不硬编码
 * UTF-8。
 */
public class CliChannel {

  private static final String QUIT = "/quit";

  private final AgentService agentService;
  private final SessionManager sessionManager;

  public CliChannel(AgentService agentService, SessionManager sessionManager) {
    this.agentService = agentService;
    this.sessionManager = sessionManager;
  }

  public void run(String profileName, String userId) {
    Session session = sessionManager.getOrCreate("cli", userId, profileName);
    PrintStream out = System.out;
    out.printf("已连接 Agent [%s]，输入 %s 退出。%n", profileName, QUIT);
    BufferedReader in = stdinReader();
    while (true) {
      out.print("> ");
      String line = readLine(in);
      if (line == null || QUIT.equals(line.trim())) {
        // EOF（Ctrl-D / 管道结束）等同退出——不抛堆栈
        out.println("再见。");
        return;
      }
      if (line.isBlank()) {
        continue;
      }
      String reply = agentService.process(session, line);
      out.println(reply);
    }
  }

  /** 可见给单测：解析 stdin 的 reader 选择策略。 */
  static BufferedReader stdinReader() {
    Console console = System.console();
    if (console != null) {
      return new BufferedReader(console.reader());
    }
    return new BufferedReader(new InputStreamReader(System.in, stdinFallbackCharset()));
  }

  /** 无 {@link System#console()} 时的回退编码——跟 JVM 默认，不写死 UTF-8。 */
  static Charset stdinFallbackCharset() {
    return Charset.defaultCharset();
  }

  private static String readLine(BufferedReader in) {
    try {
      return in.readLine();
    } catch (IOException e) {
      throw new UncheckedIOException("读取终端输入失败", e);
    }
  }
}
