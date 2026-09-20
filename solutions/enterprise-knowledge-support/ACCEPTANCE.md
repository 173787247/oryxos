# #478 验收映射

Parent: #460

## 范围对照

| 范围项 | 本包落点 |
|--------|----------|
| 知识支持模板 | `workspace/agents/knowledge-support/` + `knowledge/support-faq/` |
| 权限样例 | `permissions.sample.yml` |
| 引用策略 | `citation-policy.md` + Agent / Skill 正文 |
| 转人工流程 | `workspace/flows/knowledge-handoff.flow.md` |
| 评测集 | `workspace/evals/knowledge-support-suite.json`（CI 镜像见 oryxos-core test resources） |
| 部署验收 | 本文 + `scripts/accept.sh` + README 快速落地 |

## 验收标准

### 1. 回答附可访问来源引用

- 命中知识库后，回答必须带可跟读出处：`[support-faq] <rel-path> #<loc>`
- 出处路径必须落在已绑定库目录内，可用 `read_file` 打开原文
- 评测用例 `cite-password-reset` / `cite-vpn` 要求 citation 对齐

**怎么验**

```bash
./solutions/enterprise-knowledge-support/scripts/accept.sh
# 或
mvn -pl oryxos-core -Dtest=EnterpriseKnowledgeSupportPackTest,FlowDocumentsTest test
```

### 2. 无依据时安全转人工

- 零命中 / 片段不足 / 出处不可用 → **不得编造引用**，触发转人工
- Flow `knowledge-handoff`：Agent 判定后进入 `human` 节点
- 评测用例 `no-evidence-escalate` 与 `handoff-flow` 必须成功

### 3. 新环境可按文档独立验收

干净 clone → 不配真实 API Key → `scripts/accept.sh` exit 0 即可完成离线门禁。

## 非目标

- #479 审批工单 / 失败可恢复副作用
- #480 受控研发运维危险写权限
- 打开 `oryxos.eval.gate-enabled` 默认值（保持 false）
