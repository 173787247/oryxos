# 共享卷支持矩阵与运维（027 / 042）

多副本部署（`oryxos.cluster.enabled=true`）时，`.oryxos/` 工作区（Agent 目录、Skill 库、人格、知识源文件）必须放在**所有副本可读写的同一共享目录**。本文声明 OryxOS 对共享卷的依赖边界与推荐配置。

## 依赖什么（必须满足）

| 依赖 | 说明 |
|------|------|
| **读写可见性（close-to-open 一致性）** | 副本 A 关闭文件后，副本 B 重新打开必须读到新内容。NFS 默认保证；OryxOS 的变更感知走「数据库版本号总线」——版本号在文件落盘**之后**递增，读到新版本号即保证能读到新文件内容 |
| **同卷 rename 原子性** | 文件覆盖与下载发布走「临时文件写全 + 原子改名」（`ATOMIC_MOVE`）；卷必须保证同目录 rename 原子（POSIX 语义，NFS/CephFS/本地盘均满足）。不支持原子移动的文件系统会**直接报错而非降级**——这是刻意的：静默降级等于放弃「绝无半写文件」承诺 |
| **相对软链接与排他创建目录** | Agent 绑定依赖相对软链接；管理写预约依赖同一路径只能被一个写者成功创建目录。首期内置 provider 还要求原生 POSIX 属性与本机执行视图 |

## 不依赖什么（明确声明）

| 不依赖 | 原因 |
|--------|------|
| **跨机器文件锁**（flock/fcntl over NFS） | 跨机器文件锁在 NFS 上会静默失效（业界实证教训）。026/027 的任务协调使用共享数据库 CAS 租约；042 的管理文件提交另用持久预约目录（不自动过期），文件锁不承担正确性职责 |
| **inotify / 文件系统事件** | NFS 上远端写不产生 inotify 事件。集群档**不装配** WatchService watcher，变更感知全部走版本号轮询（默认 1s，`oryxos.cluster.workspace-poll-interval` 可调）；单机档保留 watcher 零回归 |
| **文件 mtime 精度/单调性** | 变更判定不依赖时间戳比较（版本号为数据库单调序号；知识索引用内容 sha256 指纹） |

## 支持矩阵

| 卷类型 | 支持 | 备注 |
|--------|------|------|
| NFS v3/v4（默认挂载参数） | ✅ | close-to-open 默认开启即可；无需 `sync` 强制同步挂载 |
| NFS + `nolock` | ✅ | 不依赖文件锁，`nolock` 无影响 |
| NFS + 关闭一致性的激进缓存（如 `nocto`、超长 `actimeo`） | ❌ 不支持 | 破坏 close-to-open 可见性，B 副本可能长时间读到旧内容 |
| K8s RWX PVC（NFS / CephFS / 云厂商文件存储） | ✅ | 推荐部署形态（039 Helm 交付） |
| CephFS / GlusterFS 直挂 | ✅ | 须满足上述依赖并做实际部署验证 |
| 对象存储挂载器（s3fs / JuiceFS 非 POSIX 模式等） | ⚠️ 谨慎 | 须确认 rename 原子与 close-to-open；多数 s3fs 形态 rename 非原子，**不支持** |
| 本地盘多进程（同机多副本） | ✅ | 天然满足；适合验证环境 |
| SQLite 数据库文件放 NFS | ❌ 永不支持 | 与本文无关但常见误区：集群档必须共享 PostgreSQL（026 启动即拒 SQLite），`.oryxos/oryxos.db` 不应存在于集群档 |

## 推荐挂载与部署要点

- **NFS**：默认参数即可（`vers=4.1,actimeo` 保持默认量级）；不要加 `nocto`。
- **K8s（039 起为标准姿势）**：官方 Helm Chart 已把 `.oryxos` 声明为 RWX PVC 挂到 `/data/.oryxos`（镜像 `ORYXOS_ROOT` 原生指向），`workspace.storageClassName` 指定支持 RWX 的存储类（NFS provisioner / CephFS / 云厂商文件存储）；`replicaCount>1` 而 `workspace.accessMode` 未声明 RWX 时 chart 渲染期即拒并指路本文。安装与排查见 [K8s 部署指南](K8sDeployGuide.md)。本地 kind 验收用单节点 hostPath 静态 PV 提供同宿主共享访问，不代表真实跨主机 RWX 验收。
- **直接改盘（运维逃生舱）**：042 起集群档通过周期对账最终发现直接改盘；直接改盘必须在停写维护窗口，恢复后可调 `POST /api/v1/workspace/refresh` 加快全副本重载；单机档仍由 watcher 自动感知。
- **误配自检边界**：042 启动核验身份、相对链接和原子替换等本机能力，但无法证明两个主机挂载同一卷或故障后仍可用；真实部署仍须按 [双主机验收步骤](../scripts/042-shared-acceptance.md) 验证。

## 行为语义速查

| 场景 | 行为 |
|------|------|
| A 副本建/改/删 Agent、Skill、人格 | 健康共享存储上的目标为 B 副本 ≤3s 生效（1s 轮询 + 重载余量）；真实双主机计时仍待 T013 验收 |
| 共享卷短暂不可达 | 重载失败保留上一份注册表快照并告警，恢复后下一轮自愈；绝不清空注册表 |
| 两副本同时触发同一知识库重建 | 恰好一个执行（CAS 认领），另一个收到 409「构建进行中」 |
| 执行重建的副本崩溃 | 认领超时（`lease-ttl`，默认 30s）后任一健康副本可重新触发接管 |
| 写入中途进程被杀 | 原子覆盖的最终路径保留旧完整或新完整文件；可能遗留不可发布暂存和阻塞预约，多文件中断须按下文恢复 |

## 042：可插拔存储及共享卷身份

027 的目录、相对软链接、PostgreSQL `workspace_versions` 和各域重载继续使用。042
新增显式 `WorkspaceStorageProvider` 注册表，业务使用所选插件的 Java NIO `Path`，
`Files` 的流、属性、目录、复制、移动、删除和链接操作由该 Path 的
`FileSystemProvider` 分派。新增插件作为 Spring Bean 注册；轻 CLI 使用 Java
`ServiceLoader`。插件 id 不得重复，缺少所需能力会拒绝启动。

首期提供 `local`（兼容默认配置）和 `shared-posix`。共享部署必须显式选后者：

```yaml
oryxos:
  root: /data/.oryxos
  workspace:
    storage:
      provider: shared-posix
      identity: production-workspace-01
  cluster:
    enabled: true
    workspace-reconcile-interval: 30s
```

管理员先把真实 NFS/NAS/CephFS RWX 卷挂到根目录，再在卷内预置一个普通文件
`.workspace-id`，内容为相同的 identity（允许末尾换行）。应用不会为共享模式创建根或
身份文件。缺失、软链接、不匹配、运行中身份丢失均拒绝访问，不回退本地目录。
identity 是误挂载防护，不是防恶意管理员的认证凭据，也不能证明存储有多副本。

轻 CLI 不读取 `application.yml`：同时设置 `ORYXOS_ROOT`、
`ORYXOS_WORKSPACE_STORAGE_PROVIDER=shared-posix` 和
`ORYXOS_WORKSPACE_STORAGE_IDENTITY`，或相应 Java 系统属性。集群在线管理统一走
管理 API；离线 CLI 导入/初始化和人工改盘必须在停止所有管理写者的维护窗口运行。

### 插件扩展约束

契约在 `oryxos-core` 的 `io.oryxos.core.workspace`。当前运行层要求流 I/O、原子改名、
软链接和本机执行视图。`resolve` 及派生 Path 必须保持所选 provider，不能通过
`Path.of(path.toString())` 重建默认路径。原生进程通过 `storage.nativePath` 显式取得
执行视图；PDF 等可用流读取的组件直接用插件流。

扩展接入步骤：实现 `WorkspaceStorageProvider.id/open`，返回 `WorkspaceStorage`；将 provider
注册为 Spring Bean，然后配置 `oryxos.workspace.storage.provider=<id>`。轻 CLI 扩展另在插件
JAR 的 `META-INF/services/io.oryxos.core.workspace.WorkspaceStorageProvider` 中登记实现类。
标准读写无需修改业务服务：它们接收 `storage.root()`，通过 `Files` 使用对应的
`FileSystemProvider`。插件须验证派生 Path、流关闭、软链接、原子覆盖、越界拒绝及
显式 `nativePath` 的语义；不得只换 id 却把调用静默发回默认文件系统。

对象存储插件将来可以提供受控物化执行视图、缓存和上传发布协议，但必须先满足能力
契约。普通 OSS/S3 key 不等价于 POSIX 文件路径；不能仅改 endpoint 就宣称兼容
现有原子 rename 和相对软链接。首期没有实现原生 OSS/S3 插件。

### 自动收敛与 readiness

常规通知仍走 027 的版本号轮询。每 30 秒（可配置正 Duration）另做完整域对账，
覆盖文件已发布、版本通知遗漏的窗口。单域失败下一 tick 重试，不推进该域成功时间；
数据库不可用时保留旧注册表并继续到期文件对账，恢复后再次对齐版本。
扫描及派生文件时显式区分真实目标缺失与存储 I/O 失败；身份文件/挂载根的缺失属于
存储不可用，不能当成业务目录删除。读取异常保留原因并触发重试，不发布空注册表
来覆盖已有快照；这也覆盖错误处理前存储已恢复的瞬时故障。

`/actuator/health/readiness` 包含 `workspace`。存储探针用单独守护线程每 5 秒核验身份并执行独立临时文件的写入/原子替换/删除；
结果超过 15 秒未更新则视为不可用，因此卡住的 NFS 系统调用不会卡住健康 HTTP 请求，
也不会无限创建探针线程。健康响应只提供 provider/可用性/重载状态，不泄露路径凭据。
管理 HTTP 入口先读取非阻塞的存储可用性快照；探针失败或超过 15 秒失效后，新管理请求在任何文件 I/O 前返回 503，直连 Pod 同样生效。重载/通知失败不会单独禁止健康存储上的管理修复。
运行时文件访问仍逐次检查身份。已放行请求仍可能在存储随后的故障中阻塞；底层内核 I/O 卡死不会因为 Java 中断就必然取消；
探针摘流不能替代 NFS 客户端的超时及运维设置。

### 管理并发、版本冲突与中断恢复

Agent、Skill、知识库、Persona 与工作区编辑 HTTP API 在认证之后竞争共享根的
`.workspace-write` 持久预约目录。目录的排他创建串行化多个实例的管理写请求；
占用返回 HTTP 409。读响应携带 `X-Workspace-Revision`；客户端提交时通过
`If-Match` 回传该值，旧版本返回 409，处理器不会执行。管理台 Agent、Skill、Persona、知识、绑定及工作区编辑入口均保存读取快照的版本并
回传它；编辑视图读取规范文件，不给滞后运行注册表的旧内容配上最新版本。为兼容旧客户端，不带 If-Match 的请求只享有写互斥，不具备陈旧编辑检测；
接入方需要在读取编辑内容时保存版本，不能在提交前自动取新版本替代。

每个文件仍通过同卷临时写和原子替换发布。Agent 多文件写入预先保留旧内容及
`.workspace-transaction/manifest`，逐文件替换，完成后写 committed 标记再清理。
读者可能看见不同文件处于不同提交阶段；这不是目录级快照事务，也不是文件与数据库
或绑定目录的联合事务。知识索引使用单次捕获的文档快照，让内容和摘要对应同一版本。

预约**不自动过期**：仅靠 PG 租约无法阻止已经暂停的旧写者恢复后写 NFS。进程异常退出
会留下预约并阻止新的管理写入，需要维护恢复。这是首期写入安全边界；运行/读取与
其他健康副本的服务能力不等于元数据管理写入能无干预故障接管。不要直接删除锁来解堵。

恢复步骤：

1. 停止所有实例、CLI、自动部署及人工文件写者，备份卷（保留软链接和恢复日志）。
2. 只读检查：`python3 bin/workspace-recover.py /mnt/workspace --identity production-workspace-01`。
3. 核对输出中的预约 token、事务数量；检查 `.workspace-write/operation` 及应用审计。
   脚本仅恢复 Agent 文件事务，不推断技能/知识删除等其他管理操作的业务意图；这些操作
   应先从备份恢复或人工核实当前完整状态，再释放预约。若进程在发布 owner 前或释放
   owner 后崩溃，检查输出会显示 `reservation: "OWNERLESS-RESERVATION"` 与
   `ownerless: true`；这是需要人工确认的持久恢复状态，不会按时间自动接管。
4. 显式恢复：`python3 bin/workspace-recover.py /mnt/workspace --identity production-workspace-01 --token <检查所得token> --apply --confirm-writers-stopped`。
   ownerless 情况必须原样使用显式哨兵：`python3 bin/workspace-recover.py /mnt/workspace --identity production-workspace-01 --token OWNERLESS-RESERVATION --apply --confirm-writers-stopped`。
   未完成的 Agent 文件事务回滚，已 committed 的保留新内容；日志/备份改名保存为
   `.workspace-recovered-*`，不直接销毁。脚本更新管理版本，并保留恢复档案供审计。
5. 校验文件 hash 与相对链接，启动一个实例检查重载，再启动其他实例。确认恢复后按
   自有保留策略清理恢复档案；不要在线按“文件年龄”自动清理活跃暂存/预约。

预约前的格式/版本/占用拒绝不推进代次。处理器已执行后返回 4xx 时，保守推进代次并将
预约归档为 `.workspace-rejected-<token>`（`rejected-unverified`），然后释放写入阻塞；
这不表示副作用已回滚。失败客户端必须重新读取后再编辑，不能自动采用新代次重试。
这些诊断档案随卷备份，在维护窗口核实后按自有保留策略清理。服务端异常或归档失败
保留阻塞预约和诊断标记。所有未经该管理入口的
写者必须遵守离线维护约束；本机制不拦截管理员直接执行的 shell，也不取代底层备份。

### 输出发布

每次 Agent 运行拥有独立 `output/<agent>/<run-uuid>/`。本机 Shell 在
`.staging/output/<agent>/<run>/<invocation>/` 执行，成功才原子移动到
`output/<agent>/<run>/shell/<invocation>/`；失败/超时不会伪装成已发布产物。
Shell 使用绝对路径访问其他已获授权资源仍有其原有权限，不在这个产物提交事务内。
命令须等待自身后台写者结束；前台退出码不能证明后台子进程已停，首期不提供后台
写入的快照隔离。
下载先写临时文件，网络失败、大小超限、最终守卫拒绝都保留旧目标文件。
共享模式拒绝未协调的 append，避免把读改写误当并发安全追加；默认本地模式保留原有
追加语义。工具写入根之外的显式授权路径仍受沙箱约束，不属于共享工作区持久性承诺。

### Helm 与真实共享验收

```yaml
workspace:
  existingClaim: oryxos-shared-rwx
  accessMode: ReadWriteMany
  provider: shared-posix
  identity: production-workspace-01
```

设置 existingClaim 后 chart 不创建或接管 PVC，卸载 chart 不应删除它。管理员负责确认
该 PVC 实际 RWX、共享可见和支持原子替换；声明 accessMode 不构成存储行为证明。
未设置 existingClaim 时保留 chart 创建 PVC 的旧行为。

迁移前停所有写者；使用保留软链接的工具（例如 `rsync -a`，不要 `-L`）复制卷，分别
生成文件 SHA-256 清单与 `readlink` 清单，恢复后核对。备份策略应包含所有工作区内容，
恢复日志与相对绑定链接；PG/mem0 的独立备份仍按原策略执行，联合恢复点由运维协调。

真实验收至少需要两个独立主机上的实例：A 编辑后 B 读取、漏通知后 B 自动对账、
错挂/断挂拒写、并发旧版本冲突、运行同名产物隔离、停止 A 后 B 仍可读取、从备份到
新节点恢复后 hash/链接一致。记录节点、存储类型、挂载参数、时间戳及请求结果。
单机两个目录、同机 Docker volume、两个 JVM 的测试只能证明应用协议，不能登记为
跨主机存储高可用通过。当前已完成单宿主 kind 双 Pod、真实 UI、身份故障注入及备份恢复验证；T013 的真实双主机共享存储验收仍待提供环境，详见 [042 验收记录](../specs/042-workspace-storage/acceptance.md)。
