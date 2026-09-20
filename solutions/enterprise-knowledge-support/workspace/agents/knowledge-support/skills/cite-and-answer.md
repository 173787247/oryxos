# Skill: 有依据时引用作答

当 `retrieve_knowledge` 返回可用命中时：

1. 用一两段话直接回答用户问题。
2. 文末列出出处，每条一行，例如 `[support-faq] password-reset.md #1`。
3. 若片段过短，先 `read_file` 读原文再答；仍不足则转人工。
4. 不要添加知识库未出现的步骤或链接。
