package io.oryxos.core.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TeamRemotePeerTest {

  @Test
  @DisplayName("parse name=url entries")
  void parse() {
    List<TeamRemotePeer> peers =
        TeamRemotePeer.parse("edge=http://10.0.0.5:8080/, lab=http://lab:9090");
    assertEquals(2, peers.size());
    assertEquals("edge", peers.get(0).name());
    assertEquals("http://10.0.0.5:8080", peers.get(0).baseUrl());
  }

  @Test
  @DisplayName("resolve alias to URL")
  void resolveAlias() {
    List<TeamRemotePeer> peers = TeamRemotePeer.parse("edge=http://10.0.0.5:8080");
    assertEquals("http://10.0.0.5:8080", TeamRemotePeer.resolveBaseUrl(peers, "edge"));
    assertEquals("http://other:1", TeamRemotePeer.resolveBaseUrl(peers, "http://other:1/"));
  }

  @Test
  @DisplayName("blank raw yields empty")
  void blank() {
    assertTrue(TeamRemotePeer.parse("").isEmpty());
    assertTrue(TeamRemotePeer.parse("noequals").isEmpty());
  }
}
