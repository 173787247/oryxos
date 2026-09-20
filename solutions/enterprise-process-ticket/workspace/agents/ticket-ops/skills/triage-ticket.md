# Skill: 工单分诊

对入站工单输出纯文本摘要（供 Flow 下游人工审阅）：

```text
TRIAGE
ticket_id: <id>
category: access | change | incident | other
risk: low | medium | high
summary: <一两句话>
proposed_change: <拟执行动作，一句话>
needs_approval: yes
```

规则：

- `risk` 为 medium/high，或拟执行写操作时，`needs_approval` 必须为 `yes`
- 不编造未在工单/手册中出现的系统名或账号
- 需要手册时先 `retrieve_knowledge`，必要时 `read_file` 跟读
