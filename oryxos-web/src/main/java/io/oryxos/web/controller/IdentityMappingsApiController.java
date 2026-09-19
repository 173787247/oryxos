package io.oryxos.web.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxos.storage.IdentityMapping;
import io.oryxos.storage.IdentityMappingService;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.config.WebOidcProperties;
import io.oryxos.web.controller.dto.IdentityMappingView;
import io.oryxos.web.controller.dto.UpsertIdentityMappingRequest;
import io.oryxos.web.error.ResourceNotFoundException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OIDC identity_mappings Admin HTTP API（#577）：镜像 CLI {@code oryxos user oidc-map} + list/delete。
 *
 * <p>flag {@code oryxos.web.oidc.mappings-api-enabled} 默认关 → 404。授权走 {@code RequestActionResolver}
 * 的 {@code MANAGE_MEMBERS}（ADMIN）。与 {@code oryxos.web.oidc.enabled}（登录）独立。
 */
@SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification =
        "identity-mappings API 是有意暴露的 Spring Controller（#577）；service/properties 为 Spring"
            + " 注入共享单例，构造注入存同一引用正是意图（镜像 TeamsApiController）。")
@RestController
public class IdentityMappingsApiController {

  private static final String MSG_DISABLED = "identity mappings api disabled";

  private final WebOidcProperties properties;
  private final IdentityMappingService mappings;

  public IdentityMappingsApiController(
      WebOidcProperties properties, IdentityMappingService mappings) {
    this.properties = properties;
    this.mappings = mappings;
  }

  @GetMapping("/api/v1/identity-mappings")
  public ApiResponse<List<IdentityMappingView>> list() {
    requireEnabled();
    List<IdentityMappingView> views = new ArrayList<>();
    for (IdentityMapping row : mappings.list()) {
      views.add(IdentityMappingView.from(row));
    }
    return ApiResponse.ok(List.copyOf(views));
  }

  @PostMapping("/api/v1/identity-mappings")
  public ApiResponse<IdentityMappingView> upsert(@RequestBody UpsertIdentityMappingRequest body) {
    requireEnabled();
    String issuer = body == null ? null : body.issuer();
    String subject = body == null ? null : body.subject();
    String username = body == null ? null : body.username();
    String email = body == null ? null : body.email();
    return ApiResponse.ok(
        IdentityMappingView.from(mappings.upsert(issuer, subject, username, email)));
  }

  @DeleteMapping("/api/v1/identity-mappings")
  public ApiResponse<Void> delete(
      @RequestParam("issuer") String issuer, @RequestParam("subject") String subject) {
    requireEnabled();
    mappings.delete(issuer, subject);
    return ApiResponse.ok(null);
  }

  private void requireEnabled() {
    if (properties == null || !properties.isMappingsApiEnabled()) {
      throw new ResourceNotFoundException(MSG_DISABLED);
    }
  }
}
