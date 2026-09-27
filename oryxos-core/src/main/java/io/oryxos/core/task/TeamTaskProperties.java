package io.oryxos.core.task;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code oryxos.task.team.*} — Direction I switches. */
@ConfigurationProperties(prefix = "oryxos.task.team")
public record TeamTaskProperties(
    boolean enabled,
    String coordinator,
    int maxSubtasks,
    @DefaultValue("true") boolean parallel,
    @DefaultValue("true") boolean replanOnFailure,
    @DefaultValue("1") int maxReplanRounds,
    /**
     * Comma-separated remote peers {@code name=http://host:port} advertised to the coordinator and
     * usable as {@code remote} aliases in plan JSON.
     */
    @DefaultValue("") String remotePeers) {

  public TeamTaskProperties {
    coordinator =
        coordinator == null || coordinator.isBlank() ? "coordinator" : coordinator.strip();
    maxSubtasks = maxSubtasks <= 0 ? 4 : Math.min(maxSubtasks, 16);
    maxReplanRounds = maxReplanRounds <= 0 ? 0 : Math.min(maxReplanRounds, 3);
    remotePeers = remotePeers == null ? "" : remotePeers.strip();
  }

  public static TeamTaskProperties disabled() {
    return new TeamTaskProperties(false, "coordinator", 4, true, true, 1, "");
  }

  public List<TeamRemotePeer> parsedRemotePeers() {
    return TeamRemotePeer.parse(remotePeers);
  }
}
