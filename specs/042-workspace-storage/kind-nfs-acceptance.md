# Docker Desktop / kind NFS 验收（2026-09-18）

## 环境与边界

已实际搭建独立集群 `oryxos-042-nfs`：一个 control-plane 上运行 NFS-Ganesha 与专用测试 PostgreSQL，两个 worker 各运行一个 OryxOS 副本，强制 Pod anti-affinity。使用现有应用镜像 `oryxos:042-acceptance`（应用提交 `027e4d0`，镜像摘要见 acceptance.md）。原有开发服务、数据库和验收环境未被替换。

NFS 服务采用官方 GHCR amd64 固定摘要：`ghcr.io/kubernetes-sigs/nfs-ganesha@sha256:31a92f9a3ef0fa56615f06e0069f90151b2b7098d2dd905b5cfa7250b29a665d`。该镜像是 Ganesha 基础镜像，须显式启动 rpcbind、DBus 和 ganesha.nfsd；不是动态 provisioner。使用静态 PV/PVC，服务无需 Kubernetes API token，初始排查时创建的多余 RBAC 已删除。

工作区 PVC `oryxos-nfs/workspace-nfs` 绑定 `042-nfs-workspace`，RWX、Retain；NFS 导出 `/workspace`，工作区身份 `kind-nfs-042`。两个 worker 的 findmnt 均确认 `nfs4,vers=4.1,hard,proto=tcp,timeo=30,retrans=2,sec=sys`。NFS 后端是 control-plane `/var` 下 Docker volume 的 ext4；应用通过网络 NFS 协议访问，未用 hostPath 替代客户端挂载。种子 Pod 经 NFS 创建身份文件并设置 uid/gid 1000。

这是同一个 Docker Desktop/WSL2 内核上的协议集成验收。NFS 服务本身仍是单实例；不能证明物理主机故障容忍、独立内核客户端缓存行为或生产 NFS 服务的冗余。因此不把此结果登记为完整 T013 通过。

## 实测结果

| 检查 | 结果 |
| --- | --- |
| 两 worker / 共享卷启动 | 两副本 Ready，真实 NFSv4.1 挂载 |
| 运行注册表收敛 | A 完成一次创建及 19 次更新后，轮询 B `/api/v1/profiles`，20 次均 ≤3 秒，最大 0.915 秒；采样间隔 50ms。不是规范文件 GET，也不含删除/独立内核验收 |
| 旧版本并发冲突 | 同一 If-Match 并发写 A/B，结果 200/409，成功内容保留 |
| Skill | 创建共享 Skill 并绑定 Agent，B 可读；相对链接 `../../../skills/nfs-skill` 正确 |
| 知识源 | 经 API 上传 Markdown，B 可读知识库且文档 READY；源文件 SHA-256 已采集 |
| NFS 服务暂停 | 只暂停 NFS 容器任务，数据库和应用保持运行；两副本 readiness 约 14.135 秒后均 503，健康请求维持毫秒级响应 |
| NFS 服务恢复 | finally 恢复任务，约 1.017 秒后两副本 readiness 均 200，随后 Skill/知识源操作通过 |
| worker A 暂停 | B 的运行注册表和 READY 知识库仍可读；finally 恢复 A |
| 管理台 | `http://localhost:18046/admin/`；B 直接入口 `http://localhost:18047/admin/`，均仅绑定 loopback |

故障实验未验证 hard-mount 阻塞期间直连 Pod 写请求能在固定期限内返回 503；readiness 摘流与每个文件系统调用有界完成是不同保证。未将单宿主恢复当作新物理主机恢复。

## 本机操作与证据

关键时间样本和故障结果已归档到 [结构化证据](evidence/kind-nfs.json)。所有生成清单、探针脚本与日志位于 `/tmp/oryxos-042-nfs/`：`kind.yaml`、`nfs-static.yaml`、`seed.yaml`、`app.yaml`、`probe.py`、`fault.py`、`assets.py`、`node-fault.py` 及对应 JSON。`app-prereqs.yaml` 含专用测试密钥，仅保存在本机 0600 文件，不提交。此目录不是生产部署包；删除集群前需另行导出数据，Retain 不能保护已删除的 Docker 节点卷。

```sh
kubectl --context kind-oryxos-042-nfs -n oryxos-nfs get pods,pvc -o wide
# 当前端口转发退出后可重新建立管理台入口：
kubectl --context kind-oryxos-042-nfs -n oryxos-nfs port-forward svc/oryxos 18046:8080
# 该 Service 入口适用于 UI；再次测量 A/B 时必须分别转发两个具体 Pod。
```

参考：[NFS-Ganesha 官方镜像](https://github.com/kubernetes-sigs/nfs-ganesha-server-and-external-provisioner/pkgs/container/nfs-ganesha)、[VFS 导出配置](https://github.com/nfs-ganesha/nfs-ganesha/blob/next/src/config_samples/vfs.conf)。
