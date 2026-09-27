package io.oryxos.core.task;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Named remote A2A peer for team orchestration ({@code name=baseUrl} entries). */
public record TeamRemotePeer(String name, String baseUrl) {

  public TeamRemotePeer {
    name = Objects.requireNonNull(name, "name").strip();
    baseUrl = Objects.requireNonNull(baseUrl, "baseUrl").strip().replaceAll("/+$", "");
    if (name.isEmpty()) {
      throw new IllegalArgumentException("peer name must not be blank");
    }
    if (baseUrl.isEmpty()) {
      throw new IllegalArgumentException("peer baseUrl must not be blank");
    }
  }

  /**
   * Parse {@code name=url,name2=url2}. Entries without {@code =} are skipped. Duplicate names: last
   * wins (exact name match).
   */
  public static List<TeamRemotePeer> parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    LinkedHashMap<String, TeamRemotePeer> map = new LinkedHashMap<>();
    for (String part : raw.split(",")) {
      String p = part.strip();
      if (p.isEmpty()) {
        continue;
      }
      int eq = p.indexOf('=');
      if (eq <= 0 || eq >= p.length() - 1) {
        continue;
      }
      String n = p.substring(0, eq).strip();
      String u = p.substring(eq + 1).strip();
      if (n.isEmpty() || u.isEmpty()) {
        continue;
      }
      map.put(n, new TeamRemotePeer(n, u));
    }
    return List.copyOf(new ArrayList<>(map.values()));
  }

  /** Resolve peer alias (exact name) or return input if it already looks like a URL. */
  public static String resolveBaseUrl(List<TeamRemotePeer> peers, String remoteOrAlias) {
    if (remoteOrAlias == null || remoteOrAlias.isBlank()) {
      return "";
    }
    String r = remoteOrAlias.strip().replaceAll("/+$", "");
    if (r.contains("://")) {
      return r;
    }
    if (peers == null || peers.isEmpty()) {
      return r;
    }
    for (TeamRemotePeer peer : peers) {
      if (peer.name().equals(r)) {
        return peer.baseUrl();
      }
    }
    return r;
  }
}
