# Acceptance Report: 企业身份与授权基座（Identity & Authorization）

**Feature**: 039-identity-authorization | **Date**: 2026-09-14 | **Verdict**: **阶段一部分验收**——自动化测试已真实执行并全绿；真机走查、浏览器走查与 SC-002~SC-007 **仍未验收**

> **状态说明（按房规「不允许未验证就写 ✅」）**：本报告初版是设计阶段落卷的 stub。现补入**已真实执行**的自动化测试证据（§自动化测试），其余条目逐条保持「⏳ 待验收」，**没有把未跑的东西写成 ✅**。
>
> **体例说明**：本节标题按仓库主流体例（对齐 `018-rest-api-key` / `020-tool-policy` 的 `## 自动化测试 / ## 真机走查 / ## SC 达成情况 / ## 质量门禁`）。原稿的中文数字小节已重排，内容未删减。

## 自动化测试

**命令**（本机 Maven 3.9.9 + JDK 21；`-pl oryxos-web -am` 会自动带上 core/storage/persona/provider/knowledge 五个上游模块）：

```bash
mvn -B clean verify -pl oryxos-web -am \
  -Dmaven-pmd-plugin.version=3.26.0 -Dspotbugs-maven-plugin.version=4.9.8.2 \
  -Dpmd.skip=true -Dspotbugs.skip=true -Dowasp.skip=true
```

**结果**：`BUILD SUCCESS`，**1033 tests，failures=0，errors=0，skipped=0**。

| 套件 | 用例数 | 说明 |
|------|--------|------|
| `io.oryxos.core.auth.PrincipalTest` | 13 | 新增：主体构造/归一/不可变角色集/匿名语义 |
| `io.oryxos.core.policy.RoleBasedAuthorizationServiceImplTest` | 27 | 新增：三档角色边界、API Key 上限、**角色不叠加（防提权）**；逐动作穷举 11 个动作而非点样例 |
| `io.oryxos.core.policy.ResourceRefTest` | 6 | 新增：资源词表与 `describe()` 退化 |
| `io.oryxos.core.policy.PolicyInterceptTest` | 4 | 既有（020 工具策略）：未回归 |
| `io.oryxos.web.security.ApiKeyAuthFilterTest` | 22 | 既有 20 个全绿 + 新增 2 条「默认关不得多打一次库」 |
| `io.oryxos.web.security.BasicAuthFilterTest` | 17 | 既有：未回归 |
| `io.oryxos.web.security.RbacEnforcerTest` | 6 | 新增：**真实请求穿过 filter 链**——`rbac.enabled=false` 放行、`true`+角色足够放行、角色不足 403 **且控制器未被调用**、API Key 默认档为空被拒、启用时才解析 Key 名称、拒绝响应不带 HTML 内容类型 |

**独立复核方另跑的端到端**：`oryxos-boot` 的 `ApiKeyAuthE2ETest` 5 个全绿（真实 HTTP + SQLite + 完整 filter 链）。

**本次证据能支撑的结论**：

- **SC-001（默认档零破坏）部分成立**：`enabled=false` 时既有 20 个 `ApiKeyAuthFilterTest` 与 17 个 `BasicAuthFilterTest` 全绿；且新增两条断言钉死「未启用授权时不得调用 `findNameByPlaintext`」——把「零行为变化」从"响应相同"收紧到"无新增副作用"。
- **授权在真实请求链路上确实生效**：拒绝是**真的拦住了执行**（断言控制器 `hits == 0`），不只是改了个状态码。
- 授权裁决逻辑（矩阵、上限、不叠加、拒因可读）由单测覆盖。

**本次证据不能支撑的结论**（不写成绿灯）：

- **PMD/P3C 与 SpotBugs 两道门未验证**：本机 Maven 3.9.9 与仓库锁定的 `maven-pmd-plugin:3.21.2` / `spotbugs-maven-plugin:4.8.6.4` 存在 API 不兼容（`MavenMultiPageReport`、`doxia.logging.LogEnabled`）；换 Maven 3.8.8 同样失败（实测）。**这两道门只能由 CI 证明**。
- **P3C 命名风险未证伪**：`RoleBasedAuthorizationServiceImpl` 作为实现类可能触发 P3C「Service 实现类须以 `Impl` 结尾」（020 先例改名 `ToolPolicyServiceImpl`），需 CI 结果确认。

**独立复核**：本切面的契约与实现由独立于实现者的复核方在隔离快照中复跑，报告见仓库外交付目录 `oryxos-contrib/research/authz-verification.md`（含逐文件 md5 锚点、失败面分析与变异检验结论）。

**本阶段交付（设计文档九件套）**：

| 文件 | 定位 |
|------|------|
| `spec.md` | 需求：US1~US3、FR-001~FR-014、SC-001~SC-007、Clarifications（3 项待 maintainer 裁决 + 1 项阻断项 + 1 项待补缺口） |
| `plan.md` | 技术方案：宪法检查表（原则一~八逐行对齐）、双阶段实施拆分、源码树与「已落地/待补」标注 |
| `research.md` | R1~R11 技术裁决（每条含 Alternatives considered），含与立项输入的显式偏差（R4 落位） |
| `data-model.md` | `web_users.roles`（阶段二）+ `authz_events`（阶段二）+ V8 双轨迁移 + 配置面 + 不新增的 |
| `contracts/authorization-contract.md` | 主体与裁决承诺、角色矩阵正典、配置与启动校验、路径→动作全表（**已标注为阶段二目标态**）、拒绝审计、兼容性承诺 |
| `quickstart.md` | V1~V6 人工走查（每项挂 US/SC 映射）+ 收尾 |
| `tasks.md` | T001~T046 分 6 阶段、`[ID] [P?] [Story]` 格式、Checkpoint、依赖/并行/实施策略 |
| `checklists/requirements.md` | spec 质量清单（16 项英文模板 + 中文 Notes） |
| `acceptance-report.md` | 本文件 |

**实现快照（2026-09-14，工作树未提交）**：

| 面 | 文件 | 结论 |
|----|------|------|
| core 主体 | `oryxos-core/.../core/auth/Principal.java` | `Kind{USER,API_KEY,ANONYMOUS}`、`ANONYMOUS_ID`、`user/apiKey/anonymous` 工厂、`isAnonymous/hasRole/isAuthorizable/describe`、`null` 归一、角色冻结 |
| core 角色 | `oryxos-core/.../core/auth/Role.java` | `VIEWER/EDITOR/ADMIN` 三档与语义 javadoc |
| core 词表 | `oryxos-core/.../core/policy/Action.java` | 11 个动作，读/运行/管理三族 |
| core 资源 | `oryxos-core/.../core/policy/ResourceRef.java` | `type+id` + `TYPE_*` 常量与工厂 |
| core 决策 | `oryxos-core/.../core/policy/AuthorizationService.java` | `ALLOW_ALL` + `decide` + 嵌套 `Decision{ALLOWED, denied}` |
| core 实现 | `oryxos-core/.../core/policy/RoleBasedAuthorizationServiceImpl.java` | 三档 `EnumSet` 叠加、API Key 上限（减成员/策略）、角色不取并集、`matrix()` 只读视图 |
| web 配置 | `oryxos-web/.../config/{WebRbacProperties,RoleMappingProperties,AuthorizationConfig}.java` | 开关与默认档、`enabled=false` 注入 `ALLOW_ALL`、`RbacEnforcer` Bean |
| web 承载 | `oryxos-web/.../security/PrincipalHolder.java` | 请求属性 `io.oryxos.web.principal`，未认证读回匿名主体 |
| web 强制点 | `oryxos-web/.../security/RbacEnforcer.java` | flag 关恒放行；启用时**只做基线判定**（`READ_WORKSPACE`）与 403 + WARN 日志 |
| web 接线 | `oryxos-web/.../security/ApiKeyAuthFilter.java`、`.../config/ApiKeyFilterConfig.java` | 两处成功分支置主体并调强制点；`PROTECTED_URL_PATTERNS` 数组未改 |
| 其它 | `oryxos-storage/.../ApiKeyService.java`（+`findNameByPlaintext`）、`config/application.yml.example` | 与契约一致 |

## 真机走查（quickstart V1~V6）

**未执行**。quickstart V1~V6（含管理台三档角色走查、Chromium 截图留证）待阶段二（角色落库）完成后补填——阶段一没有角色可赋，V2/V3 的「VIEWER 被拒 / EDITOR 放行」在真机上无法演示。

由实现方在阶段二完成后补填本节与截图。

## SC 达成情况

| SC | 口径 | 判定 | 备注 |
|----|------|------|------|
| SC-001 默认档零破坏 | 缺省 `enabled=false` 时全量既有测试零改动全绿 | ⚠️ 部分 | 既有 37 个认证测试全绿 + 2 条副作用断言 + E2E；`mvn verify` 的 PMD/P3C/SpotBugs 两道门未验证（见「自动化测试」） |
| SC-002 裁决表 100% | 路径 × 三档角色全覆盖用例 + 越权动作零执行 | ⏳ 待验收 | 依赖差距 1、2 |
| SC-003 拒绝 100% 可筛 | `authz_events` 字段齐全、放行零落库 | ⏳ 待验收 | 依赖差距 3 |
| SC-004 不泄露/不混淆 | 403 统一信封、无权与不存在不可区分 | ⏳ 待验收 | 部分：`RbacEnforcerTest` 已断言拒绝不带 HTML 内容类型；统一信封与「不可区分」需 T026 用例与真机走查 |
| SC-005 端点覆盖 100% | 启动期枚举 + 遗漏即拒 | ⏳ 待验收 | 依赖差距 4 |
| SC-006 开销 | `decide` ≤0.1ms、每请求 ≤1 次索引查询、p99 <1% | ⏳ 待验收 | 未实测 |
| SC-007 文档同步 | CLAUDE.md / application.yml.example / CliGuide / website 中英文 | ⚠️ 部分 | `application.yml.example` 已改；其余四项待办（T041~T043） |

## 实现与设计偏差（未实现清单，按阻断程度排序）

1. **路径 → 动作映射未落（阻断 US1）**：`RbacEnforcer` 目前只对每请求做 `READ_WORKSPACE` 基线判定，`contracts §4` 的全表（`RequestActionResolver`）与「未登记路径 fail-closed」均未实现——EDITOR/ADMIN 的矩阵差异在任何真实请求上都不会生效。**契约已加显式状态标注，避免读者误判粒度。**
2. **角色未落库（阻断 US1）**：`web_users.roles` 列、`WebUserService.rolesOf/setRoles`、CLI `oryxos user role`、V8 迁移均未实现；当前管理台账号一律落入 `default-user-roles=ADMIN`，VIEWER/EDITOR 两档只存在于单测。
3. **拒绝审计未落库（阻断 US2）**：`authz_events` 表与相关组件未实现；当前拒绝留痕只有 `RbacEnforcer` 的结构化 WARN 日志，SC-003 不成立。
4. **启动校验未落（阻断 SC-005）**：`RbacStartupCheck` 四组合（apikey 未开 / 无 ADMIN / auth 未开 / 未知角色）与端点全覆盖校验均未实现。
5. **`BasicAuthFilter` 未接线**：`/admin/**` 的认证成功分支尚未置主体、未调强制点。控制台数据面走 `/api/v1/**` 已由 `ApiKeyAuthFilter` 覆盖，故不影响「管理台数据面被裁决」，但影响「`/admin/**` 请求主体可被后续特性读取」。
6. **`/api/v1/auth/me` 角色字段未落**：`AuthMeView` 仍是 `(authenticationEnabled, username)`。
7. **V8 迁移未落**：两 vendor 目录当前止于 V7。
8. **未执行的部分**：真机走查、浏览器走查、性能实测（SC-006）、双库迁移验证。

## 待 maintainer 裁决（阻断本刀验收）

1. **路径 → 动作映射边界**（spec.md §Clarifications 第 4 条，推荐按 `contracts §4` 全表 + 启动期全覆盖校验）。
2. **角色是否与本刀同批落库**（第 5 条「待补」，推荐落：V8 加列 + CLI 赋值 + 默认档收紧为空 + 无 ADMIN 拒启）。
3. 边界术语（第 1 条）、`/admin/**` 语义是否原样保留（第 2 条）、API Key 归属（第 3 条）。
4. **P3C 命名/落位**（第 5 条「待确认」）：见「自动化测试」的未验证项。

## 实现期风险与待确认（本次设计发现的冲突）

1. **`READ_AUDIT` 归入 VIEWER 与既有脱敏口径冲突**：`oryxos-web/.../audit/Redactor.java` 的 javadoc 记录「脱敏是展示层行为、库里保留原始值」，其正当性依赖「库访问 = 运维特权边界」；把审计读取面放开给只读角色（VIEWER）后，这条论证不再自动成立。建议确认：审计读取是否应至少收到 EDITOR，或 `READ_AUDIT` 单独列为 ADMIN 档。
2. **API Key 主体在授权开启后默认全拒**（`default-api-key-roles` 默认空）：机器调用方需要部署方显式授予角色才可用，属预期行为但**是行为变更**——升级说明必须写明，否则会被当作故障。
3. **`default-user-roles=ADMIN` 的过渡值**：仅防单机锁死；多人部署开启 RBAC 前必须显式调低，否则「开启授权」等于「没有授权」。与偏差 2 打包解决。
4. **会话有效期 × 撤权**：012 的既有裁决是「session 有效即通过、不查 `web_users.enabled`」；本设计用「每请求重解析角色」保证撤权即时，但账号被删/禁用时其 session 仍存活到过期（降为无角色 → 拒绝）。若要求更强即时性，需改 `WebSessionService.findValid` 并同步 012 的裁决。
5. **渠道入站回执面（`/api/v1/channels/inbound/**`）纳入 `MANAGE_CHANNELS` 会打挂线上渠道**（调用方是 IM 平台、凭证是部署级 Key），本设计已登记为「只认证不裁决」——请评审确认该豁免。
6. **审计写失败策略**：既有 `ToolExecutor.recordCompleted` 的审计是 fail-open；本刀的拒绝审计是「写失败不改变裁决（拒绝照旧）」。两者方向一致（旁路失败不影响主流程）但语义不同，已在契约 §5 显式写明。

## 质量门禁

| 门 | 状态 | 说明 |
|----|------|------|
| Spotless（google-java-format） | ✅ 绿 | `mvn spotless:apply` 后全仓 `check` 通过 |
| Checkstyle | ✅ 绿 | 全仓通过 |
| 单元测试 | ✅ 绿 | 1033 tests / 0 failures（含新增 58 个授权用例） |
| P3C / PMD | ⏳ **未验证** | 本机 mojo 无法加载（见下）；须由 CI 证明 |
| SpotBugs + FindSecBugs | ⏳ **未验证** | 同上 |
| OWASP Dependency-Check | ⏳ 未跑 | 本地默认 `owasp.skip=true`，CI 负责 |

**本机无法验证 PMD/P3C 与 SpotBugs 的根因（环境问题，非仓库代码问题）**：`maven-pmd-plugin:3.21.2` 的 mojo 在 Maven 3.9.9 下加载即失败（缺 `org.apache.maven.reporting.MavenMultiPageReport`，因 Maven 3.9.x 移除了插件依赖的旧 `maven-reporting-api` API）；换 Maven 3.8.8 同样失败（实测）；在本地仓库给插件 POM 补依赖后改为缺 `doxia.logging.LogEnabled`，属逐层依赖缺失，已放弃并还原。**因此本报告不声称「门禁全绿」。**

**复测计划**：V8 落地后复跑 `MigrationEvolutionIT` / `LegacyTakeoverIT` / `PostgresStorageE2ETest.assertFlywayHistoryHealthy`（存量库无损）；阶段二完成后补 quickstart V1~V6 真机走查与 SC-006 计时。
