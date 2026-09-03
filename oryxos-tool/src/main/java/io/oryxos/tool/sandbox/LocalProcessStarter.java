package io.oryxos.tool.sandbox;

import java.io.IOException;
import java.util.List;

/**
 * local 档执行后端（024 默认档）：{@link ProcessBuilder} 直接派生本地进程——自 ShellTools 默认实现逐字节搬运，行为零变化（SC-001）。docker
 * 档见 DockerProcessStarter（Phase 3）。
 */
public final class LocalProcessStarter implements ProcessStarter {

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "COMMAND_INJECTION",
      justification =
          "以 argv 直接执行、不经 shell 解释；可执行文件在 ShellTools.shell() 启动前经 Sandbox"
              + " 精确白名单校验（自 ShellTools.startProcess 原样搬运，含注解）")
  @Override
  public Process start(List<String> command) throws IOException {
    return new ProcessBuilder(command).start();
  }
}
