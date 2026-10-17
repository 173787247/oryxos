# MCP server 接入指南

本文说明 OryxOS 作为 MCP **客户端**如何接入外部 MCP server：配置写在哪、三种配置路径、传输选型、超时语义，以及常见故障该读哪一行日志。

> 范围：`.oryxos/mcp_servers.yaml`、管理台 API、以及内置目录。MCP server 自身怎么实现、怎么部署，不在本文范围。

**字段的"正典"在 [`config/mcp_servers.yaml.example`](../config/mcp_servers.yaml.example)** —— 逐字段带注释（`transport` 的取值与回退语义、`request_timeout` 的边界、`env` 的 `${VAR}` 占位符都在那里）。本文**只做指路与选型**，不复制 schema：两处写同一份 schema 必然漂移。

## 一、三条配置路径

1. **文件**（最常见）：

   ```bash
   cp config/mcp_servers.yaml.example .oryxos/mcp_servers.yaml
   ```

   示例里所有 server 默认都是注释掉的 —— 空文件**不连任何 server**，只取消注释你真正要的那几个。

2. **管理台 / HTTP API**：

   | 端点 | 用途 |
   |---|---|
   | `GET/POST/PUT/DELETE /api/v1/mcp-servers` | 增删改查 server 配置（改完立即尝试连接，不用重启）|
   | `GET /api/v1/mcp-servers/status` | 连接状态、`error`、已注册的工具名 |
   | `GET /api/v1/mcp-servers/catalog` | 内置的公开 server 目录（静态数据），可一键启用 |

3. **Agent 侧白名单**（决定"谁能用"）：`AGENT.md` 的 `mcp_servers:` 列出该 Agent 允许使用的 server 名。

   ```yaml
   mcp_servers:
     - github-mcp
   ```

   不在此列的工具**模型调不到**（`ToolExecutor` 按工具归属校验）。配了 server 但 Agent 没写进白名单，是最常见的"配了却不生效"。

## 二、传输选型

| `transport` | 说明 | 什么时候用 |
|---|---|---|
| `streamable` | Streamable HTTP —— MCP 规范当前推荐的远程传输 | 远程 server 的首选 |
| `sse` / `http` | 旧 SSE 传输；**已被 MCP 规范废弃** | 只用于还没升级的远端 |
| `auto` | 先 Streamable；**仅在初始化阶段的明确 legacy 响应**时同端点回退 SSE；**工具调用不切换、不重放** | 远端版本不明时 |
| `stdio` | 本地子进程。`command` 是**一行**、按空白切分（第一个 token 是可执行文件），**没有单独的 `args` 字段** | 本地工具、CLI 型 server |

`headers` / `url` 用于远程传输；`command` / `env` 用于 stdio。根路径 URL 会补默认路径（`sse`/`http` → `/sse`，`streamable`/`auto` → `/mcp`）；**已知端点时请写全路径**，不要让它猜。

## 三、请求超时

`request_timeout`（整数秒，**1..3600，缺省 30**）按 **server** 配，stdio / SSE / Streamable 三个传输都生效。它解决的是**长任务型工具**（ETL、部署、大文件转换）到点被杀 —— 这类超时以前是全仓写死的 30s。

它**不**改变两件事：

- SDK `initialize` 握手自己的超时（约 20s，见示例文件注释）；
- 启动与 `tools/list` 探测的上限（60s，`MAX_CONNECT_PROBE_TIMEOUT`）。

所以「连不上」和「工具跑得久」是**两个**超时。不要试图用调大 `request_timeout` 去解决连接慢。

## 四、失败与安全语义（读日志之前先知道）

- **连不上 = WARN + 跳过**：应用照常启动，该 server 的工具不进注册表、也不在 `/api/v1/tools` 里。表现为每个连不上的 server **等一次超时**（缺省约 30s）—— 多个坏 server 会叠加启动时间。
- **工具重名 = 整个 server 被跳过**：某个 server 暴露了与内置工具同名的工具（例如 `read_file`）时，`ToolRegistry` 拒绝注册，该 server **已注册的其他工具也会全部回滚**，并打一条 WARN 带上 server 名与冲突的工具名。这是一条安全属性：外部进程不能顶掉沙箱里的文件、Shell、记忆工具。
- **密钥一律走 `${ENV_VAR}`**：`env` / `headers` 的值支持 `${VAR}` 占位符；缺失时保留字面量并 WARN。**不要把密钥写进仓库**（示例文件里也这么写）。

## 五、故障排查

| 现象 | 先看什么 |
|---|---|
| 工具没出现 | `GET /api/v1/mcp-servers/status` 的 `connected` / `error`；日志 `MCP server <name> 连接失败，跳过它的工具: …` |
| 工具出现了但模型调不到 | `AGENT.md` 的 `mcp_servers:` 白名单（第一节第 3 条）|
| 启动变慢 | 是否有连不上的 server：每个等一次超时（`request_timeout`，缺省 30s）|
| 提示工具重名 | 日志 `工具重名，拒绝注册: <tool>` —— 该 server 整个被跳过（第四节）|
| `auto` 没有回退到 SSE | 回退只发生在**初始化阶段的明确 legacy 响应**；鉴权、网络、以及工具调用阶段的错误都不回退 |
| 长任务工具被中断 | 调 `request_timeout`（1..3600）；握手仍是约 20s，别指望它解决连接慢 |

## 六、想进一步了解

- 字段细节与合法取值：`config/mcp_servers.yaml.example` 的注释（**以它为准**）。
- 传输的兼容性测试：`oryxos-tool/src/test/java/io/oryxos/tool/mcp/McpTransportCompatibilityTest.java`（`auto` 的回退边界在用例里写得很清楚）。
- 启动级验证：`oryxos-boot/src/test/java/io/oryxos/boot/McpServerBootE2ETest.java`（真实拉起一个 stdio server 并调用工具）。
