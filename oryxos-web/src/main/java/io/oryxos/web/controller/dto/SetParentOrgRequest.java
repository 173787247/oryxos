package io.oryxos.web.controller.dto;

/** 设置组织父级（#566）。parentOrgId 空/缺省=清空。 */
public record SetParentOrgRequest(String parentOrgId) {}
