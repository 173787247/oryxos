package io.oryxos.web.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxos.core.auth.Principal;
import io.oryxos.core.channel.ChannelAdminService;
import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.policy.Action;
import io.oryxos.core.policy.AssetGovernance;
import io.oryxos.core.policy.AssetGovernanceStore;
import io.oryxos.core.policy.AuthorizationService;
import io.oryxos.core.policy.ResourceRef;
import io.oryxos.storage.AssetGovernanceEventRecorder;
import io.oryxos.storage.AssetGovernanceRevisionRecorder;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.config.WebAssetGovernanceProperties;
import io.oryxos.web.controller.dto.AssetGovernanceRevisionView;
import io.oryxos.web.controller.dto.AssetGovernanceView;
import io.oryxos.web.controller.dto.ChannelStatusView;
import io.oryxos.web.controller.dto.ChannelView;
import io.oryxos.web.error.ResourceNotFoundException;
import io.oryxos.web.security.AssetBindGuard;
import io.oryxos.web.security.PrincipalHolder;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 入站 IM 渠道管理（017）：薄转发给 {@link ChannelAdminService}（core 契约，依赖倒置——与 {@code McpApiController} 之于
 * {@code McpServerAdmin} 同分层）。
 *
 * <p>增/改/删都是「落盘 + 立即生效」：加一个立刻建长连接、删一个立刻断开，无需重启。列表与回显走 raw 口径且 appSecret 掩码——凭证明文永不回显（FR-012）。name
 * 冲突 / 定义非法 → 400；不存在 → 404；统一 {@code ApiResponse} 信封。
 *
 * <p>041：写路径在落盘前多一次 {@code decide(MANAGE_CHANNELS, channel(name))}。Filter 仍映射 {@code
 * channel(null)}，URL 不变。入站 webhook 不走本控制器。治理块走 {@code GET/PUT .../governance}，只改 channels.yaml，不断连。
 */
@SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification =
        "core-stage web API is unauthenticated by design (internal network + gateway); auth is extension-phase. admin 是 Spring 注入的共享单例，构造注入共享同一引用正是意图。")
@RestController
@RequestMapping("/api/v1/channels")
public class ChannelApiController {

  private final ChannelAdminService admin;

  /** 写渠道前的唯一额外裁决点。默认全允，保证未装配 / flag 关时不额外拒绝；容器 setter 覆盖为真实 {@link AuthorizationService}。 */
  private AssetBindGuard assetBindGuard = new AssetBindGuard(AuthorizationService.ALLOW_ALL);

  /** 治理变更审计；未装配时跳过（与 AssetGovernanceController 一致）。 */
  private AssetGovernanceEventRecorder governanceRecorder;

  /** #537 全文快照；未装配或 flag 关时跳过。 */
  private AssetGovernanceRevisionRecorder governanceRevisions;

  private WebAssetGovernanceProperties assetGovernanceProperties;

  public ChannelApiController(ChannelAdminService admin) {
    this.admin = admin;
  }

  @Autowired(required = false)
  public void setAssetBindGuard(AssetBindGuard assetBindGuard) {
    if (assetBindGuard != null) {
      this.assetBindGuard = assetBindGuard;
    }
  }

  @Autowired(required = false)
  public void setGovernanceRecorder(AssetGovernanceEventRecorder governanceRecorder) {
    this.governanceRecorder = governanceRecorder;
  }

  @Autowired(required = false)
  public void setGovernanceRevisions(AssetGovernanceRevisionRecorder governanceRevisions) {
    this.governanceRevisions = governanceRevisions;
  }

  @Autowired(required = false)
  public void setAssetGovernanceProperties(WebAssetGovernanceProperties assetGovernanceProperties) {
    this.assetGovernanceProperties = assetGovernanceProperties;
  }

  @GetMapping
  public ApiResponse<List<ChannelView>> list(HttpServletRequest request) {
    return ApiResponse.ok(
        admin.listRaw().stream()
            .filter(c -> assetBindGuard.isVisible(request, ResourceRef.channel(c.name())))
            .map(ChannelView::from)
            .toList());
  }

  @GetMapping("/status")
  public ApiResponse<List<ChannelStatusView>> status() {
    return ApiResponse.ok(admin.status().stream().map(ChannelStatusView::from).toList());
  }

  @GetMapping("/{name}/governance")
  public ApiResponse<AssetGovernanceView> getGovernance(@PathVariable String name) {
    ChannelConfig config = requireConfig(name);
    AssetGovernance block =
        config.governance() == null ? AssetGovernance.empty() : config.governance();
    return ApiResponse.ok(AssetGovernanceView.from(block));
  }

  @GetMapping("/{name}/governance/revisions")
  public ApiResponse<List<AssetGovernanceRevisionView>> listGovernanceRevisions(
      @PathVariable String name) {
    requireExists(name);
    return AssetGovernanceApiSupport.listRevisions(
        assetGovernanceProperties, governanceRevisions, ResourceRef.channel(name));
  }

  @PostMapping("/{name}/governance/revisions/{revisionId}/restore")
  public ApiResponse<AssetGovernanceView> restoreGovernance(
      HttpServletRequest request, @PathVariable String name, @PathVariable long revisionId) {
    requireExists(name);
    return AssetGovernanceApiSupport.restore(
        request,
        assetBindGuard,
        governanceRecorder,
        assetGovernanceProperties,
        governanceRevisions,
        Action.MANAGE_CHANNELS,
        ResourceRef.channel(name),
        revisionId,
        (id, g) -> admin.updateGovernance(id, g),
        id -> {
          ChannelConfig cfg = requireConfig(id);
          return cfg.governance() == null ? AssetGovernance.empty() : cfg.governance();
        });
  }

  @PutMapping("/{name}/governance")
  public ApiResponse<AssetGovernanceView> putGovernance(
      HttpServletRequest request,
      @PathVariable String name,
      @RequestBody(required = false) AssetGovernanceView body) {
    requireExists(name);
    requireChannelManage(request, name);
    AssetGovernance model = body == null ? AssetGovernance.empty() : body.toModel();
    AssetGovernance saved = admin.updateGovernance(name, model);
    Principal actor = PrincipalHolder.get(request);
    if (governanceRecorder != null) {
      governanceRecorder.record(
          actor.describe(), ResourceRef.TYPE_CHANNEL, name, AssetGovernanceStore.summarize(saved));
    }
    if (assetGovernanceProperties != null
        && assetGovernanceProperties.isVersionHistoryEnabled()
        && governanceRevisions != null) {
      governanceRevisions.record(
          actor.describe(),
          ResourceRef.TYPE_CHANNEL,
          name,
          saved.version(),
          AssetGovernanceStore.snapshotYaml(saved));
    }
    return ApiResponse.ok(AssetGovernanceView.from(saved));
  }

  @PostMapping
  public ApiResponse<ChannelView> add(HttpServletRequest request, @RequestBody ChannelView req) {
    if (req == null || req.name() == null || req.name().isBlank()) {
      throw new IllegalArgumentException("渠道名为空"); // → 400
    }
    requireChannelManage(request, req.name());
    return ApiResponse.ok(ChannelView.from(admin.add(req.toConfig())));
  }

  @PutMapping("/{name}")
  public ApiResponse<ChannelView> update(
      HttpServletRequest request, @PathVariable String name, @RequestBody ChannelView req) {
    requireExists(name);
    if (req == null) {
      throw new IllegalArgumentException("渠道定义为空"); // → 400
    }
    requireChannelManage(request, name);
    return ApiResponse.ok(ChannelView.from(admin.update(name, req.toConfig())));
  }

  @DeleteMapping("/{name}")
  public ApiResponse<Void> delete(HttpServletRequest request, @PathVariable String name) {
    requireExists(name);
    requireChannelManage(request, name);
    admin.remove(name);
    return ApiResponse.ok(null);
  }

  /** 落盘前唯一额外 decide：具名渠道，供装饰器读 channels.yaml 治理块。 */
  private void requireChannelManage(HttpServletRequest request, String name) {
    assetBindGuard.requireManage(request, Action.MANAGE_CHANNELS, ResourceRef.channel(name));
  }

  private void requireExists(String name) {
    requireConfig(name);
  }

  private ChannelConfig requireConfig(String name) {
    return admin.listRaw().stream()
        .filter(c -> c.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new ResourceNotFoundException("渠道不存在: " + name)); // → 404
  }
}
