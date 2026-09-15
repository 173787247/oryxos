# 共享卷支持矩阵（027 文件面分布式）

多副本部署（`oryxos.cluster.enabled=true`）时，`.oryxos/` 工作区（Agent 目录、Skill 库、人格、知识源文件）必须放在**所有副本可读写的同一共享目录**。本文声明 OryxOS 对共享卷的依赖边界与推荐配置。

## 依赖什么（必须满足）

| 依赖 | 说明 |
|------|------|
| **读写可见性（close-to-open 一致性）** | 副本 A 关闭文件后，副本 B 重新打开必须读到新内容。NFS 默认保证；OryxOS 的变更感知走「数据库版本号总线」——版本号在文件落盘**之后**递增，读到新版本号即保证能读到新文件内容 |
| **同卷 rename 原子性** | 所有工作区写入走「临时文件写全 + 原子改名」（`ATOMIC_MOVE`）；卷必须保证同目录 rename 原子（POSIX 语义，NFS/CephFS/本地盘均满足）。不支持原子移动的文件系统会**直接报错而非降级**——这是刻意的：静默降级等于放弃「绝无半写文件」承诺 |

## 不依赖什么（明确声明）

| 不依赖 | 原因 |
|--------|------|
| **跨机器文件锁**（flock/fcntl over NFS） | 跨机器文件锁在 NFS 上会静默失效（业界实证教训）。一切互斥正确性由共享数据库的 CAS 租约保证（026/027 协调面），文件锁不承担任何正确性职责 |
| **inotify / 文件系统事件** | NFS 上远端写不产生 inotify 事件。集群档**不装配** WatchService watcher，变更感知全部走版本号轮询（默认 1s，`oryxos.cluster.workspace-poll-interval` 可调）；单机档保留 watcher 零回归 |
| **文件 mtime 精度/单调性** | 变更判定不依赖时间戳比较（版本号为数据库单调序号；知识索引用内容 sha256 指纹） |

## 支持矩阵

| 卷类型 | 支持 | 备注 |
|--------|------|------|
| NFS v3/v4（默认挂载参数） | ✅ | close-to-open 默认开启即可；无需 `sync` 强制同步挂载 |
| NFS + `nolock` | ✅ | 不依赖文件锁，`nolock` 无影响 |
| NFS + 关闭一致性的激进缓存（如 `nocto`、超长 `actimeo`） | ❌ 不支持 | 破坏 close-to-open 可见性，B 副本可能长时间读到旧内容 |
| K8s RWX PVC（NFS / CephFS / 云厂商文件存储） | ✅ | 推荐部署形态（028 容器交付的标准姿势） |
| CephFS / GlusterFS 直挂 | ✅ | 满足两项依赖即可 |
| 对象存储挂载器（s3fs / JuiceFS 非 POSIX 模式等） | ⚠️ 谨慎 | 须确认 rename 原子与 close-to-open；多数 s3fs 形态 rename 非原子，**不支持** |
| 本地盘多进程（同机多副本） | ✅ | 天然满足；适合验证环境 |
| SQLite 数据库文件放 NFS | ❌ 永不支持 | 与本文无关但常见误区：集群档必须共享 PostgreSQL（026 启动即拒 SQLite），`.oryxos/oryxos.db` 不应存在于集群档 |

## 推荐挂载与部署要点

- **NFS**：默认参数即可（`vers=4.1,actimeo` 保持默认量级）；不要加 `nocto`。
- **K8s（039 起为标准姿势）**：官方 Helm Chart 已把 `.oryxos` 声明为 RWX PVC 挂到 `/data/.oryxos`（镜像 `ORYXOS_ROOT` 原生指向），`workspace.storageClassName` 指定支持 RWX 的存储类（NFS provisioner / CephFS / 云厂商文件存储）；`replicaCount>1` 而存储类非 RWX 时 chart 渲染期即拒并指路本文。安装与排查见 `docs/K8sDeployGuide.md`。本地 kind 验收用单节点 hostPath 静态 PV 等价 RWX（同节点天然满足两项依赖）。
- **直接改盘（运维逃生舱）**：集群档下绕过管理台直接修改共享卷不会被自动感知——改完调 `POST /api/v1/workspace/refresh` 触发全副本重载；单机档仍由 watcher 自动感知。
- **误配自检边界**：卷「是否真正共享」无法从配置判定，启动不做探测（探测在慢 NFS 上误判、同机部署上漏判）；部署后按 `specs/027-file-plane/quickstart.md` 的走查步骤验证一次即可。

## 行为语义速查

| 场景 | 行为 |
|------|------|
| A 副本建/改/删 Agent、Skill、人格 | B 副本 ≤3s 生效（1s 轮询 + 重载余量） |
| 共享卷短暂不可达 | 重载失败保留上一份注册表快照并告警，恢复后下一轮自愈；绝不清空注册表 |
| 两副本同时触发同一知识库重建 | 恰好一个执行（CAS 认领），另一个收到 409「构建进行中」 |
| 执行重建的副本崩溃 | 认领超时（`lease-ttl`，默认 30s）后任一健康副本可重新触发接管 |
| 写入中途进程被杀 | 共享卷上只有旧完整或新完整文件，绝无半写状态 |
