# Quickstart: 密钥加密存储验收

**Feature**: 022-secret-encryption | 契约详见 [contracts/secret-storage.md](contracts/secret-storage.md)

## 前置

```bash
mvn -q -DskipTests package
alias oryxos='java -jar oryxos-boot/target/oryxos-boot-*.jar'
```

## V1 — 零配置首启（US2 / SC-003）

```bash
# 全新工作区、不设任何环境变量
oryxos serve --port 8080 &
ls -la .oryxos/master.key   # 期望：存在，权限 -rw-------（0600）
# 期望：启动正常、管理台可用，全程无需理解密钥概念
```

## V2 — 落库即密文（US1 / SC-001）

```bash
# 管理台或 API 录入一个 Provider（带 api_key）与一个 email 通知渠道（config 含 password）
sqlite3 .oryxos/oryxos.db "SELECT api_key FROM providers;"
# 期望：enc:v1:… 密文，无明文
sqlite3 .oryxos/oryxos.db "SELECT config FROM notify_channels;"
# 期望：password 值为 enc:v1:…；host/port/from 等保持明文
```

## V3 — 功能回归（US1 / SC-002）

```bash
# 用真实 key 的 Provider 发起一次对话（或 mock 场景跑通全链）；触发一次邮件通知
# 期望：LLM 调用与通知发送行为与加密前一致（凭证使用时正确还原）
```

## V4 — 存量明文迁移（US1 / SC-002）

```bash
# 模拟升级前旧库：手工把某行改回明文
sqlite3 .oryxos/oryxos.db "UPDATE providers SET api_key='sk-plaintext-legacy' WHERE name='<n>';"
# 重启
# 期望：日志「已加密 N 条凭证」；该行变回 enc:v1:…；再重启不再迁移（幂等）
```

## V5 — 密钥丢失拒启指路（US2 / SC-004）

```bash
mv .oryxos/master.key /tmp/master.key.bak
oryxos serve --port 8080
# 期望：启动失败，报错含「已有 N 条加密凭证无法解密」+ 两条恢复路径；不静默降级、不清数据
mv /tmp/master.key.bak .oryxos/master.key   # 恢复后正常启动
```

## V6 — 环境变量档优先（US2）

```bash
export ORYXOS_MASTER_KEY=$(base64 -w0 .oryxos/master.key 2>/dev/null || openssl rand -base64 32)
# 用与文件不同的新钥匙启动 → 按 V5 拒启口径报「密钥不匹配」；
# 用文件内容的 Base64 启动 → 正常（环境变量优先且值一致）
# 另：设置格式非法的值（如 ORYXOS_MASTER_KEY=short）→ 启动即报格式错误
```

## V7 — 管理台不回显明文（US3 / SC-005）

```bash
curl -s localhost:8080/api/v1/notify-channels | python3 -m json.tool
# 期望：config.password 为 ****+末4位 掩码，响应无明文
# 浏览器 /admin/：编辑该渠道不动密码保存 → 通知照发（未修改判定）；输入新密码保存 → 新值生效
# Provider 页回归：api_key 掩码回显如旧
```

## 质量门禁

```bash
mvn verify
```
