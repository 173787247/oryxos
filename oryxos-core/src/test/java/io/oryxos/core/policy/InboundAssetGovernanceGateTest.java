package io.oryxos.core.policy;

import static org.assertj.core.api.Assertions.assertThat;

import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.ChannelConfigLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InboundAssetGovernanceGateTest {

  @TempDir Path root;

  @Test
  void noopWhenFlagOffEvenIfOfflineOnDisk() {
    writeChannelOffline("ops");
    InboundAssetGovernanceGate gate =
        InboundAssetGovernanceGate.of(new AssetGovernanceStore(root), false, true);
    assertThat(gate.denyReason("ops", "agent-a")).isEmpty();
  }

  @Test
  void deniesWhenChannelOffline() {
    writeChannelOffline("ops");
    InboundAssetGovernanceGate gate =
        InboundAssetGovernanceGate.of(new AssetGovernanceStore(root), true, true);
    assertThat(gate.denyReason("ops", "agent-a"))
        .contains(InboundAssetGovernanceGate.REASON_OFFLINE);
  }

  @Test
  void deniesWhenAgentOffline() throws Exception {
    Path agentDir = root.resolve("agents").resolve("agent-a");
    Files.createDirectories(agentDir);
    Files.writeString(agentDir.resolve("GOVERNANCE.yml"), "health: OFFLINE\n");
    InboundAssetGovernanceGate gate =
        InboundAssetGovernanceGate.of(new AssetGovernanceStore(root), true, true);
    assertThat(gate.denyReason("ops", "agent-a"))
        .contains(InboundAssetGovernanceGate.REASON_OFFLINE);
  }

  @Test
  void allowsWhenNoGovernanceBlock() {
    InboundAssetGovernanceGate gate =
        InboundAssetGovernanceGate.of(new AssetGovernanceStore(root), true, true);
    assertThat(gate.denyReason("ops", "agent-a")).isEmpty();
  }

  private void writeChannelOffline(String name) {
    AssetGovernance offline =
        new AssetGovernance(null, null, null, null, AssetGovernance.Health.OFFLINE);
    new ChannelConfigLoader(root.resolve(AssetGovernanceStore.CHANNELS_FILE))
        .save(List.of(channel(name).withGovernance(offline)));
  }

  private static ChannelConfig channel(String name) {
    return new ChannelConfig(name, "feishu", "app", "secret", "ops-agent", true);
  }
}
