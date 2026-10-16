# Phase 1 Data Model: Health 端点暴露版本信息

**Feature**: 052-health-version | **Date**: 2026-10-14

本特性不涉及持久化实体，只有一个运行期只读的内存值对象（实例生命周期内不变）。

## HealthView（健康检查返回视图）

承载 `/api/v1/health` 的 `data` 部分，以 record 实现（对齐既有 `InfoView`）。

| 字段 | 类型 | 说明 | 约束 |
|------|------|------|------|
| `status` | String | 健康状态，沿用现值 | 非空；现阶段恒为 `"ok"`，语义与位置不变（向后兼容） |
| `version` | String | OryxOS 发布版本号 | 非空；来自 `BuildProperties.version`（Maven `<version>`）；不可得时为占位 |
| `build` | String | 构建标识 | 非空；git short sha → 构建时间 → `dev` 降级链；绝不为 null |

序列化后外层仍为统一 `ApiResponse{code,message,data}`，`data` = `{status,version,build}`。

## VersionInfo（版本信息值对象）

启动时解析一次、缓存为不可变值的内部值对象，供 `health()` 读取。

| 字段 | 类型 | 说明 |
|------|------|------|
| `version` | String | 解析后的版本号（已应用降级，非空） |
| `build` | String | 解析后的构建标识（已应用降级，非空） |

### 取值与降级规则

- `version`：`BuildProperties` 存在 → 取 `version`；否则 → 占位 `"unknown"`。
- `build`：`git.properties` 有 `git.commit.id.abbrev` → 取之；否则 `BuildProperties` 有构建时间 → 取 ISO 时间戳；否则 → `"dev"`。
- 解析在组件构造期完成一次，结果不可变 → 满足"实例生命周期内稳定一致"（FR-003）。
- 任一来源缺失只触发降级，**不抛异常**（FR-004 / Edge Case）。

### 状态与生命周期

- 无状态转换：启动解析后只读，不随请求、不随健康状态变化。
- 与健康状态解耦：`VersionInfo` 的可读性不依赖端点是否健康（Edge Case）。
