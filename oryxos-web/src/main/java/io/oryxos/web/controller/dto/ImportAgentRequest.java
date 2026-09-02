package io.oryxos.web.controller.dto;

/**
 * import-preview / import 两步共用请求体：body = 源文件内容字符串 + 可选 name（缺省从源 displayName 派生 slug）+ 可选
 * model（缺省落「请在此填写模型名」占位，与 create 脚手架同语义；可选 provider 跟随默认）。
 */
public record ImportAgentRequest(String sourceContent, String name, String model) {}
