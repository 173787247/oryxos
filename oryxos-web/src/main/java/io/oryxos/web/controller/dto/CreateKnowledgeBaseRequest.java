package io.oryxos.web.controller.dto;

/** POST /knowledge 请求体；backend 缺省 local（FR-015）。 */
public record CreateKnowledgeBaseRequest(String name, String description, String backend) {}
