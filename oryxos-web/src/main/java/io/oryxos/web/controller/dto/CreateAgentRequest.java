package io.oryxos.web.controller.dto;

/** POST /agents 请求体：Agent 名 + 描述 + 可选的 provider/model（不选则走默认 provider）。 */
public record CreateAgentRequest(String name, String description, String provider, String model) {}
