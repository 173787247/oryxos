# Feature Specification: 容器级执行隔离（Container Sandbox）

**Feature Branch**: `023-container-sandbox`

**Created**: 2026-09-01

**Status**: Draft（issue #334 认领后的首版草案，5 处待裁决，见 Clarifications）

**Input**: User description: "容器级执行隔离（方向 F，issue #334）：Agent 的 shell 工具目前执行在 OryxOS 进程内，沙箱白名单是应用自觉的策略层——检查者与被检查者同处一个信任域，近两个月的十几个安全修复全部是在封堵绕过手法（symlink 别名/TOCTOU/隧道地址），注定持续对抗。本特性把执行位置迁到内核强制的容器边界：ToolExecutor 的 shell 执行后端可插拔——local（默认，现状零依赖零变化）/ docker（可选，短命容器执行）/ ssh（远程，后续档）。与 015 记忆后端三档、020 Tool Policy 构成纵深防御：Policy 管意图、白名单管准入、容器管爆炸半径，三层互补不替代。落点参照既有 ProcessStarter 接缝（现为包级测试用，ProcessBuilder 为默认实现），docker 档走 CLI 零新依赖。明确不做：file/http 工具容器化、容器化 OryxOS 挂 docker socket（advanced 文档化）、SSH 档（后续）。"

## Clarifications（5 处待裁决，均附推荐）

### RQ-1 · docker socket 安全的默认形态

**问题**：OryxOS 容器化部署（PR #331）+ docker 档时需挂 `/var/run/docker.sock`，socket ≈ 宿主机 root。
**推荐**：**Phase 1 只承诺宿主机直跑 OryxOS + docker 档**（OryxOS 进程直接调宿主 docker CLI，无 socket 挂载问题）；容器化 OryxOS + docker 档标注为 advanced 路径，文档给出 socket proxy（Sysbox）/ rootless podman / K8s Job 三种候选与风险说明，不在本期验收范围。
**理由**：单二进制宿主机直跑是项目当前的典型形态（tar.gz 路径）；socket 挂载的安全设计值得独立特性消化。

### RQ-2 · 生命周期粒度

**问题**：每次工具调用起短命容器（隔离最干净，延迟 ~几百 ms）还是每 Agent 长驻容器（低延迟但隔离变粗、状态延续引入新问题）？
**推荐**：**Phase 1 只做短命容器（`docker run --rm`）**。长驻容器作为后续优化档（有真实延迟诉求再评估）。
**理由**：与「爆炸半径最小化」的立项动机一致；长驻容器的状态清理、崩溃恢复、复用隔离是另一整个问题域。

### RQ-3 · 后端覆盖范围

**问题**：只有 shell 走后端，还是 file / http 工具也进容器？
**推荐**：**Phase 1 仅 shell**。file 工具继续本地直操作（工作区在卷/磁盘上，挂进容器后容器内写入对 file 工具天然可见，见 FR-014）；http 工具维持现状（其沙箱是域名白名单 + DNS pinning，与执行位置正交）。
**理由**：shell 是真正的任意代码执行面；file/http 的既有防线与执行位置无关，容器化它们的收益远小于成本。

### RQ-4 · 工作区挂载与路径映射

**问题**：Agent 的脚本/文件在容器内如何可见？路径如何双向翻译？
**推荐**：**`ORYXOS_ROOT` 整体只读挂载？否——读写挂载到容器内固定路径 `/workspace`**。入参中的工作区绝对路径翻译为 `/workspace/...`；审计记录保留宿主路径；容器输出里出现的 `/workspace/...` 反译回宿主路径（便于模型与用户理解）。
**理由**：Agent 的典型用法是「执行工作区里的脚本并读写产出」，只读挂载会砍掉一半场景；固定挂载点让翻译规则可预测、可测试。

### RQ-5 · 资源限额默认值

**问题**：cgroup 限额（--memory / --cpus）默认开还是默认关？
**推荐**：**默认开启**：`--memory 512m --cpus 1.0`，全局配置可调，按 Agent 可覆写；另默认 `--network none`（需要联网的 shell 场景显式配置打开）与 `--read-only` + `--tmpfs /tmp`。
**理由**：「安全是地基」原则下默认值应安全优先而非性能优先；网络默认隔离与 http 工具的域名白名单形成一致的纵深口径。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - docker 档最小闭环：shell 命令在短命容器里执行 (Priority: P1)

管理员在 `config/application.yml` 配置 `oryxos.sandbox.backend: docker` 与执行镜像后重启。此后所有 Agent 的 `shell` 调用：白名单与 Tool Policy 照常前置（意图与准入检查一个不少），实际执行发生在 `docker run --rm` 的短命容器里——工作区挂载在 `/workspace` 双向可见，命令结束后容器销毁不留痕迹，审计记录多出「backend=docker + 容器 ID」。未配置 backend 的部署一切与现状完全一致（local 档零回归）。

**Why this priority**: 本特性的本体——把执行位置从「应用自觉」迁到「内核强制」。全局最小闭环独立可用，不依赖任何按 Agent 配置。

**Independent Test**: 配 docker 档 → 让 Agent 执行 `cat /etc/os-release`：返回容器发行版而非宿主的；执行 `touch /workspace/.oryxos/tmp-marker`：宿主侧文件工具立即可见该文件；`docker ps -a` 过滤无残留容器；审计含容器 ID。改回 local（或不配）→ 同命令返回宿主发行版，全量既有测试零修改通过。

**Acceptance Scenarios**:

1. **Given** backend=docker 且镜像已配置，**When** Agent 调用 `shell`，**Then** 命令在容器内执行（容器内文件系统视角），工作区路径入参被翻译为 `/workspace/...`，命令结束后容器被 `--rm` 清理。
2. **Given** 同上，**When** 容器内命令写 `/workspace` 下文件，**Then** 宿主侧该文件立即存在且 file 工具可读（双向映射）。
3. **Given** 同上，**When** 命令执行超过工具超时，**Then** 容器被终止（不泄漏，见 FR-006），调用按既有超时语义失败。
4. **Given** backend=docker 但 docker daemon 不可用，**When** Agent 调用 shell，**Then** 返回清晰错误（含排障提示）、审计留痕、OryxOS 进程不崩；**不静默回落 local**。
5. **Given** 未配置 backend（默认 local），**When** 任意 Agent 任意工具调用，**Then** 行为与本 feature 之前完全一致（回归零破坏）。
6. **Given** shell 命令试图访问网络（如 curl），**When** 默认 `--network none`，**Then** 连接失败（网络默认隔离）。

---

### User Story 2 - 按 Agent 覆写与资源限额 (Priority: P2)

全局 docker 档之下，运维 Agent（ops-agent）需要原生机能（如访问宿主 docker CLI 本身）：其 `AGENT.md` frontmatter 写 `sandbox: local`，仅它回落本地执行。反向：全局 local 的保守部署里，单个处理外部输入的 Agent 可单独 `sandbox: docker` 收紧。资源限额同理可按 Agent 覆写——给跑批 Agent 更大的 `--memory`。

**Why this priority**: 全局一刀切必然产生例外（与 020 US2 的 exempt 同构）；依赖 US1 的后端框架，故 P2。

**Independent Test**: 全局 docker + ops-agent 配 `sandbox: local` → 该 Agent `cat /etc/os-release` 返回宿主发行版，其余 Agent 返回容器的；Agent A 配 `sandbox.docker.memory: 1g` → 该 Agent 容器的 Memory Limit 为 1g（`docker inspect` 验证），其余为默认值。

**Acceptance Scenarios**:

1. **Given** 全局 docker 且 Agent X frontmatter `sandbox: local`，**When** X 调用 shell，**Then** 本地执行（与现状一致）；其余 Agent 仍走容器。
2. **Given** 全局 local 且 Agent Y frontmatter `sandbox: docker`（含镜像继承全局配置），**When** Y 调用 shell，**Then** 容器内执行。
3. **Given** Agent 级覆写了 memory/cpus，**When** 其容器启动，**Then** cgroup 限额按 Agent 值生效（`docker inspect` 可证）。
4. **Given** frontmatter 的 sandbox 值非法（未知档位/拼写错误），**When** Agent 加载，**Then** 启动期清晰告警并按全局档执行（不静默、不阻断其它 Agent）。

---

### User Story 3 - 管理台后端状态页 (Priority: P3)

管理员在管理台看到执行后端的全局档位、docker 可用性探测结果（daemon 活着吗、CLI 版本）、当前生效镜像与资源限额默认值、各 Agent 的覆写一览。只读为主，改配置仍走 application.yml / Agent 目录（GitOps 路径不动）。

**Why this priority**: 「配置即责任」要成立，配置结果必须可见——回答「现在命令到底在哪执行」。纯可视化增强，不影响 US1/US2 正确性。

**Independent Test**: 配 docker 档 + 停掉 daemon → 状态页档位显示 docker 但可用性标红；Agent 覆写列表与 frontmatter 实际内容一致。

**Acceptance Scenarios**:

1. **Given** backend=docker 且 daemon 正常，**When** 打开状态页，**Then** 显示档位、daemon 可达、镜像 digest、默认限额。
2. **Given** daemon 停止，**When** 打开状态页，**Then** 可用性标红并给出排障提示（与 SC-006 的运行时错误口径一致）。

## Edge Cases

1. 镜像配置了但本地不存在：启动校验尝试预拉取；失败则启动报错（fail loud，不进「配置了却静默不生效」状态）。
2. 白名单允许的命令在镜像内不存在（如 alpine 无 bash）：错误信息须指明「容器内未找到」，区别于宿主语义。
3. 工作区挂载与容器内 uid 映射：容器内 root/非 root 写卷的权限差异——执行用户固定非 root（`--user`），文档明示卷属主要求。
4. docker CLI 不在 PATH：启动校验即报错（档位为 docker 时）。
5. 并发多次 shell 调用：多个短命容器并存，无共享状态；超时清理不误伤他人容器（按容器 ID 精确 kill）。
6. Windows / macOS Docker Desktop：挂载路径翻译经 Docker Desktop 的文件共享层，行为差异文档化（开发环境为主，生产形态是 Linux）。
7. Agent 声明 docker 档但全局未配镜像：按「镜像缺失」fail loud 口径（EC-1），不隐式选默认镜像。
8. 超长输出：容器 stdout/stderr 经现有 ShellTools 的截断与字符集处理，行为不变。

## Requirements *(mandatory)*

### Functional Requirements

需求用语约定：MUST = 必须满足；MUST NOT = 必须禁止。FR/US/SC 编号为稳定标识符。

| # | 需求 | 备注 |
| --- | --- | --- |
| FR-001 | 现有包级 `ProcessStarter`（ShellTools 内）MUST 升格为公开扩展点（迁至 `io.oryxos.tool.sandbox`），local 档 = 现 `ProcessBuilder` 实现为默认，行为逐字节不变 | 接缝已存在，升格即可 |
| FR-002 | docker 档 MUST 以 `docker run --rm` 短命容器执行白名单校验后的命令，经 CLI 调用（ProcessBuilder 起 docker 进程），MUST NOT 引入 docker SDK / 新运行时依赖 | 宪法「零仪式」同源 |
| FR-003 | `ORYXOS_ROOT` MUST 读写挂载到容器内固定路径 `/workspace`；shell 入参中的工作区绝对路径 MUST 翻译为 `/workspace/...`，审计 MUST 记录宿主原始路径 | RQ-4 |
| FR-004 | docker 档默认安全参数 MUST 生效：`--network none`、`--read-only` + `--tmpfs /tmp`、`--memory 512m`、`--cpus 1.0`、非 root `--user`；各项 MUST 可配置覆写 | RQ-5 |
| FR-005 | 档位为 docker 时 MUST 显式配置执行镜像（`oryxos.sandbox.docker.image`）；启动校验 MUST 验证 CLI 存在 + daemon 可达 + 镜像可用（必要时预拉取），任一失败 fail loud | EC-1/4/7 |
| FR-006 | 工具超时 MUST 终止容器本体而非仅杀 docker CLI 进程（如 `--cidfile` + destroy 联动 `docker kill`）；MUST NOT 留下泄漏容器 | SC-004 |
| FR-007 | 既有检查顺序 MUST 不变：Tool Policy（020，事前不可见/事中拒绝）→ 白名单 enforce → 后端执行；后端 MUST NOT 跳过或替代前两层 | 三层互补 |
| FR-008 | `tool_invocations` 审计 MUST 记录执行后端标识（local/docker）与容器 ID（docker 档）；local 档记录与本 feature 之前一致 + backend=local | 兼容既有查询 |
| FR-009 | 全局配置 MUST 支持 `oryxos.sandbox.backend: local|docker`（默认 local）与 `oryxos.sandbox.docker.*`（image/memory/cpus/network/user/tmpfs） | |
| FR-010 | Agent MUST 可经 AGENT.md frontmatter（`sandbox.backend` + 限额覆写）覆写全局档位；非法值告警并回落全局（EC） | US2 |
| FR-011 | docker 不可用（daemon 停止/CLI 缺失）时 MUST 以清晰错误失败并留审计，MUST NOT 静默回落 local 执行 | 安全语义：档位是承诺 |
| FR-012 | 管理台 MUST 提供后端状态页：档位、daemon 探测、镜像信息、默认限额、Agent 覆写一览（只读） | US3 |
| FR-013 | local 档（含未配置）MUST 保持全量既有行为：既有测试零修改通过 | SC-001 |
| FR-014 | file 工具维持本地直操作；容器内对 `/workspace` 的写入对 file 工具 MUST 立即可见（同一文件系统），路径语义文档化 | RQ-3 |
| FR-015 | http 工具、MCP 工具的执行位置 MUST NOT 因本特性改变 | RQ-3 |

### Key Entities

```yaml
# config/application.yml（新增段）
oryxos:
  sandbox:
    backend: local            # local | docker，默认 local
    docker:
      image: ""               # 必填（backend=docker 时），如 python:3.12-alpine
      memory: 512m
      cpus: "1.0"
      network: none           # none | default | <自定义网络名>
      user: "65534:65534"     # 非root执行用户（nobody）
```

```yaml
# AGENT.md frontmatter（新增可选字段）
sandbox:
  backend: docker             # 覆写全局档位
  docker:
    memory: 1g                # 按Agent覆写限额
```

审计字段：`tool_invocations` 增加 `execution_backend`（local/docker）与 `container_id`（nullable）。

## Success Criteria *(mandatory)*

### Measurable Outcomes

| # | 验收标准 |
| --- | --- |
| SC-001 | 未配置 backend 的部署：全量既有测试零修改通过；shell 执行路径与现状逐字节一致（local 零回归） |
| SC-002 | docker 档下 `cat /etc/os-release` 返回容器发行版≠宿主；执行后 `docker ps -a` 无残留容器 |
| SC-003 | 容器内 `touch /workspace/.oryxos/marker` 后，宿主侧 file 工具读取成功（双向映射） |
| SC-004 | 制造超时（如 `sleep 600` + 短超时）：调用失败且 `docker ps -a` 无泄漏容器（终止联动生效） |
| SC-005 | 默认网络隔离：容器内 `curl/wget` 外网失败（`--network none`） |
| SC-006 | 停 daemon 后调用 shell：返回含排障提示的错误、审计留痕、进程存活；恢复 daemon 后自动恢复（无需重启 OryxOS） |
| SC-007 | 审计页可按 backend 筛选；docker 记录含容器 ID |
| SC-008 | 全局 docker + 单 Agent `sandbox: local`：两 Agent 同命令返回宿主/容器两种发行版（覆写生效） |

## Assumptions

1. Phase 1 目标形态：OryxOS 直跑宿主机（tar.gz / `make docker` 之外的本地路径），宿主有 docker CLI；容器化 OryxOS + docker 档为 advanced（socket 安全设计独立消化，RQ-1）。
2. docker 档不引入任何新 Maven 依赖（CLI 经 ProcessBuilder 调用，复用 ShellTools 既有超时/字符集/输出截断）。
3. 白名单与 Tool Policy 的行为、配置、审计口径零改动（FR-007 只约束顺序不变）。
4. SSH 远程档、file/http 容器化、每 Agent 长驻容器、镜像管理台——均为后续档/后续特性，本期不做（见 Input）。
5. `--user` 非 root 与卷属主的配合：默认 `nobody`，卷属主不匹配导致写失败时错误信息给出 chmod/chown 提示（文档 + EC-3）。
