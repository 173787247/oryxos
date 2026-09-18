package io.oryxos.web.controller.dto;

/** 创建组织目录请求（#554）。displayName 可空，服务侧回落为 orgId。 */
public record CreateOrganizationRequest(String orgId, String displayName) {}
