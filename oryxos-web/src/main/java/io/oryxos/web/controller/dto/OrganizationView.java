package io.oryxos.web.controller.dto;

import io.oryxos.storage.Organization;

/** 组织目录视图（#554）：orgId + displayName。 */
public record OrganizationView(String orgId, String displayName) {

  public static OrganizationView from(Organization org) {
    if (org == null) {
      return new OrganizationView(null, null);
    }
    return new OrganizationView(org.getOrgId(), org.getDisplayName());
  }
}
