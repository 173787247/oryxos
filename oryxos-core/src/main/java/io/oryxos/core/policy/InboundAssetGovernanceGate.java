package io.oryxos.core.policy;

import java.util.Optional;

/**
 * 入站消息 OFFLINE 门禁（041 / #504）：只读 {@link AssetGovernanceStore}，不经角色矩阵、不调 {@link
 * AuthorizationService#decide}（入站无 Principal，裸 decide(anonymous) 会在 RBAC 开时打挂全部 IM）。
 *
 * <p>flag 关或 store 空 → 恒放行。渠道或绑定 Agent 的 {@code health=OFFLINE} → 拒绝推理；缺侧车 / 非 OFFLINE →
 * 放行。平台挑战握手不经过本闸（仍在适配器层）。
 */
public final class InboundAssetGovernanceGate {

  /** 与装饰器同文案，便于审计/用户侧对齐。 */
  public static final String REASON_OFFLINE = AssetAwareAuthorizationServiceImpl.REASON_OFFLINE;

  /** 测试与 flag 关路径：零拒绝。 */
  public static final InboundAssetGovernanceGate NOOP = new InboundAssetGovernanceGate(null, false);

  private final AssetGovernanceStore store;
  private final boolean enabled;

  public InboundAssetGovernanceGate(AssetGovernanceStore store, boolean enabled) {
    this.store = store;
    this.enabled = enabled && store != null;
  }

  /** 与 HTTP 资产门禁同启条件：RBAC + asset-governance 均开。 */
  public static InboundAssetGovernanceGate of(
      AssetGovernanceStore store, boolean rbacEnabled, boolean assetGovernanceEnabled) {
    return new InboundAssetGovernanceGate(store, rbacEnabled && assetGovernanceEnabled);
  }

  /**
   * @return 拒绝原因（OFFLINE）；空表示放行
   */
  public Optional<String> denyReason(String channelName, String agentName) {
    if (!enabled) {
      return Optional.empty();
    }
    if (isOffline(store.loadChannel(channelName))) {
      return Optional.of(REASON_OFFLINE);
    }
    if (isOffline(store.loadAgent(agentName))) {
      return Optional.of(REASON_OFFLINE);
    }
    return Optional.empty();
  }

  private static boolean isOffline(AssetGovernance governance) {
    return governance != null && governance.health() == AssetGovernance.Health.OFFLINE;
  }
}
