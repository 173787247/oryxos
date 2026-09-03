package io.oryxos.tool.builtin;

import io.oryxos.tool.sandbox.ActionType;
import io.oryxos.tool.sandbox.LocalProcessStarter;
import io.oryxos.tool.sandbox.ProcessStarter;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxAction;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 内置命令工具：直接执行获准的可执行文件，带超时兜底——命令挂死不能拖死整个 ReAct 循环。
 *
 * <p>白名单校验可执行文件名（SHELL_COMMAND 检查位已过 enforce）；参数作为 argv 直接传给进程，不经 Shell 解释。超时默认 30 秒。两个运维细节： (1)
 * stdout/stderr 在 {@code waitFor} 前就并发排空——否则输出超过管道缓冲（~64KB）的命令会写阻塞、被误判超时；(2)
 * 超时后递归杀进程树——子进程不在父进程组内时，只杀父进程会留孤儿继续跑。
 *
 * <p>子进程输出按 {@link Charset#defaultCharset()} 解码（与本地控制台/CLI 代码页一致）。中文 Windows 上 {@code dir}/{@code
 * type} 等常按 GBK/CP936 吐字节；硬编码 UTF-8 会把合法输出解成乱码。单测可注入 Charset。
 */
public class ShellTools {

  /** 默认超时：30 秒。 */
  static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  /**
   * 输出保留上限：stdout/stderr 全文会原样回灌模型上下文，白名单内命令（如 cat 大日志）可无界输出撑爆内存与 context
   * window。超限部分**继续排空但丢弃**——停止读取会让子进程管道写阻塞、被误判超时。
   */
  static final int MAX_OUTPUT_BYTES = 64 * 1024;

  /** 排空子进程输出的虚拟线程执行器（宪法 VII）：流读取是 IO 等待，虚拟线程天然适配。 */
  @SuppressWarnings("PMD.ThreadPoolCreationRule") // Java 21 虚拟线程无池参数可配，非 P3C 针对的固定线程池反模式。
  private static final ExecutorService DRAINER = Executors.newVirtualThreadPerTaskExecutor();

  private final Sandbox sandbox;
  private final Duration timeout;
  private final ProcessStarter processStarter;
  private final Charset outputCharset;

  public ShellTools(Sandbox sandbox) {
    this(sandbox, DEFAULT_TIMEOUT);
  }

  /** 装配层注入执行后端（024）：local 档传 LocalProcessStarter、docker 档传 DockerProcessStarter。 */
  public ShellTools(Sandbox sandbox, ProcessStarter processStarter) {
    this(sandbox, DEFAULT_TIMEOUT, processStarter, Charset.defaultCharset());
  }

  ShellTools(Sandbox sandbox, Duration timeout) {
    this(sandbox, timeout, new LocalProcessStarter(), Charset.defaultCharset());
  }

  ShellTools(Sandbox sandbox, Duration timeout, ProcessStarter processStarter) {
    this(sandbox, timeout, processStarter, Charset.defaultCharset());
  }

  ShellTools(
      Sandbox sandbox, Duration timeout, ProcessStarter processStarter, Charset outputCharset) {
    this.sandbox = Objects.requireNonNull(sandbox, "sandbox 不能为空");
    this.timeout = Objects.requireNonNull(timeout, "timeout 不能为空");
    this.processStarter = Objects.requireNonNull(processStarter, "processStarter 不能为空");
    this.outputCharset = Objects.requireNonNull(outputCharset, "outputCharset 不能为空");
  }

  @Tool(name = "shell", description = "执行一个已获许可的可执行文件，返回标准输出")
  public String shell(
      @ToolParam(description = "要执行的、已在白名单中的可执行文件") String executable,
      @ToolParam(description = "传给可执行文件的独立参数数组，不支持 shell 语法") List<String> arguments) {
    String commandExecutable = requireExecutable(executable);
    List<String> command = command(commandExecutable, arguments);
    sandbox.enforce(new SandboxAction(ActionType.SHELL_COMMAND, commandExecutable));
    try {
      Process process = processStarter.start(command);
      // 先起并发排空再 waitFor：管道不被写满阻塞，waitFor 只在「命令真没跑完」时超时
      Future<BoundedOutput> stdout = DRAINER.submit(() -> drainBounded(process.getInputStream()));
      Future<BoundedOutput> stderr = DRAINER.submit(() -> drainBounded(process.getErrorStream()));
      boolean finished;
      try {
        finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        killTree(process);
        throw new IllegalStateException("命令执行被中断: " + commandExecutable, e);
      }
      if (!finished) {
        killTree(process);
        throw new IllegalStateException(
            "命令超时（" + timeout.toSeconds() + "s）被终止: " + commandExecutable);
      }
      try {
        BoundedOutput err = stderr.get();
        if (process.exitValue() != 0) {
          String errText = new String(err.retained, outputCharset).trim();
          throw new IllegalStateException(
              "命令退出码 " + process.exitValue() + ": " + errText + truncationNote(err));
        }
        BoundedOutput out = stdout.get();
        return new String(out.retained, outputCharset) + truncationNote(out);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("命令执行被中断: " + commandExecutable, e);
      } catch (ExecutionException e) {
        throw new IllegalStateException("命令输出读取失败: " + commandExecutable, e.getCause());
      }
    } catch (IOException e) {
      throw new UncheckedIOException("命令启动失败: " + commandExecutable, e);
    }
  }

  private static String requireExecutable(String executable) {
    if (executable == null || executable.isBlank()) {
      throw new IllegalArgumentException("可执行文件不能为空");
    }
    return executable.strip();
  }

  private static List<String> command(String executable, List<String> arguments) {
    List<String> command = new ArrayList<>();
    command.add(executable);
    if (arguments != null) {
      for (String argument : arguments) {
        if (argument == null) {
          throw new IllegalArgumentException("命令参数不能为 null");
        }
        command.add(argument);
      }
    }
    return List.copyOf(command);
  }

  /** 先递归杀命令派生的子孙进程，再杀命令本身（只 destroyForcibly 主进程会留孤儿继续执行）。 */
  private static void killTree(Process process) {
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
  }

  /** 有界排空：全程读取（保管道不阻塞），只保留前 {@link #MAX_OUTPUT_BYTES} 字节。截断点可能切开多字节字符， 解码时落为替换符——尾部紧邻截断标注，可接受。 */
  private static BoundedOutput drainBounded(InputStream in) throws IOException {
    ByteArrayOutputStream retained = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    boolean truncated = false;
    int n;
    while ((n = in.read(buffer)) != -1) {
      if (truncated) {
        continue; // 已超限：继续排空但丢弃
      }
      int room = MAX_OUTPUT_BYTES - retained.size();
      if (n <= room) {
        retained.write(buffer, 0, n);
      } else {
        retained.write(buffer, 0, room);
        truncated = true;
      }
    }
    return new BoundedOutput(retained.toByteArray(), truncated);
  }

  private static String truncationNote(BoundedOutput output) {
    return output.truncated ? "\n…（输出超过 " + (MAX_OUTPUT_BYTES / 1024) + "KB，已截断）" : "";
  }

  /** 一路输出（stdout 或 stderr）的保留结果。 */
  private static final class BoundedOutput {
    private final byte[] retained;
    private final boolean truncated;

    private BoundedOutput(byte[] retained, boolean truncated) {
      this.retained = retained;
      this.truncated = truncated;
    }
  }
}
