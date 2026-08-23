package io.oryxos.web.controller.dto;

/** 更新模型定价请求：只改单价，provider/model 不可变（唯一键）。 */
public record UpdatePricingRequest(Double promptPrice, Double completionPrice) {}
