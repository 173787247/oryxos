# Acceptance Report: Persona 人格 + 人格库（copy-in 模板库）+ Agent 导入

> **状态**：待人工验收（stub）。本报告由用户按 [quickstart.md](./quickstart.md) 走完端到端验证后补填；自动化门禁证据见下方「已自动验证」节。

## 已自动验证（Phase 5 全量回归，质量门禁）

- [x] `mvn test` 全模块 reactor 绿（含 spotless / checkstyle / PMD / spotbugs）
- [x] 迁移测试随实现落地：
  - core：`ProfileLoaderTest` persona 解析契约、`ContextLoaderTest` 注入位置 + 缺省零改变、`AgencyAgentsParserTest`、`AgencyAgentsImporterTest`、`AgentLifecycleServiceTest`（importAgent / updatePersona / validateAgent / 冲突拒绝）
  - persona：`PersonaPresetCatalogTest` + `PersonaPresetsGoldenTest`（12 内置 + golden 逐字节）、`PersonaServiceTest`、`PersonaStoreTest`
  - web：`PersonaApiControllerTest`（5 端点）、`AgentApiControllerTest`（persona 投影 / import-preview / import / PUT persona）

## 待人工验收（T032，quickstart.md 场景 1-7）

- [ ] 场景 1：CLI 手工导入 —— 产物 AGENT.md 断言 + 重名拒绝
- [ ] 场景 2：热加载体感 —— serve 期间导入即刻可见/可聊
- [ ] 场景 3：管理台人格卡 + personaEdit 表单（PUT 生效、name/role 必填）
- [ ] 场景 4：Web 导入两步走（预览不落盘 → 确认落盘 → 中文名显式 name 报错）
- [ ] 场景 5：人格库页（12 内置只读 + 自定义 CRUD + 重启持久 + copy-in 隔离）
- [ ] 场景 6：预览校验（坏源 200 + valid=false；预览不 bypass 落盘校验）
- [ ] 场景 7：校验回滚（坏 frontmatter 导入无残留半目录）

## 结论

**通过 / 不通过**（用户验收后勾选），遗留问题 / 备注：

---
