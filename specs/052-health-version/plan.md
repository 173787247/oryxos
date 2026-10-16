# Implementation Plan: Health 端点暴露版本信息

**Branch**: `052-health-version` | **Date**: 2026-10-14 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/052-health-version/spec.md`

## Summary

在既有 `GET /api/v1/health` 返回体 `data` 内纯增量新增 `version`（Maven 项目版本号）与 `build`（git short sha，降级到构建时间/`dev`）两个字段，让运维与客户端一眼确认线上实例版本。技术路径：启用 Spring Boot `build-info` 与 `git-commit-id` 两个标准构建插件产出版本来源，web 层以只读组件 `VersionInfoProvider` 启动期解析缓存、`HealthView` record 承载视图，原字段与鉴权一律不变。

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 3.x（Spring MVC）、`spring-boot-maven-plugin`（build-info goal）、`git-commit-id-maven-plugin`

**Storage**: N/A（无持久化；运行期内存只读值对象）

**Testing**: JUnit 5 + Spring Boot Test（`@WebMvcTest`/MockMvc 校验返回体契约）

**Target Platform**: 企业私有部署（K8s / VM / 物理机），单 fat JAR

**Project Type**: web-service（多模块，改动集中在 `oryxos-web` + 构建配置）

**Performance Goals**: 健康检查为高频轻量调用；版本信息启动期解析一次、请求期 O(1) 读缓存，无额外开销

**Constraints**: 纯增量、向后兼容（原字段/路径/鉴权不变）；不新增端点；不新增模块；不改 `oryxos-core` 契约

**Scale/Scope**: 改动极小——1 个 record、1 个只读组件、1 处 controller 返回体、2 处 pom 插件配置

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 判定 | 说明 |
|------|------|------|
| I. 自实现 ReAct | ✅ N/A | 不触及推理循环 |
| II. Spring AI 仅协议转换 | ✅ N/A | 不触及 Provider/LLM |
| III. Provider 显式映射 | ✅ N/A | 不触及 Provider 路由 |
| IV. 一个目录=一个 Agent | ✅ N/A | 不触及 Agent/Skill 加载 |
| V. 审计 Day One | ✅ 符合 | 只读视图扩展，不新增 tool/LLM 调用，不涉审计表 |
| VI. 安全是地基 | ✅ 符合 | 不走 Shell/文件白名单；运行期只读 classpath 资源；版本为非敏感公开信息；鉴权不变 |
| VII. 同步执行+虚拟线程 | ✅ 符合 | 纯同步读取，无 Reactor/CompletableFuture |
| VIII. 目录配置即 Agent/状态外置 | ✅ 符合 | 无状态，版本信息为启动期派生的只读内存值 |
| 技术栈/模块约束 | ✅ 符合 | 不新增模块、不改 core 契约，仅加 2 个标准构建插件 + web 层改动 |

**门禁结论：全绿，无需复杂度论证（Complexity Tracking 留空）。** 设计后复查（Phase 1 结束）结论不变。

## Project Structure

### Documentation (this feature)

```text
specs/052-health-version/
├── plan.md              # 本文件
├── research.md          # Phase 0：取值来源与降级决策
├── data-model.md        # Phase 1：HealthView / VersionInfo 值对象
├── quickstart.md        # Phase 1：4 个验证场景
├── contracts/
│   └── health.md        # Phase 1：GET /health 契约（变更前后 + 契约规则）
└── tasks.md             # Phase 2：/speckit-tasks 生成（本命令不创建）
```

### Source Code (repository root)

```text
pom.xml                              # 新增 git-commit-id-maven-plugin（或在 boot 模块）
oryxos-boot/pom.xml                  # 启用 spring-boot-maven-plugin build-info goal
oryxos-web/src/main/java/io/oryxos/web/
├── controller/
│   └── SystemApiController.java     # health() 改为返回 HealthView
├── controller/dto/
│   └── HealthView.java              # 新增 record(status, version, build)
└── version/
    └── VersionInfoProvider.java     # 新增：启动期解析 version/build + 降级，缓存只读

oryxos-web/src/test/java/io/oryxos/web/
└── controller/
    └── SystemApiControllerHealthTest.java  # 新增：契约断言（C-1..C-6）
```

**Structure Decision**: 改动集中在 `oryxos-web` 模块 + 根/boot 构建配置，不新增模块、不动 `oryxos-core` 契约（符合"新增能力只加/改必要文件"）。版本解析逻辑独立成 `version/VersionInfoProvider`，与 controller 解耦便于单测降级分支。

## Complexity Tracking

> 无宪法违背项，本节留空。
