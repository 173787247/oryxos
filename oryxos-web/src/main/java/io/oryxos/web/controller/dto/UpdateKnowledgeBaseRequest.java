package io.oryxos.web.controller.dto;

/** PATCH /knowledge/{name} 请求体：只改描述。 */
public record UpdateKnowledgeBaseRequest(String description) {}
