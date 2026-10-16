# Tasks: Health 端点暴露版本信息

**Feature**: 052-health-version | **Branch**: `052-health-version`
**Input**: [spec.md](spec.md) · [plan.md](plan.md) · [research.md](research.md) · [data-model.md](data-model.md) · [contracts/health.md](contracts/health.md) · [quickstart.md](quickstart.md)

改动集中在 `oryxos-web` + 根/boot 构建配置，不新增模块、不改 `oryxos-core` 契约。

## Phase 1: Setup（构建期版本来源）

- [X] T001 在 `oryxos-boot/pom.xml` 的 `spring-boot-maven-plugin` 下启用 `build-info` goal，使构建期生成 `META-INF/build-info.properties`（含 version = Maven `<version>`）
- [X] T002 [P] 配置 `io.github.git-commit-id:git-commit-id-maven-plugin`（落在 `oryxos-boot/pom.xml` 而非根 pom——产物直接进 fat jar classpath 更正确），生成 `git.properties`（`git.commit.id.abbrev`），`failOnNoGitDirectory=false` 兜底源码态

## Phase 2: Foundational（阻塞所有故事的前置）

- [X] T003 在 `oryxos-web/src/main/java/io/oryxos/web/version/VersionInfoProvider.java` 新增只读组件：启动期解析 version/build 并缓存为不可变值；注入可选 `BuildProperties`；按 [research.md](research.md) Decision 2 的降级链实现 `build`（`git.commit.id.abbrev` → 构建时间 → `"dev"`）与 `version`（`BuildProperties.version` → `"unknown"`）；任一来源缺失只降级、**不抛异常**（FR-004）

## Phase 3: User Story 1 - 运维确认线上版本（P1）

**Goal**: `/health` 返回体含可读 `version` 与 `build`。
**Independent Test**: 对运行实例请求 `/api/v1/health`，`data` 含非空 `version`、`build` 即通过。

- [X] T004 [US1] 在 `oryxos-web/src/main/java/io/oryxos/web/controller/dto/HealthView.java` 新增 record `HealthView(String status, String version, String build)`，对齐既有 `InfoView` 风格；三字段均非空（约束见 [data-model.md](data-model.md)）
- [X] T005 [US1] 修改 `oryxos-web/src/main/java/io/oryxos/web/controller/SystemApiController.java` 的 `health()`：注入 `VersionInfoProvider`，返回 `ApiResponse.ok(new HealthView("ok", versionInfo.version(), versionInfo.build()))`
- [X] T006 [P] [US1] 在 `oryxos-web/src/test/java/io/oryxos/web/controller/SystemApiControllerHealthTest.java` 新增测试：MockMvc 请求 `/api/v1/health`，断言 `data.version`、`data.build` 存在且非空（契约 C-2/C-3）；断言同一实例两次请求取值一致（C-4 / FR-003）

## Phase 4: User Story 2 - 现有调用方不受影响（P1）

**Goal**: 原字段与鉴权不变，老调用方零改动。
**Independent Test**: 用上线前契约校验返回体，`data.status == "ok"` 仍在、鉴权不变即通过。

- [X] T007 [US2] 在 `SystemApiControllerHealthTest.java` 补充向后兼容断言：`data.status` 仍存在且为 `"ok"`、HTTP 200、`/health` 仍匿名可访问（契约 C-1/C-6 / US2）
- [X] T008 [P] [US2] 补充降级分支测试：无 `BuildProperties`/`git.properties` 时 `version`/`build` 为占位值（`unknown`/`dev`）、字段存在非 null、不抛异常（契约 C-5 / FR-004 / SC-004）

## Phase 5: Polish & Cross-Cutting

- [X] T009 [P] 若 `/health` 有 OpenAPI/springdoc 注解或示例，更新为新返回体结构（`contracts/health.md` 变更后 JSON）
- [X] T010 按 [quickstart.md](quickstart.md) 跑通 4 个验证场景：已打包起实例 live curl `/api/v1/health` → `version=0.1.7-RELEASE`、`build=3edb6f0`、`status=ok`，端到端装配链验证通过（build-info.properties/git.properties 产物已确认）
- [X] T011 [P] `oryxos-web` 模块门禁全绿：Spotless + PMD + Checkstyle + **SpotBugs（Error size 0）** + 477 测试通过。SpotBugs 曾抓出 `VersionInfoProvider` 两处 `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE`（getVersion/getShortCommitId 连调两次），已改为局部变量后复测通过。备注：全 reactor `mvn verify` 被 `oryxos-core` 既有的 macOS `/var`→`/private/var` 符号链接 realpath 测试（AgentStore/SkillBinding）阻断，与 052 无关；OWASP 本地默认 `owasp.skip=true`，由 CI 跑。

## Dependencies & Execution Order

```
Phase 1 (T001, T002[P])  →  Phase 2 (T003)  →  ┌ US1 (T004→T005, T006[P])
构建来源就绪                 版本组件就绪        └ US2 (T007, T008[P])  →  Polish (T009[P], T010, T011[P])
```

- T003 依赖 T001/T002 的产物（BuildProperties / git.properties）才能真正取到值，但降级逻辑使其在缺失时仍可先行开发。
- US1 的 T005 依赖 T003（组件）+ T004（record）。
- US2 的测试依赖 US1 的 controller 改动已落地。

## Parallel Opportunities

- T001 与 T002 不同文件，可并行。
- T006 / T008 / T009 / T011 标 `[P]`，各自独立文件/动作，可并行。

## Implementation Strategy

- **MVP = User Story 1**：Phase 1→2→3 完成即交付核心价值（运维能看到版本）。
- US2 本质是"给 MVP 加向后兼容的护栏测试"，紧随其后。
- Polish 收尾文档与门禁。
