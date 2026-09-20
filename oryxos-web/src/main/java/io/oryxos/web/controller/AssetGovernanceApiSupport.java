package io.oryxos.web.controller;

import io.oryxos.core.auth.Principal;
import io.oryxos.core.policy.Action;
import io.oryxos.core.policy.AssetGovernance;
import io.oryxos.core.policy.AssetGovernanceStore;
import io.oryxos.core.policy.ResourceRef;
import io.oryxos.storage.AssetGovernanceEventRecorder;
import io.oryxos.storage.AssetGovernanceRevision;
import io.oryxos.storage.AssetGovernanceRevisionRecorder;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.config.WebAssetGovernanceProperties;
import io.oryxos.web.controller.dto.AssetGovernanceRevisionDiffView;
import io.oryxos.web.controller.dto.AssetGovernanceRevisionView;
import io.oryxos.web.controller.dto.AssetGovernanceView;
import io.oryxos.web.error.ResourceNotFoundException;
import io.oryxos.web.security.AssetBindGuard;
import io.oryxos.web.security.PrincipalHolder;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * 三类资产共用的治理读写（041 / #537 / #541 / #544）：PUT 先 {@code decide(MANAGE_*)}，再写侧车、记变更事件，可选记全文快照； restore
 * 把历史快照写回现网；diff 输出两版统一 diff。
 *
 * <p>放在 support 而不是复制三份 Controller 逻辑，是为了让「权限只有 decide 一条路径」可被单测盯住。
 */
final class AssetGovernanceApiSupport {

  private AssetGovernanceApiSupport() {}

  static ApiResponse<AssetGovernanceView> get(
      ResourceRef resource, Function<String, AssetGovernance> loader) {
    // GET 由 Filter 路径映射做 READ；这里不另开权限路径。PUT 才再 decide(MANAGE_*)。
    return ApiResponse.ok(AssetGovernanceView.from(loader.apply(resource.id())));
  }

  static ApiResponse<List<AssetGovernanceRevisionView>> listRevisions(
      WebAssetGovernanceProperties properties,
      AssetGovernanceRevisionRecorder revisions,
      ResourceRef resource) {
    if (properties == null
        || !properties.isVersionHistoryEnabled()
        || revisions == null
        || resource == null) {
      return ApiResponse.ok(List.of());
    }
    return ApiResponse.ok(
        revisions.list(resource.type(), resource.id()).stream()
            .map(AssetGovernanceRevisionView::from)
            .toList());
  }

  /** 两版快照统一 diff（#544）。flag 关或不存在/资源不匹配 → 404。GET 路径不做额外 MANAGE decide（与 list 同档 READ）。 */
  static ApiResponse<AssetGovernanceRevisionDiffView> diff(
      WebAssetGovernanceProperties properties,
      AssetGovernanceRevisionRecorder revisions,
      ResourceRef resource,
      long fromId,
      long toId) {
    if (properties == null || !properties.isVersionHistoryEnabled() || revisions == null) {
      throw new ResourceNotFoundException("version history disabled");
    }
    AssetGovernanceRevision from = requireOwnedRevision(revisions, resource, fromId);
    AssetGovernanceRevision to = requireOwnedRevision(revisions, resource, toId);
    String unified =
        GovernanceUnifiedDiff.unified(fromId, toId, from.getSnapshotText(), to.getSnapshotText());
    return ApiResponse.ok(
        new AssetGovernanceRevisionDiffView(
            fromId, toId, from.getVersionLabel(), to.getVersionLabel(), unified));
  }

  private static AssetGovernanceRevision requireOwnedRevision(
      AssetGovernanceRevisionRecorder revisions, ResourceRef resource, long revisionId) {
    AssetGovernanceRevision row =
        revisions
            .find(revisionId)
            .orElseThrow(() -> new ResourceNotFoundException("revision not found: " + revisionId));
    if (!Objects.equals(resource.type(), row.getResourceType())
        || !Objects.equals(resource.id(), row.getResourceId())) {
      throw new ResourceNotFoundException("revision not found: " + revisionId);
    }
    return row;
  }

  static ApiResponse<AssetGovernanceView> put(
      HttpServletRequest request,
      AssetBindGuard guard,
      AssetGovernanceEventRecorder recorder,
      WebAssetGovernanceProperties properties,
      AssetGovernanceRevisionRecorder revisions,
      Action action,
      ResourceRef resource,
      AssetGovernanceView body,
      Saver saver,
      Function<String, AssetGovernance> loader) {
    guard.requireManage(request, action, resource);
    AssetGovernance model = body == null ? AssetGovernance.empty() : body.toModel();
    return writeLive(request, recorder, properties, revisions, resource, model, saver, loader);
  }

  /** 将指定快照写回现网（#541）。flag 关或不存在/资源不匹配 → 404。成功路径与 PUT 相同（事件 + 新快照）。 */
  static ApiResponse<AssetGovernanceView> restore(
      HttpServletRequest request,
      AssetBindGuard guard,
      AssetGovernanceEventRecorder recorder,
      WebAssetGovernanceProperties properties,
      AssetGovernanceRevisionRecorder revisions,
      Action action,
      ResourceRef resource,
      long revisionId,
      Saver saver,
      Function<String, AssetGovernance> loader) {
    guard.requireManage(request, action, resource);
    if (properties == null || !properties.isVersionHistoryEnabled() || revisions == null) {
      throw new ResourceNotFoundException("version history disabled");
    }
    AssetGovernanceRevision row = requireOwnedRevision(revisions, resource, revisionId);
    AssetGovernance model = AssetGovernanceStore.parseSnapshotYaml(row.getSnapshotText());
    return writeLive(request, recorder, properties, revisions, resource, model, saver, loader);
  }

  private static ApiResponse<AssetGovernanceView> writeLive(
      HttpServletRequest request,
      AssetGovernanceEventRecorder recorder,
      WebAssetGovernanceProperties properties,
      AssetGovernanceRevisionRecorder revisions,
      ResourceRef resource,
      AssetGovernance model,
      Saver saver,
      Function<String, AssetGovernance> loader) {
    saver.save(resource.id(), model);
    Principal actor = PrincipalHolder.get(request);
    if (recorder != null) {
      recorder.record(
          actor.describe(), resource.type(), resource.id(), AssetGovernanceStore.summarize(model));
    }
    if (properties != null && properties.isVersionHistoryEnabled() && revisions != null) {
      revisions.record(
          actor.describe(),
          resource.type(),
          resource.id(),
          model.version(),
          AssetGovernanceStore.snapshotYaml(model));
    }
    return ApiResponse.ok(AssetGovernanceView.from(loader.apply(resource.id())));
  }

  @FunctionalInterface
  interface Saver {
    void save(String id, AssetGovernance governance);
  }
}
