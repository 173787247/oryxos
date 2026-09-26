package io.oryxos.web.controller.dto;

/** POST /api/v1/team-tasks body. */
public record TeamTaskRequest(String goal, String coordinator) {}
