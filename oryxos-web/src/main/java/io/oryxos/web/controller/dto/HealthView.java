package io.oryxos.web.controller.dto;

/**
 * GET /health 视图：健康状态 + 版本信息（052）。
 *
 * <p>纯增量扩展——{@code status} 字段名、位置与语义不变（向后兼容）；{@code version}/{@code build} 恒为非空字符串，取值与降级规则见
 * VersionInfoProvider。
 */
public record HealthView(String status, String version, String build) {}
