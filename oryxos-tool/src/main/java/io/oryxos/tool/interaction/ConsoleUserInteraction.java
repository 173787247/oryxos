package io.oryxos.tool.interaction;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;

/**
 * CLI 场景的交互实现：把问题打到终端、读用户输入的一行。
 *
 * <p>与 chat 循环共用同一终端；因 ReAct 循环同步执行，ask_user 期间的读行不会与对话读行并发争抢。
 *
 * <p>读入编码：有 {@link System#console()} 时走 console reader（跟随控制台编码）；否则用 {@link
 * Charset#defaultCharset()}（Windows 上多为 GBK/GB18030）。禁止再硬编码 UTF-8 解 {@code System.in}——会把本地中文打成乱码。
 */
public class ConsoleUserInteraction implements UserInteraction {

  private final BufferedReader in;
  private final PrintStream out;

  public ConsoleUserInteraction() {
    Console console = System.console();
    if (console != null) {
      this.in = new BufferedReader(console.reader());
      this.out = System.out;
    } else {
      // IDE / 管道 / 无 console 时：跟 JVM 默认 charset（native.encoding），不是 UTF-8 常量
      this.in = new BufferedReader(new InputStreamReader(System.in, Charset.defaultCharset()));
      this.out = System.out;
    }
  }

  /** 测试 / 注入用：按指定 charset 解输入流（生产默认构造见无参）。 */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "交互实现必须持有输出流引用向用户打印问题；流的生命周期由调用方（终端 / 测试）管理")
  public ConsoleUserInteraction(InputStream in, PrintStream out, Charset charset) {
    this.in = new BufferedReader(new InputStreamReader(in, charset));
    this.out = out;
  }

  /** 注入流时跟 JVM 默认 charset（与无 console 的生产回退一致）。需要 UTF-8 fixture 请用三参构造。 */
  public ConsoleUserInteraction(InputStream in, PrintStream out) {
    this(in, out, Charset.defaultCharset());
  }

  @Override
  public String ask(String question) {
    out.println("[Agent 提问] " + question);
    out.print("> ");
    try {
      String line = in.readLine();
      if (line == null) {
        throw new InteractionUnavailableException("输入流已结束，无法获取用户回答");
      }
      return line;
    } catch (IOException e) {
      throw new UncheckedIOException("读取用户回答失败", e);
    }
  }
}
