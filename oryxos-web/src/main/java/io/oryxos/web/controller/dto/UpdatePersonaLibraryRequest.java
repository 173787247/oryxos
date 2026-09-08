package io.oryxos.web.controller.dto;

/** PUT /personas/{key} 请求体：更新自定义人格的源文件全文（key 走路径，不可改）（025 人格库）。 */
public record UpdatePersonaLibraryRequest(String sourceContent) {}
