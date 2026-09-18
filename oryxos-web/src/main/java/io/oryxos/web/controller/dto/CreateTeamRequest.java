package io.oryxos.web.controller.dto;

/** 创建团队目录请求（#546）。displayName 可空，服务侧回落为 teamId。 */
public record CreateTeamRequest(String teamId, String displayName) {}
