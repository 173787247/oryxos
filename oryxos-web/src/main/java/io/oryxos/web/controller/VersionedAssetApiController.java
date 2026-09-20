package io.oryxos.web.controller;

import io.oryxos.core.auth.Principal;
import io.oryxos.core.workspace.versioned.AssetVersion;
import io.oryxos.core.workspace.versioned.VersionedAssetKind;
import io.oryxos.core.workspace.versioned.VersionedAssetSource;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.error.ResourceNotFoundException;
import io.oryxos.web.security.PrincipalHolder;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Versioned Agent/Skill/Knowledge source API (#473). All routes 404 when {@code
 * oryxos.cluster.versioned-asset-source-enabled} is false.
 */
@RestController
@RequestMapping("/api/v1/workspace/assets/{kind}/{assetId}")
public class VersionedAssetApiController {

  private final VersionedAssetSource source;

  public VersionedAssetApiController(VersionedAssetSource source) {
    this.source = source;
  }

  @GetMapping("/active")
  public ApiResponse<Map<String, Long>> active(
      @PathVariable String kind, @PathVariable String assetId) {
    requireEnabled();
    OptionalLong version = source.activeVersion(parse(kind), assetId);
    if (version.isEmpty()) {
      throw new ResourceNotFoundException("no active version");
    }
    return ApiResponse.ok(Map.of("version", version.getAsLong()));
  }

  @GetMapping("/versions")
  public ApiResponse<List<Map<String, Object>>> versions(
      @PathVariable String kind, @PathVariable String assetId) {
    requireEnabled();
    List<Map<String, Object>> body =
        source.list(parse(kind), assetId).stream()
            .map(
                v ->
                    Map.<String, Object>of(
                        "version",
                        v.version(),
                        "kind",
                        v.kind().directory(),
                        "assetId",
                        v.assetId()))
            .toList();
    return ApiResponse.ok(body);
  }

  @PostMapping("/versions")
  public ApiResponse<Map<String, Object>> publish(
      HttpServletRequest request, @PathVariable String kind, @PathVariable String assetId) {
    requireEnabled();
    AssetVersion published = source.publish(parse(kind), assetId, actor(request));
    return ApiResponse.ok(toView(published));
  }

  @PostMapping("/versions/{version}/activate")
  public ApiResponse<Map<String, Object>> activate(
      HttpServletRequest request,
      @PathVariable String kind,
      @PathVariable String assetId,
      @PathVariable long version) {
    requireEnabled();
    AssetVersion activated = source.activate(parse(kind), assetId, version, actor(request));
    return ApiResponse.ok(toView(activated));
  }

  @PostMapping("/rollback")
  public ApiResponse<Map<String, Object>> rollback(
      HttpServletRequest request, @PathVariable String kind, @PathVariable String assetId) {
    requireEnabled();
    try {
      AssetVersion rolled = source.rollback(parse(kind), assetId, actor(request));
      return ApiResponse.ok(toView(rolled));
    } catch (IllegalStateException missing) {
      throw new ResourceNotFoundException(missing.getMessage());
    }
  }

  private void requireEnabled() {
    if (source == null || !source.isEnabled()) {
      throw new ResourceNotFoundException("versioned asset source disabled");
    }
  }

  private static VersionedAssetKind parse(String kind) {
    try {
      return VersionedAssetKind.parse(kind);
    } catch (IllegalArgumentException e) {
      throw new ResourceNotFoundException(e.getMessage());
    }
  }

  private static String actor(HttpServletRequest request) {
    Principal principal = PrincipalHolder.get(request);
    return principal == null ? "anonymous" : principal.describe();
  }

  private static Map<String, Object> toView(AssetVersion version) {
    return Map.of(
        "kind",
        version.kind().directory(),
        "assetId",
        version.assetId(),
        "version",
        version.version(),
        "contentHash",
        version.contentHash() == null ? "" : version.contentHash());
  }
}
