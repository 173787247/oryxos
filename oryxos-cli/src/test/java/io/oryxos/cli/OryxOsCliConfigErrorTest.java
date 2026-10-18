package io.oryxos.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.cli.command.LightDbConfigException;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/** 轻命令配置错误在 CLI 入口的落地形态：stderr 一行信息 + 退出码 1，不打堆栈；其它异常保持 picocli 默认（堆栈 + 默认退出码）。 */
class OryxOsCliConfigErrorTest {

  private static final String CONFIG_ERROR =
      "解析 config/application.yml 失败（第 3 行第 5 列）：mapping values are not allowed"
          + " here；轻命令无法确定数据库，拒绝回落到内置 SQLite。";

  @Test
  @DisplayName("执行期配置错误_退出码 1 且 stderr 只有一行信息、无堆栈")
  void executionPathMapsConfigErrorToStderrAndExitCodeOne() {
    CommandLine commandLine =
        new CommandLine(new ThrowingCommand(new LightDbConfigException(CONFIG_ERROR)))
            .setExecutionExceptionHandler(OryxOsCli::handleExecutionException);
    StringWriter err = new StringWriter();
    commandLine.setErr(new PrintWriter(err, true));

    int exitCode = commandLine.execute();

    assertEquals(1, exitCode, "配置错误必须非零退出");
    assertEquals(CONFIG_ERROR, err.toString().strip(), "stderr 应只给一行可读信息");
    assertFalse(err.toString().contains("\tat "), "不得打印堆栈");
  }

  @Test
  @DisplayName("其它异常保持默认形态（堆栈 + 默认退出码）")
  void otherExceptionsKeepStacktraceAndDefaultExitCode() {
    CommandLine commandLine =
        new CommandLine(new ThrowingCommand(new IllegalStateException("boom")))
            .setExecutionExceptionHandler(OryxOsCli::handleExecutionException);
    StringWriter err = new StringWriter();
    commandLine.setErr(new PrintWriter(err, true));

    int exitCode = commandLine.execute();

    assertEquals(1, exitCode, "默认退出码仍是 1");
    assertTrue(err.toString().contains("IllegalStateException: boom"), err.toString());
    assertTrue(err.toString().contains("\tat "), "非配置错误保持原有堆栈形态");
  }

  /** picocli 只接受带 {@code @Command} 的对象，用探针命令把异常从执行期抛出。 */
  @Command(name = "config-error-probe")
  static class ThrowingCommand implements Runnable {

    private final RuntimeException failure;

    ThrowingCommand(RuntimeException failure) {
      this.failure = failure;
    }

    @Override
    public void run() {
      throw failure;
    }
  }

  @Test
  @DisplayName("入口 CommandLine 已安装配置错误处理者")
  void entryPointInstallsHandler() {
    assertTrue(
        OryxOsCli.commandLine().getExecutionExceptionHandler() != null,
        "入口必须安装自定义执行异常处理者（picocli 未设置时该值为 null）");
  }
}
