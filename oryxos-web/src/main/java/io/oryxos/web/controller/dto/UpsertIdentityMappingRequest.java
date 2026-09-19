package io.oryxos.web.controller.dto;

/** Upsert identity mapping 请求（#577）；镜像 CLI {@code oryxos user oidc-map}。email 可选。 */
public record UpsertIdentityMappingRequest(
    String issuer, String subject, String username, String email) {}
