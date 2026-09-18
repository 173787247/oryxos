package io.oryxos.web.controller.dto;

/** 设置团队所属组织（#554）。orgId 空/缺省=清空。 */
public record SetTeamOrgRequest(String orgId) {}
