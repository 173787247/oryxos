# 验收报告: Tool Policy 工具策略（020）

**Date**: 2026-08-28 | **验收方式**: 自动化测试（26 用例）+ fat JAR 真机走查（quickstart V1~V7）+ Chromium 管理台策略页走查

## 自动化测试

| 套件 | 用例 | 结果 |
|------|------|------|
| `ToolPolicyServiceImplTest`（storage） | 9 | ✅ 全过（收敛三步/三重叠加/通配经归属表/有效集 ⊆ 声明集/空规则全允） |
| `ToolPolicyStartupCheckTest`（cli） | 4 | ✅ 全过（三类未知目标告警/全空告警/正常无告警/恒不抛） |
| `PolicyInterceptTest`（core） | 4 | ✅ 全过（事前过滤/事中拒绝零执行+审计标记/两点 ALLOW_ALL 等价） |
| `PolicyApiControllerTest`（web） | 6 | ✅ 全过（CRUD/400 校验/409 重复/unknownTarget/effective 视图） |
| `AuditSchemaUpgradeTest` 追加断言（storage） | — | ✅（blocked_by 列升级幂等） |
| `ToolPolicyE2ETest`（boot，真实 HTTP+SQLite+mock） | 3 | ✅ 全过（零策略现状/三道保险+审计筛选+热更新/例外与三重叠加） |

## 真机走查（quickstart V1~V7）

环境：WSL2，fat JAR `oryxos-boot-0.1.3-RELEASE.jar`，mock provider（第一轮固定调 save_memory——天然模拟「模型不看清单硬调工具」的幻觉场景），双 Agent（agent-a/agent-b）scratch 工作区。

| 走查 | 观测 | 结论 |
|------|------|------|
| V1 零策略现状 | 空规则时 invoke 全链路照旧，save_memory 成功执行 | ✅ |
| V2 全局 deny 三道保险 | 配 `GLOBAL_DENY save_memory` 后：mock 仍发起调用（幻觉模拟）→ 执行层拒绝、审计落 `blocked_by='policy'` 且 `error_message` 含「被平台策略禁止：命中全局禁用规则（#1）」；`GET /api/v1/audit/tool?blockedBy=policy` 精准筛出；effective 视图两 Agent 的 removed 均含命中原因 | ✅ |
| V3 MCP 通配 | 无 MCP server 走查环境——`server:*` 经归属表匹配、精确豁免通配、名字伪前缀不误伤由 `ToolPolicyServiceImplTest` 三个用例钉死 | ✅（单测口径） |
| V4 例外与三重叠加 | EXEMPT 后 agent-a 执行成功（success=1, blocked_by=null）、agent-b 仍被拒（blocked_by=policy）；再加 AGENT_DENY → agent-a 的 removed 变为「命中该 Agent 的定向禁用规则」 | ✅ |
| V5 热更新 | 删除全部规则后（不重启）下一次调用 save_memory 即恢复成功执行 | ✅ |
| V6 管理台策略页 | Chromium 走查 PASS：三个表格（规则/各 Agent 有效工具集/策略拒绝记录）渲染齐全、未知工具规则带 ⚠ 标记、页面新增规则后有效集即时联动、页面删除规则即刻生效（截图留证） | ✅ |
| V7 校验与告警 | `GLOBAL_DENY` 带 agentName → 400；未知工具名保存成功且 `unknownTarget: true`；重启后启动日志 WARN「工具策略规则 #4 指向未注册的工具 no_such_tool」（加载期告警，analyze G1 修正项验证） | ✅ |
| CLI 表现（T020） | `oryxos chat` 下被禁调用显示 `[调用工具 save_memory …]` → `[工具 save_memory 失败]` + 模型解释；审计同口径落列——无需 CLI 代码改动（019 打字机提示已覆盖），确认型任务结论落卷 | ✅ |

## SC 达成情况

| SC | 口径 | 结论 |
|----|------|------|
| SC-001 零破坏 | 空规则真机现状 + 两拦截点 ALLOW_ALL 等价单测 + 既有测试零改动全绿（`mvn verify`） | ✅ |
| SC-002 零执行 | 事中拒绝真机验证（幻觉调用被拒、success=0）+ PolicyInterceptTest `never().execute` | ✅ |
| SC-003 收敛确定 | 三重叠加真机 + 单测重复求值一致 | ✅ |
| SC-004 热更新 | 真机删规则即恢复 + E2E 断言 | ✅ |
| SC-005 审计可筛 | `blockedBy=policy` API 真机筛出 + 与普通失败可区分（blocked_by 空） | ✅ |
| SC-006 管理台可见 | V6 走查：任一 Agent 的「能用什么/少了什么/为什么」全可见 | ✅ |
| SC-007 走查 Demo | V2 完整演示：配 deny → 越权调用被拒留痕 → 审计按标记查到 | ✅ |

## 实现要点与偏差

- **模块边界纠偏**：`ToolPolicyStartupCheck` 计划放 oryxos-web，实施发现 web 不依赖 oryxos-tool（拿不到 ToolRegistry）——移至 oryxos-cli 由 `OryxOsRuntime` 以 `@Bean + @ConditionalOnWebApplication(SERVLET)` 装配，语义不变（tasks T008 落地路径与 plan 源码树以此为准）。
- **交互契约保全**（019 教训复用）：`ToolExecutor.fail` 在 blockedBy 为空时仍走 8 参 `record` 旧签名，全部既有 auditor stub/verify 测试零改动通过；`ToolInvocationAuditor` 新 9 参为 default 方法。
- **审计筛选落点**：管理台的「策略拒绝记录」做在策略页内（直接调 `?blockedBy=policy`），未侵入报表页结构——SC-005「可筛出」达成口径。
- **unknownTarget 分工**：API 层只标精确名（web 无工具归属表），通配的未知 server 由启动检查告警——两层合计覆盖 FR-008。

## 质量门禁

`mvn verify`（Spotless + P3C + Checkstyle + SpotBugs/FindSecBugs + OWASP Dependency-Check）：**BUILD SUCCESS**，全仓测试 0 失败 0 错误。过程记录：① P3C 命名规约要求 Service 实现类以 Impl 结尾——`JpaToolPolicyStore` 全局改名 `ToolPolicyServiceImpl`（与 `SpringAiProviderServiceImpl` 同例）；② SpotBugs 对 `ReActLoop` 构造报 EI_EXPOSE_REP2（PromptBuilder/ToolExecutor 因策略 setter 成为可变类）——按项目惯例带理由 Suppress；新 `fail` 重载补 CRLF 注解；③ 视图 record 的 List 组件以 compact constructor `List.copyOf` 保不可变（比 Suppress 更干净的修法）。
