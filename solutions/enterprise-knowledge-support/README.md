# 企业知识与支持 Agent 标杆方案

Tracks: **#478**（Epic **#460**）。本目录是可复制的样例包：模板 Agent、权限样例、引用策略、转人工 Flow、评测集与部署验收——**不改默认运行时行为**。

## 包内容

| 路径 | 用途 |
|------|------|
| [ACCEPTANCE.md](ACCEPTANCE.md) | #478 验收映射与独立验收清单 |
| [citation-policy.md](citation-policy.md) | 可访问来源引用策略 |
| [permissions.sample.yml](permissions.sample.yml) | 知识支持场景工具权限样例 |
| [workspace/](workspace/) | 可直接拷入工作区的样例 |
| [scripts/accept.sh](scripts/accept.sh) | 新环境离线验收（无 LLM Key） |

## 快速落地

```bash
cp -a solutions/enterprise-knowledge-support/workspace/. <your-workspace>/.oryxos/
oryxos serve --port 8080
curl -s -X PUT localhost:8080/api/v1/agents/knowledge-support/knowledge/support-faq
./solutions/enterprise-knowledge-support/scripts/accept.sh
```

## 默认安全

- Agent `tools` 仅 `retrieve_knowledge` + `read_file`（无 shell / 无写文件）
- 权限样例全局 deny `shell` / `write_file` / `exec`
- 无检索命中或出处不可跟读时，**禁止编造引用**，走转人工 Flow
- 评测门禁与 048 harness 一致；`oryxos.eval.gate-enabled` 仍默认 **false**

## 与 #479 / #480 边界

本包只覆盖**知识问答与支持转人工**。工单审批（#479）与受控研发运维（#480）另包交付。
