package io.oryxos.web.controller.dto;

/** 新增模型定价请求：provider + model 唯一标识一条定价。 */
public record CreatePricingRequest(
    String provider, String model, Double promptPrice, Double completionPrice) {}
