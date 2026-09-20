# Skill: 无依据时安全转人工

触发条件（任一）：检索零命中、命中片段无法支撑完整答案、出处不可跟读。

输出（纯文本，勿伪造引用块）：

```text
ESCALATE
question: <用户原话>
searched: support-faq
reason: no_evidence | insufficient_span | citation_unreadable
notes: <已尝试的查询改写，可选>
```

然后停止猜测。由 Flow `knowledge-handoff` 将摘要交给人工节点。
