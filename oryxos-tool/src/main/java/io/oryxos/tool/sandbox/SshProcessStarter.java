package io.oryxos.tool.sandbox;

import io.oryxos.core.agent.ToolExecutionContext;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Remote shell via local {@code ssh} CLI (zero new deps). Does not sync workspace paths — remote
 * cwd/path semantics are the operator's responsibility (documented). Fail-loud when host/key
 * missing.
 */
public final class SshProcessStarter implements ProcessStarter {

  private final SshExecutionProperties props;
  private final ProcessStarter localCli;

  public SshProcessStarter(SshExecutionProperties props) {
    this(props, new LocalProcessStarter());
  }

  SshProcessStarter(SshExecutionProperties props, ProcessStarter localCli) {
    this.props = Objects.requireNonNull(props, "props");
    this.localCli = Objects.requireNonNull(localCli, "localCli");
  }

  @Override
  public Process start(List<String> command) throws IOException {
    return start(command, null);
  }

  @Override
  public Process start(List<String> command, Path workingDirectory) throws IOException {
    if (!props.configured()) {
      throw new IOException(
          "ssh execution backend requires oryxos.sandbox.execution.ssh.host (fail-loud; no local fallback)");
    }
    if (props.identityFile().isBlank()) {
      throw new IOException(
          "ssh execution backend requires oryxos.sandbox.execution.ssh.identity-file");
    }
    // workingDirectory is intentionally ignored: remote filesystem is not the local workspace.
    List<String> argv = new ArrayList<>();
    argv.add("ssh");
    argv.add("-i");
    argv.add(props.identityFile());
    argv.add("-p");
    argv.add(Integer.toString(props.port()));
    argv.add("-o");
    argv.add("BatchMode=yes");
    argv.add("-o");
    argv.add("StrictHostKeyChecking=accept-new");
    argv.add(props.user() + "@" + props.host());
    argv.add("--");
    argv.addAll(command);
    ToolExecutionContext.setExecution("ssh", () -> props.user() + "@" + props.host());
    return localCli.start(argv);
  }
}
