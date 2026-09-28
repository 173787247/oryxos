package io.oryxos.web.controller.dto;

/** POST /api/v1/flows/generate：自然语言目标 → Flow Markdown 草稿。 */
public record GenerateFlowRequest(String goal, String id) {}
