package io.oryxos.web.controller.dto;

/** 设置团队父级（#581）。parentTeamId 空/缺省=清空。 */
public record SetParentTeamRequest(String parentTeamId) {}
