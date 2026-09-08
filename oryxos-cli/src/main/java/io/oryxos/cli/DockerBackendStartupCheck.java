package io.oryxos.cli;

import io.oryxos.tool.sandbox.ExecutionBackendProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * docker 执行后端启动校验（024 FR-005，fail loud）：backend=docker 时校验 CLI 存在、daemon 可达、执行镜像可用（缺失则预拉取），任一失败抛
 * {@link IllegalStateException} 阻断启动——「配置了却静默不生效」比启动失败更危险（EC-1/4/7）。 local 档（默认）零检查零开销（SC-001）。
 *
 * <p>放 oryxos-cli（镜像 020 ToolPolicyStartupCheck 的位置理由）：依赖 oryxos-tool 的执行后端配置，由 {@code
 * OryxOsRuntime} 以 {@code @Bean + @ConditionalOnWebApplication(SERVLET)} 装配，仅 serve/gateway 模式运行。
 */
public class DockerBackendStartupCheck implements ApplicationRunner {

  private static final Logger LOG = LoggerFactory.getLogger(DockerBackendStartupCheck.class);

  /** 探测命令输出尾部的截断长度（诊断信息够用即可，防刷屏）。 */
  private static final int DIAGNOSTIC_TAIL = 300;

  /** docker CLI 可执行名（P3C：字面量提常量）。 */
  private static final String DOCKER = "docker";

  /** docker 子命令（P3C：重复字面量提常量）。 */
  private static final String IMAGE_SUBCOMMAND = "image";

  private static final String INSPECT_SUBCOMMAND = "inspect";

  private final ExecutionBackendProperties props;

  /** 探针（单测注入桩；生产实现跑真实 docker CLI）。 */
  private final CommandProbe probe;

  /** 探针：跑一条命令。exit==0 返回 {@code null}（通过）；否则返回错误输出尾部（诊断）；启动失败抛 {@link IOException}。 */
  @FunctionalInterface
  interface CommandProbe {
    String probe(List<String> argv) throws IOException, InterruptedException;
  }

  public DockerBackendStartupCheck(ExecutionBackendProperties props) {
    this(props, DockerBackendStartupCheck::processProbe);
  }

  DockerBackendStartupCheck(ExecutionBackendProperties props, CommandProbe probe) {
    this.props = Objects.requireNonNull(props, "props 不能为空");
    this.probe = Objects.requireNonNull(probe, "probe 不能为空");
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!props.isDocker()) {
      return; // local 档（默认）零检查零开销
    }
    try {
      String cli = probe.probe(List.of(DOCKER, "--version"));
      failIf(cli != null, "docker CLI 不可用（未安装或不在 PATH），无法启用 docker 执行后端。排障: " + s(cli));
      String info = probe.probe(List.of(DOCKER, "info"));
      failIf(info != null, "docker daemon 不可达（docker info 失败），无法启用 docker 执行后端。排障: " + s(info));
      // 镜像不在本地则预拉取（-q 静默进度，输出仅 digest——不会撑爆管道缓冲）
      if (probe.probe(List.of(DOCKER, IMAGE_SUBCOMMAND, INSPECT_SUBCOMMAND, props.image()))
          != null) {
        String pull = probe.probe(List.of(DOCKER, "pull", "-q", props.image()));
        failIf(
            pull != null,
            "执行镜像拉取失败: " + props.image() + "（检查镜像名与网络，或先手动 docker pull）。排障: " + s(pull));
      }
    } catch (IOException e) {
      throw new IllegalStateException("docker 执行后端启动校验失败: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("docker 执行后端启动校验被中断", e);
    }
    LOG.info(
        "docker 执行后端就绪: image={} network={} memory={} cpus={}",
        s(props.image()),
        s(props.network()),
        s(props.memory()),
        s(props.cpus()));
  }

  private static void failIf(boolean condition, String message) {
    if (condition) {
      throw new IllegalStateException(message);
    }
  }

  /** 生产探针：真实跑 docker CLI 子进程（argv 形式、不经 shell；命令与镜像名均为管理员配置，非模型输入）。 */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "COMMAND_INJECTION",
      justification = "argv 全部来自内部常量与管理员配置的镜像名（非模型/用户输入），且以 argv 传给 CLI、不经 shell 解释")
  private static String processProbe(List<String> argv) throws IOException, InterruptedException {
    Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
    byte[] output;
    try (InputStream in = process.getInputStream()) {
      output = in.readAllBytes(); // 探测输出远小于管道缓冲（--version/info/pull -q 均为小输出）
    }
    int exit = process.waitFor();
    if (exit == 0) {
      return null;
    }
    String text = new String(output, StandardCharsets.UTF_8).replace('\r', '_').replace('\n', '_');
    return text.length() > DIAGNOSTIC_TAIL
        ? "…" + text.substring(text.length() - DIAGNOSTIC_TAIL)
        : text;
  }

  /** 日志/异常消息消毒：null 归空串 + 去换行（防日志伪造）。 */
  private static String s(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
