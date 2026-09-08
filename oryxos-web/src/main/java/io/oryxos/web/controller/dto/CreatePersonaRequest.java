package io.oryxos.web.controller.dto;

/** POST /personas 请求体：新建一个自定义人格（key + 源文件全文）（025 人格库）。 */
public record CreatePersonaRequest(String key, String sourceContent) {}
