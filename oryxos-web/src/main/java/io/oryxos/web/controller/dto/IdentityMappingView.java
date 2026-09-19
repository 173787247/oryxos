package io.oryxos.web.controller.dto;

import io.oryxos.storage.IdentityMapping;

/** identity_mappings 视图（#577）：issuer + subject → username[, email]。 */
public record IdentityMappingView(String issuer, String subject, String username, String email) {

  public static IdentityMappingView from(IdentityMapping mapping) {
    if (mapping == null) {
      return new IdentityMappingView(null, null, null, null);
    }
    return new IdentityMappingView(
        mapping.getIssuer(), mapping.getSubject(), mapping.getUsername(), mapping.getEmail());
  }
}
