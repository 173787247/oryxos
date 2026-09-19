package io.oryxos.web.controller.dto;

import io.oryxos.storage.Organization;

/** 组织目录视图（#554 / #566）：orgId + displayName + parentOrgId。 */
public record OrganizationView(String orgId, String displayName, String parentOrgId) {

  public static OrganizationView from(Organization org) {
    if (org == null) {
      return new OrganizationView(null, null, null);
    }
    return new OrganizationView(org.getOrgId(), org.getDisplayName(), org.getParentOrgId());
  }
}
