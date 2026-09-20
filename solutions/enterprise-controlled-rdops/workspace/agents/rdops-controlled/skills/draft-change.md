# Skill: 起草运维变更

对入站变更请求输出纯文本草案（供 Flow 下游人工审阅）：

```text
CHANGE_DRAFT
change_id: <id>
risk: low | medium | high
summary: <一两句话>
proposed_command: <建议命令，一行>
needs_approval: yes
```

规则：

- 任何可写 / 可执行命令，`needs_approval` 必须为 `yes`
- 不编造未在请求/手册中出现的集群、命名空间或密钥
- 需要手册时先 `retrieve_knowledge`，必要时 `read_file` 跟读
- 草案中的 `proposed_command` **不是**最终执行参数；审批人可收窄或改写为 `approved_params`
