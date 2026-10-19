package io.oryxos.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.oryxos.core.profile.ProfileValidationException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * CLI 的「用户输入非法」错误形态：stderr 一行 + 退出码 1、不打堆栈；内部错误仍打堆栈；参数解析错仍是 picocli 的退出码 2。
 *
 * <p>判据与 HTTP 侧一致：{@code GlobalExceptionHandler} 把 {@link IllegalArgumentException} 与 {@link
 * ProfileValidationException} 映射为 400 + 一行，CLI 把同一家族映射为一行 + 1。
 */
class OryxOsCliInputErrorTest {

  @Test
  @DisplayName("非法角色_退出码1_stderr一行_无堆栈")
  void invalidRoleIsOneLine() {
    Result result = run("user", "role", "alice", "SUPERADMIN");

    assertThat(result.code()).isEqualTo(1);
    assertThat(result.stderr().strip())
        .isEqualTo("invalid role 'SUPERADMIN' (expected VIEWER|EDITOR|ADMIN)");
    assertThat(result.stderr()).doesNotContain("\tat ");
    assertThat(result.stdout()).isEmpty();
  }

  @Test
  @DisplayName("无交互终端_退出码1_stderr一行_无堆栈")
  void missingConsoleIsOneLine() {
    assumeTrue(System.console() == null, "需要无 tty 的测试环境（surefire 常态）");

    Result result = run("user", "add", "alice");

    assertThat(result.code()).isEqualTo(1);
    assertThat(result.stderr().strip())
        .isEqualTo("no interactive console available (stdin piped?)");
    assertThat(result.stderr()).doesNotContain("\tat ");
    assertThat(result.stdout()).isEmpty();
  }

  @Test
  @DisplayName("密码不一致/过短_落成一行无堆栈（handler 形态）")
  void passwordValidationErrorsAreOneLine() {
    for (String message :
        new String[] {"passwords do not match", "password must be at least 8 characters"}) {
      Result result = runProbe(new IllegalArgumentException(message));

      assertThat(result.code()).isEqualTo(1);
      assertThat(result.stderr().strip()).isEqualTo(message);
      assertThat(result.stderr()).doesNotContain("\tat ");
      assertThat(result.stdout()).isEmpty();
    }
  }

  @Test
  @DisplayName("ProfileValidationException_落成一行（与 HTTP 400 同判据）")
  void profileValidationIsOneLine() {
    Result result = runProbe(new ProfileValidationException("Profile 缺少 name 字段: a.md"));

    assertThat(result.code()).isEqualTo(1);
    assertThat(result.stderr().strip()).isEqualTo("Profile 缺少 name 字段: a.md");
    assertThat(result.stderr()).doesNotContain("\tat ");
  }

  @Test
  @DisplayName("真正的内部错误仍打堆栈（回归：没把调试信息全吃掉）")
  void internalErrorsKeepStacktrace() {
    Result illegalState = runProbe(new IllegalStateException("boom"));
    assertThat(illegalState.code()).isEqualTo(1);
    assertThat(illegalState.stderr()).contains("IllegalStateException: boom").contains("\tat ");

    Result ioFailure = runProbe(new UncheckedIOException("读取失败", new java.io.IOException("disk")));
    assertThat(ioFailure.code()).isEqualTo(1);
    assertThat(ioFailure.stderr()).contains("UncheckedIOException").contains("\tat ");
  }

  @Test
  @DisplayName("缺参数仍由 picocli 以退出码 2 落账（执行期退出码不变）")
  void missingParametersStayExitCodeTwo() {
    Result result = run("user", "role");

    assertThat(result.code()).isEqualTo(2);
  }

  private static Result run(String... args) {
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();
    CommandLine commandLine = OryxOsCli.commandLine();
    commandLine.setOut(new PrintWriter(out, true));
    commandLine.setErr(new PrintWriter(err, true));
    int code = commandLine.execute(args);
    return new Result(code, out.toString(), err.toString());
  }

  private static Result runProbe(RuntimeException failure) {
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();
    CommandLine commandLine =
        new CommandLine(new FailingCommand(failure))
            .setExecutionExceptionHandler(OryxOsCli::handleExecutionException);
    commandLine.setOut(new PrintWriter(out, true));
    commandLine.setErr(new PrintWriter(err, true));
    int code = commandLine.execute();
    return new Result(code, out.toString(), err.toString());
  }

  /** picocli 只接受带 {@code @Command} 的对象：用它把指定异常从执行期抛出。 */
  @Command(name = "cli-input-error-probe")
  static class FailingCommand implements Runnable {

    private final RuntimeException failure;

    FailingCommand(RuntimeException failure) {
      this.failure = failure;
    }

    @Override
    public void run() {
      throw failure;
    }
  }

  private record Result(int code, String stdout, String stderr) {}
}
